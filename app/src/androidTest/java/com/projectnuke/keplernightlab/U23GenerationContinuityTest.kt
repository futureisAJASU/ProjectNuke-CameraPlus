package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U2.3-C3 — GENERATION CONTINUITY / DELAYED-UPDATE RACE CHARACTERIZATION.
 *
 * Bounded, UI-independent, test-owned rows only. No production changes.
 *
 * 1. Delay distribution: >=30 supported same-size writes per format (JPEG + native HEIF),
 *    each proven by readback SHA; first generation-change time sampled at
 *    0/10/25/50/100/200/500/1000/2000 ms. Idle + loaded variants reported separately.
 * 2. Race probe: a cold-start-like reader polls (generation, content SHA) while a writer
 *    mutates the same URI; counts samples showing OLD generation with NEW bytes.
 * 3. Reboot prep capture WITHOUT reboot (reboot not authorized): persists exact row
 *    identity + provider version, then removes rows and requests permission.
 * 4. Deletion uses PRESENT/ABSENT/QUERY_FAILED tri-state; query exceptions never count
 *    as absence; final cleanup ASSERTS absence of every owned URI.
 */
@RunWith(AndroidJUnit4::class)
class U23GenerationContinuityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val TAG = "U23C3"
    private val evidence = JSONObject()

    private val scheduleMs = longArrayOf(0, 10, 25, 50, 100, 200, 500, 1000, 2000)

    @Test
    fun characterizeGenerationContinuityC3() {
        evidence.put("device", JSONObject()
            .put("model", Build.MODEL).put("release", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
        log("START-C3 device=${Build.MODEL} release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        val volumes = try {
            MediaStore.getExternalVolumeNames(context).sorted()
        } catch (e: Exception) {
            listOf("error:${e.javaClass.simpleName}")
        }
        evidence.put("externalVolumeNames", JSONArray(volumes))

        val owned = mutableListOf<Uri>()
        try {
            val jpeg = createProductionRow(OutputFormat.JPEG, 128, 128, "u23c3-jpeg")
            val heif = createProductionRow(OutputFormat.HEIF, 128, 128, "u23c3-heif")
            owned.add(jpeg.uri); owned.add(heif.uri)
            log("VOL-C3 jpegUri=${jpeg.uri} volume=${volumeOf(jpeg.uri)} provGen=${providerGen(volumeOf(jpeg.uri))} provVer=${providerVer(volumeOf(jpeg.uri))}")
            log("VOL-C3 heifUri=${heif.uri} volume=${volumeOf(heif.uri)} provGen=${providerGen(volumeOf(heif.uri))} provVer=${providerVer(volumeOf(heif.uri))}")

            // 1. Idle delay distributions, >=30 writes per format.
            val jpegIdle = delayDistribution(jpeg, iterations = 30, tag = "idle")
            val heifIdle = delayDistribution(heif, iterations = 30, tag = "idle")
            evidence.put("delayJpegIdle", jpegIdle.json)
            evidence.put("delayHeifIdle", heifIdle.json)
            log("DELAY-C3 jpeg idle ${jpegIdle.summary}")
            log("DELAY-C3 heif idle ${heifIdle.summary}")

            // 2. Loaded variant: extra owned rows + verifier pressure, 10 writes per format.
            val extra = mutableListOf(
                createProductionRow(OutputFormat.JPEG, 128, 128, "u23c3-load"),
                createProductionRow(OutputFormat.HEIF, 128, 128, "u23c3-load")
            )
            extra.forEach { owned.add(it.uri) }
            val jpegLoad = delayDistribution(jpeg, iterations = 10, tag = "loaded",
                pressure = { runPressure(extra.map { r -> r.uri to r.expectation }) })
            val heifLoad = delayDistribution(heif, iterations = 10, tag = "loaded",
                pressure = { runPressure(extra.map { r -> r.uri to r.expectation }) })
            evidence.put("delayJpegLoaded", jpegLoad.json)
            evidence.put("delayHeifLoaded", heifLoad.json)
            log("DELAY-C3 jpeg loaded ${jpegLoad.summary}")
            log("DELAY-C3 heif loaded ${heifLoad.summary}")

            // 3. Concurrent-modification race probe per format.
            val raceJpeg = raceProbe(jpeg, writes = 20)
            val raceHeif = raceProbe(heif, writes = 20)
            evidence.put("raceJpeg", raceJpeg.json)
            evidence.put("raceHeif", raceHeif.json)
            log("RACE-C3 jpeg ${raceJpeg.summary}")
            log("RACE-C3 heif ${raceHeif.summary}")

            // 4. Reboot prep WITHOUT reboot (not authorized): capture, persist, remove, request.
            evidence.put("rebootPrep", rebootPrepWithoutReboot(owned))

            persistEvidence()
            log("DONE-C3")
        } finally {
            owned.toList().forEach { uri ->
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            }
            owned.forEach { uri ->
                val absent = awaitRowAbsentMs(uri, 8000L) != null
                Log.d(TAG, "CLEANUP-C3 uri=$uri absent=$absent")
                assertTrue("CLEANUP-C3: exact owned row must be absent: $uri", absent)
            }
            persistEvidence()
            Log.d(TAG, "CLEANUP-C3 completed")
        }
    }

    // ------------------------------------------------------- delay distribution

    private data class Dist(val summary: String, val json: JSONObject)

    private fun delayDistribution(row: CohortRow, iterations: Int, tag: String, pressure: (() -> Unit)? = null): Dist {
        val base = readExactBytes(row.uri)
        val toggleOffset = base.size / 2
        val observed = mutableListOf<Long>()
        var censored = 0
        var lateLanding = 0
        var provenWrites = 0
        repeat(iterations) { i ->
            pressure?.invoke()
            val expected = base.copyOf().also { it[toggleOffset] = if (i % 2 == 0) 0xA5.toByte() else 0x5A.toByte() }
            val expectedSha = sha256(expected)
            val g0 = queryGeneration(row.uri)
                ?: error("DELAY-C3: null generation pre-write fmt=${row.format} iter=$i")
            val tClose = writeExactBytes(row.uri, expected)
            val after = readExactBytes(row.uri)
            assertTrue("DELAY-C3: readback must equal written payload fmt=${row.format} iter=$i",
                after.contentEquals(expected))
            assertEquals("DELAY-C3: readback SHA must match fmt=${row.format} iter=$i", expectedSha, sha256(after))
            provenWrites++
            // Sampled observation; stop at first difference from G0.
            var firstChangeMs: Long? = null
            for (target in scheduleMs) {
                val wait = (tClose + target) - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
                val g = queryGeneration(row.uri)
                if (g != null && g != g0) {
                    firstChangeMs = SystemClock.elapsedRealtime() - tClose
                    break
                }
            }
            if (firstChangeMs != null) {
                observed.add(firstChangeMs)
            } else {
                // Censored at 2000ms: one confirmatory sample at +5000ms distinguishes
                // very-late landing from a generation that missed the write entirely.
                val wait = (tClose + 5000) - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
                val gLate = queryGeneration(row.uri)
                censored++
                if (gLate != null && gLate != g0) {
                    lateLanding++
                    observed.add(SystemClock.elapsedRealtime() - tClose)
                }
            }
        }
        fun pct(p: Double): Long {
            if (observed.isEmpty()) return -1
            val s = observed.sorted()
            return s[((s.size * p).toInt()).coerceAtMost(s.size - 1)]
        }
        val json = JSONObject()
            .put("format", row.format.name).put("load", tag).put("n", iterations)
            .put("provenWrites", provenWrites).put("censoredOver2000ms", censored)
            .put("lateLandingBy5000ms", lateLanding).put("neverLandedBy5000ms", censored - lateLanding)
            .put("min", observed.minOrNull() ?: -1).put("p50", pct(0.50))
            .put("p90", pct(0.90)).put("p95", pct(0.95)).put("max", observed.maxOrNull() ?: -1)
            .put("note", "observed maximum is device evidence, NOT a platform guarantee")
        val summary = "fmt=${row.format} n=$iterations proven=$provenWrites censored2000=$censored " +
            "late5000=$lateLanding never5000=${censored - lateLanding} " +
            "min=${observed.minOrNull()} p50=${pct(0.50)} p90=${pct(0.90)} p95=${pct(0.95)} max=${observed.maxOrNull()} ms"
        log("DELAYRAW-C3 $summary")
        return Dist(summary, json)
    }

    private fun runPressure(rows: List<Pair<Uri, GalleryExportExpectation>>) {
        rows.forEach { (uri, exp) -> verifyGalleryExportResult(context, uri.toString(), exp) }
    }

    // --------------------------------------------------------------- race probe

    private data class RaceSample(val t: Long, val gen: Long?, val sha: String?)

    private fun raceProbe(row: CohortRow, writes: Int): Dist {
        val base = readExactBytes(row.uri)
        val toggleOffset = base.size / 2
        val samples = mutableListOf<RaceSample>()
        val running = AtomicBoolean(true)
        val reader = Thread {
            while (running.get()) {
                val t = SystemClock.elapsedRealtime()
                val g = queryGeneration(row.uri)
                val sha = try {
                    context.contentResolver.openInputStream(row.uri)?.use { sha256(it.readBytes()) }
                } catch (_: Exception) {
                    null
                }
                synchronized(samples) { samples.add(RaceSample(t, g, sha)) }
            }
        }
        var hits = 0
        var windows = 0
        var windowsWithAnyChange = 0
        val hitExamples = JSONArray()
        val gensSeen = mutableSetOf<Long>()
        try {
            reader.start()
            repeat(writes) { i ->
                val gOld = queryGeneration(row.uri)
                val expected = base.copyOf().also { it[toggleOffset] = if (i % 2 == 0) 0xA5.toByte() else 0x5A.toByte() }
                val newSha = sha256(expected)
                writeExactBytes(row.uri, expected)
                val tClose = SystemClock.elapsedRealtime()
                // Sanity: the write really landed before the window closes.
                assertTrue("RACE-C3: write must land fmt=${row.format} iter=$i",
                    readExactBytes(row.uri).contentEquals(expected))
                Thread.sleep(260) // let the reader collect the post-close window
                windows++
                val windowSamples = synchronized(samples) {
                    samples.filter { it.t in tClose..(tClose + 250) }
                }
                windowSamples.mapNotNullTo(gensSeen) { it.gen }
                val windowHits = windowSamples.filter { it.gen == gOld && it.sha == newSha }
                if (gOld != null && windowSamples.any { it.gen != null && it.gen != gOld }) windowsWithAnyChange++
                if (windowHits.isNotEmpty() && gOld != null) {
                    hits++
                    if (hitExamples.length() < 3) hitExamples.put(JSONObject()
                        .put("iter", i).put("staleGen", gOld)
                        .put("sampleT", windowHits.first().t - tClose).put("newSha12", newSha.take(12)))
                }
            }
        } finally {
            running.set(false)
            reader.join(5000)
        }
        val json = JSONObject()
            .put("format", row.format.name).put("writes", writes).put("windows", windows)
            .put("windowsWithStaleGenNewBytes", hits)
            .put("windowsWithAnyGenChange", windowsWithAnyChange)
            .put("distinctGenValuesSeen", gensSeen.size)
            .put("singleImmediateQuerySafe", hits == 0)
            .put("examples", hitExamples)
            .put("totalReaderSamples", synchronized(samples) { samples.size })
        val summary = "fmt=${row.format} writes=$writes staleGenNewBytesWindows=$hits/$windows " +
            "changedWindows=$windowsWithAnyChange/$windows distinctGens=${gensSeen.size} " +
            "singleQuerySafe=${hits == 0} readerSamples=${synchronized(samples) { samples.size }}"
        return Dist(summary, json)
    }

    // ------------------------------------------------- reboot prep (no reboot)

    private fun rebootPrepWithoutReboot(owned: MutableList<Uri>): JSONObject {
        val out = JSONObject().put("rebootAuthorized", false)
        listOf(OutputFormat.JPEG, OutputFormat.HEIF).forEach { format ->
            val row = createProductionRow(format, 128, 128, "u23c3-reboot")
            owned.add(row.uri)
            val bytes = readExactBytes(row.uri)
            val vol = volumeOf(row.uri)
            out.put(format.name, JSONObject()
                .put("uri", row.uri.toString())
                .put("volume", vol)
                .put("providerVersion", providerVer(vol))
                .put("providerGeneration", providerGen(vol))
                .put("row", snapshotJson(row.uri))
                .put("sha256", sha256(bytes))
                .put("verifier", describe(verifyGalleryExportResult(context, row.uri.toString(), row.expectation))))
            log("REBOOTPREP-C3 fmt=$format uri=${row.uri} sha=${sha256(bytes).take(12)} " +
                "provVer=${providerVer(vol)} provGen=${providerGen(vol)}")
        }
        try {
            java.io.File(context.filesDir, "u23c3-reboot-prep.json").writeText(out.toString(2))
        } catch (e: Exception) {
            Log.d(TAG, "REBOOTPREP-C3 persist failed: ${e.javaClass.simpleName}")
        }
        // No reboot authorized: remove prep rows now (verified absent by final cleanup),
        // persist prep JSON on host via logcat/docs, and request permission for a later pass.
        log("REBOOT-C3 PERMISSION REQUESTED: reboot NOT executed; prep rows captured then removed; " +
            "re-create on approval to compare version/generation/SHA/verifier post-reboot")
        try {
            java.io.File(context.filesDir, "u23c3-reboot-prep.json").delete()
        } catch (_: Exception) { }
        return out
    }

    // ------------------------------------------------------------- primitives

    private data class CohortRow(val uri: Uri, val format: OutputFormat, val expectation: GalleryExportExpectation)

    private fun createProductionRow(format: OutputFormat, w: Int, h: Int, base: String): CohortRow {
        val bitmap = deterministicBitmap(w, h, w + h)
        try {
            val export = exportNightFusionBitmapToGallery(
                context = context, bitmap = bitmap,
                displayNameBase = "$base-${UUID.randomUUID()}",
                requestedFormat = format, relativeAlbumPath = "Pictures/KeplerU23C3",
                quality = 92, cancellation = NoOpKeplerPipelineCancellation,
                jobDir = null, ownerLease = null
            )
            assertTrue("Production export($format) must succeed: $export", export.success)
            assertEquals("C3 requires actual $format, got ${export.formatUsed}", format, export.formatUsed)
            val uri = Uri.parse(requireNotNull(export.uriString))
            val expectation = GalleryExportExpectation(format, w, h)
            val ver = verifyGalleryExportResult(context, uri.toString(), expectation)
            assertTrue("Fresh $format row must be VERIFIED, got ${describe(ver)}", ver is GalleryExportVerification.Verified)
            return CohortRow(uri, export.formatUsed, expectation)
        } finally {
            bitmap.recycle()
        }
    }

    /** Returns stream-close elapsedRealtime; non-null PFD handle mandatory. */
    private fun writeExactBytes(uri: Uri, payload: ByteArray): Long {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rwt")
            ?: error("WRITE-C3: null PFD for $uri; mutation did NOT execute")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { fos ->
                fos.write(payload); fos.flush(); fos.fd.sync()
            }
        }
        return SystemClock.elapsedRealtime()
    }

    private fun readExactBytes(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri) ?: error("READ-C3: null stream for $uri")
        return stream.use { it.readBytes() }
    }

    private fun queryGeneration(uri: Uri): Long? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun snapshotJson(uri: Uri): JSONObject {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED
        )
        return try {
            context.contentResolver.query(uri, cols, null, null, null)?.use {
                if (!it.moveToFirst()) return JSONObject().put("state", "ABSENT")
                JSONObject()
                    .put("id", it.getLong(0)).put("isPending", it.getInt(1)).put("size", it.getLong(2))
                    .put("dateModified", it.getLong(3)).put("mime", it.getString(4))
                    .put("name", it.getString(5)).put("width", it.getInt(6)).put("height", it.getInt(7))
                    .put("genAdded", it.getLong(8)).put("genModified", it.getLong(9))
            } ?: JSONObject().put("state", "QUERY_FAILED")
        } catch (e: Exception) {
            JSONObject().put("state", "QUERY_FAILED:${e.javaClass.simpleName}")
        }
    }

    private enum class RowPresence { PRESENT, ABSENT, QUERY_FAILED }

    private fun queryRowPresence(uri: Uri): RowPresence = try {
        val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?: return RowPresence.QUERY_FAILED
        cursor.use { if (it.moveToFirst()) RowPresence.PRESENT else RowPresence.ABSENT }
    } catch (_: Exception) {
        RowPresence.QUERY_FAILED
    }

    private fun awaitRowAbsentMs(uri: Uri, timeoutMs: Long): Long? {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            when (queryRowPresence(uri)) {
                RowPresence.ABSENT -> return SystemClock.elapsedRealtime() - start
                RowPresence.PRESENT, RowPresence.QUERY_FAILED -> { }
            }
            if (SystemClock.elapsedRealtime() - start >= timeoutMs) return null
            Thread.sleep(100)
        }
    }

    private fun volumeOf(uri: Uri): String = uri.pathSegments.firstOrNull() ?: MediaStore.VOLUME_EXTERNAL

    private fun providerGen(volume: String): String = try {
        if (Build.VERSION.SDK_INT >= 30) MediaStore.getGeneration(context, volume).toString()
        else "unsupported-sdk"
    } catch (e: Exception) {
        "error:${e.javaClass.simpleName}"
    }

    private fun providerVer(volume: String): String = try {
        MediaStore.getVersion(context, volume).toString()
    } catch (e: Exception) {
        "error:${e.javaClass.simpleName}"
    }

    private fun describe(v: GalleryExportVerification?): String = when (v) {
        is GalleryExportVerification.Verified -> "Verified(fmt=${v.detectedFormat})"
        is GalleryExportVerification.PermanentFailure -> "PermanentFailure(reason=${v.diagnosticReason})"
        is GalleryExportVerification.RetryableFailure -> "RetryableFailure(reason=${v.diagnosticReason})"
        null -> "null"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun persistEvidence() {
        try {
            val f = java.io.File(context.filesDir, "u23c3-evidence.json")
            f.writeText(evidence.toString(2))
            Log.d(TAG, "EVIDENCE-C3 path=${f.absolutePath} bytes=${f.length()}")
        } catch (e: Exception) {
            Log.d(TAG, "EVIDENCE-C3 write failed: ${e.javaClass.simpleName}")
        }
    }

    private fun log(msg: String) = Log.d(TAG, msg)

    private fun deterministicBitmap(w: Int, h: Int, seed: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            for (y in 0 until h) for (x in 0 until w) {
                it.setPixel(x, y, android.graphics.Color.argb(255, (x * 4 + seed) % 256, (y * 4 + seed * 3) % 256, ((x + y) * 2 + seed * 5) % 256))
            }
        }
}
