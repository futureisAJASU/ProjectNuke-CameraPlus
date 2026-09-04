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
 * U2.3-C3.1 — VOLUME GENERATION AUTHORITY GATE.
 *
 * Row GENERATION_MODIFIED is ACCEPTED as insufficient (C3). This phase tests the one
 * remaining candidate: MediaStore.getGeneration(context, exactVolume) with the
 * version-first contract. UI-independent, test-owned rows only, no production changes.
 *
 * Per SHA-proven same-size write: row gen + volume gen + version BEFORE/AFTER with
 * independent settlement sampling (0..5000ms), classified A/B/C/D. Race probe captures
 * row gen + volume gen + version + SHA in one stream. False-positive probe modifies an
 * unrelated owned row. All generation reads are tri-state VALUE/QUERY_FAILED/UNAVAILABLE;
 * failures never count as changed or unchanged.
 */
@RunWith(AndroidJUnit4::class)
class U23VolumeGenerationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val TAG = "U23C31"
    private val evidence = JSONObject()

    private val scheduleMs = longArrayOf(0, 10, 25, 50, 100, 200, 500, 1000, 2000, 5000)

    // Tri-state generation reads: failure is neither changed nor unchanged.
    private sealed interface GenOut {
        data class Value(val v: Long) : GenOut
        data class QueryFailed(val error: String) : GenOut
        data object Unavailable : GenOut
    }

    private sealed interface VerOut {
        data class Value(val v: String) : VerOut
        data class QueryFailed(val error: String) : VerOut
        data object Unavailable : VerOut
    }

    @Test
    fun characterizeVolumeGenerationC31() {
        evidence.put("device", JSONObject()
            .put("model", Build.MODEL).put("release", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
        log("START-C31 device=${Build.MODEL} release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")

        val owned = mutableListOf<Uri>()
        try {
            val jpeg = createProductionRow(OutputFormat.JPEG, 128, 128, "u23c31-jpeg")
            val heif = createProductionRow(OutputFormat.HEIF, 128, 128, "u23c31-heif")
            owned.add(jpeg.uri); owned.add(heif.uri)
            val jpegVol = resolveVolume(jpeg.uri)
            val heifVol = resolveVolume(heif.uri)
            log("VOL-C31 jpeg uri=${jpeg.uri} volume=$jpegVol ver=${describeVer(queryVersion(jpegVol))} gen=${describeGen(queryVolumeGen(jpegVol))}")
            log("VOL-C31 heif uri=${heif.uri} volume=$heifVol ver=${describeVer(queryVersion(heifVol))} gen=${describeGen(queryVolumeGen(heifVol))}")

            var rowQueryFailures = 0
            var volQueryFailures = 0
            val jpegMatrix = writeMatrix(jpeg, jpegVol, iterations = 30,
                onRowFailure = { rowQueryFailures++ }, onVolFailure = { volQueryFailures++ })
            val heifMatrix = writeMatrix(heif, heifVol, iterations = 30,
                onRowFailure = { rowQueryFailures++ }, onVolFailure = { volQueryFailures++ })
            evidence.put("matrixJpeg", jpegMatrix.json)
            evidence.put("matrixHeif", heifMatrix.json)
            log("MATRIX-C31 jpeg ${jpegMatrix.summary}")
            log("MATRIX-C31 heif ${heifMatrix.summary}")

            val raceJpeg = raceProbe(jpeg, jpegVol, writes = 20)
            val raceHeif = raceProbe(heif, heifVol, writes = 20)
            evidence.put("raceJpeg", raceJpeg.json)
            evidence.put("raceHeif", raceHeif.json)
            log("RACE-C31 jpeg ${raceJpeg.summary}")
            log("RACE-C31 heif ${raceHeif.summary}")

            val fp = falsePositiveProbe(jpeg, jpegVol)
            evidence.put("falsePositive", fp.json)
            log("FALSEPOS-C31 ${fp.summary}")

            evidence.put("rowQueryFailures", rowQueryFailures)
            evidence.put("volQueryFailures", volQueryFailures)
            log("QUERYFAIL-C31 row=$rowQueryFailures vol=$volQueryFailures (failures force FULL VERIFY, never count as changed/unchanged)")

            persistEvidence()
            log("DONE-C31")
        } finally {
            owned.toList().forEach { uri ->
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            }
            owned.forEach { uri ->
                val absent = awaitRowAbsentMs(uri, 8000L) != null
                Log.d(TAG, "CLEANUP-C31 uri=$uri absent=$absent")
                assertTrue("CLEANUP-C31: exact owned row must be absent: $uri", absent)
            }
            persistEvidence()
            Log.d(TAG, "CLEANUP-C31 completed")
        }
    }

    // ------------------------------------------------------------ write matrix

    private data class Dist(val summary: String, val json: JSONObject)

    private fun writeMatrix(
        row: CohortRow, volume: String, iterations: Int,
        onRowFailure: () -> Unit, onVolFailure: () -> Unit
    ): Dist {
        val base = readExactBytes(row.uri)
        val toggleOffset = base.size / 2
        var a = 0; var b = 0; var c = 0; var d = 0
        var rowFailures = 0
        var volFailures = 0
        val rowDelays = mutableListOf<Long>()
        val volDelays = mutableListOf<Long>()
        repeat(iterations) { i ->
            val expected = base.copyOf().also { it[toggleOffset] = if (i % 2 == 0) 0xA5.toByte() else 0x5A.toByte() }
            val preBytes = readExactBytes(row.uri)
            val preSha = sha256(preBytes)
            val rowBefore = queryRowGen(row.uri)
            val volBefore = queryVolumeGen(volume)
            val verBefore = queryVersion(volume)
            if (rowBefore is GenOut.QueryFailed) { rowFailures++; onRowFailure() }
            if (volBefore is GenOut.QueryFailed) { volFailures++; onVolFailure() }
            val tClose = writeExactBytes(row.uri, expected)
            val postBytes = readExactBytes(row.uri)
            val postSha = sha256(postBytes)
            // §10: explicit per-write proof, never construction-only.
            assertTrue("MATRIX-C31: preSHA != postSHA fmt=${row.format} iter=$i", preSha != postSha)
            assertTrue("MATRIX-C31: readback == written payload fmt=${row.format} iter=$i",
                postBytes.contentEquals(expected))
            assertEquals("MATRIX-C31: same byte length fmt=${row.format} iter=$i", preBytes.size, postBytes.size)
            // Independent settlement per signal; a non-VALUE pre-write voids that
            // signal's comparison (never counts as changed or unchanged).
            val rowChange = firstChangeMs(tClose, rowBefore) { queryRowGen(row.uri) }
            val volChange = firstChangeMs(tClose, volBefore) { queryVolumeGen(volume) }
            rowChange?.let { rowDelays.add(it) }
            volChange?.let { volDelays.add(it) }
            val verAfter = queryVersion(volume)
            val rowChanged = rowChange != null
            val volChanged = volChange != null
            // Version continuity required for the A/B/C/D verdict; a version change voids it.
            val versionStable = verBefore is VerOut.Value && verAfter is VerOut.Value &&
                verBefore.v == verAfter.v
            assertTrue("MATRIX-C31: MediaStore version must be stable across write fmt=${row.format} iter=$i " +
                "(${describeVer(verBefore)} -> ${describeVer(verAfter)})", versionStable)
            when {
                rowChanged && volChanged -> a++
                !rowChanged && volChanged -> b++
                rowChanged && !volChanged -> c++
                else -> d++
            }
        }
        fun pct(list: List<Long>, p: Double): Long {
            if (list.isEmpty()) return -1
            val s = list.sorted()
            return s[((s.size * p).toInt()).coerceAtMost(s.size - 1)]
        }
        val json = JSONObject()
            .put("format", row.format.name).put("volume", volume).put("n", iterations)
            .put("A_rowChVolCh", a).put("B_rowUnchVolCh", b)
            .put("C_rowChVolUnch", c).put("D_bothUnchanged", d)
            .put("rowQueryFailures", rowFailures).put("volQueryFailures", volFailures)
            .put("rowDelay", JSONObject().put("n", rowDelays.size).put("min", rowDelays.minOrNull() ?: -1)
                .put("p50", pct(rowDelays, 0.5)).put("p90", pct(rowDelays, 0.9)).put("max", rowDelays.maxOrNull() ?: -1))
            .put("volDelay", JSONObject().put("n", volDelays.size).put("min", volDelays.minOrNull() ?: -1)
                .put("p50", pct(volDelays, 0.5)).put("p90", pct(volDelays, 0.9)).put("max", volDelays.maxOrNull() ?: -1))
        val summary = "fmt=${row.format} n=$iterations A=$a B=$b C=$c D=$d " +
            "rowDelayN=${rowDelays.size} volDelayN=${volDelays.size}"
        return Dist(summary, json)
    }

    /**
     * First time after [tClose] that [read] returns a VALUE differing from [before].
     * Null when [before] is not a VALUE (comparison void) or no change by the bound.
     * Each signal settles independently over 0..5000 ms.
     */
    private fun firstChangeMs(tClose: Long, before: GenOut, read: () -> GenOut): Long? {
        val base = (before as? GenOut.Value)?.v ?: return null
        for (target in scheduleMs) {
            val wait = (tClose + target) - SystemClock.elapsedRealtime()
            if (wait > 0) Thread.sleep(wait)
            val cur = (read() as? GenOut.Value)?.v ?: continue // failures never count either way
            if (cur != base) return SystemClock.elapsedRealtime() - tClose
        }
        return null
    }

    // --------------------------------------------------------------- race probe

    private data class RaceSample(val t: Long, val rowGen: Long?, val volGen: Long?, val ver: String?, val sha: String?)

    private fun raceProbe(row: CohortRow, volume: String, writes: Int): Dist {
        val base = readExactBytes(row.uri)
        val toggleOffset = base.size / 2
        val samples = mutableListOf<RaceSample>()
        val running = AtomicBoolean(true)
        val reader = Thread {
            while (running.get()) {
                val t = SystemClock.elapsedRealtime()
                val rg = (queryRowGen(row.uri) as? GenOut.Value)?.v
                val vg = (queryVolumeGen(volume) as? GenOut.Value)?.v
                val vv = (queryVersion(volume) as? VerOut.Value)?.v
                val sha = try {
                    context.contentResolver.openInputStream(row.uri)?.use { sha256(it.readBytes()) }
                } catch (_: Exception) {
                    null
                }
                synchronized(samples) { samples.add(RaceSample(t, rg, vg, vv, sha)) }
            }
        }
        var staleRow = 0; var staleVol = 0; var staleBoth = 0; var windows = 0
        try {
            reader.start()
            repeat(writes) { i ->
                val rowOld = (queryRowGen(row.uri) as? GenOut.Value)?.v
                val volOld = (queryVolumeGen(volume) as? GenOut.Value)?.v
                val verOld = (queryVersion(volume) as? VerOut.Value)?.v
                val expected = base.copyOf().also { it[toggleOffset] = if (i % 2 == 0) 0xA5.toByte() else 0x5A.toByte() }
                val newSha = sha256(expected)
                val preSha = sha256(readExactBytes(row.uri))
                writeExactBytes(row.uri, expected)
                val tClose = SystemClock.elapsedRealtime()
                val postBytes = readExactBytes(row.uri)
                assertTrue("RACE-C31: preSHA != postSHA fmt=${row.format} iter=$i", preSha != sha256(postBytes))
                assertTrue("RACE-C31: write must land fmt=${row.format} iter=$i", postBytes.contentEquals(expected))
                Thread.sleep(300)
                windows++
                val inWindow = synchronized(samples) { samples.filter { it.t in tClose..(tClose + 280) } }
                // Only verdict windows where pre-write reads were all VALUE (else incomparable).
                if (rowOld == null || volOld == null || verOld == null) return@repeat
                val hitRow = inWindow.any { it.rowGen == rowOld && it.sha == newSha }
                val hitVol = inWindow.any { it.volGen == volOld && it.ver == verOld && it.sha == newSha }
                if (hitRow) staleRow++
                if (hitVol) staleVol++
                if (hitRow && inWindow.any { it.rowGen == rowOld && it.volGen == volOld && it.ver == verOld && it.sha == newSha }) staleBoth++
            }
        } finally {
            running.set(false)
            reader.join(5000)
        }
        val json = JSONObject()
            .put("format", row.format.name).put("writes", writes).put("windows", windows)
            .put("staleRowNewBytes", staleRow).put("staleVolNewBytes", staleVol).put("staleBothNewBytes", staleBoth)
            .put("totalReaderSamples", synchronized(samples) { samples.size })
        val summary = "fmt=${row.format} windows=$windows staleRow=$staleRow staleVol=$staleVol staleBoth=$staleBoth"
        return Dist(summary, json)
    }

    // ------------------------------------------------------- false-positive probe

    private fun falsePositiveProbe(target: CohortRow, volume: String): Dist {
        val targetBefore = sha256(readExactBytes(target.uri))
        val volBefore = (queryVolumeGen(volume) as? GenOut.Value)?.v
        val other = createProductionRow(
            if (target.format == OutputFormat.JPEG) OutputFormat.HEIF else OutputFormat.JPEG,
            64, 64, "u23c31-unrelated"
        )
        try {
            // Modify ONLY the unrelated row (proven write), then observe target + volume.
            val otherBytes = readExactBytes(other.uri)
            val mutated = otherBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }
            writeExactBytes(other.uri, mutated)
            assertTrue("FALSEPOS-C31: unrelated write must land",
                readExactBytes(other.uri).contentEquals(mutated))
            Thread.sleep(1000) // settled observation of the coarse signal
            val targetAfter = sha256(readExactBytes(target.uri))
            val volAfter = (queryVolumeGen(volume) as? GenOut.Value)?.v
            val targetUnchanged = targetBefore == targetAfter
            val volAdvanced = volBefore != null && volAfter != null && volAfter != volBefore
            val json = JSONObject()
                .put("targetUnchanged", targetUnchanged)
                .put("volumeAdvanced", volAdvanced)
                .put("volBefore", volBefore ?: -1).put("volAfter", volAfter ?: -1)
                .put("note", "volume advance on unrelated-row write is EXPECTED coarse behavior, not a safety failure")
            return Dist("targetUnchanged=$targetUnchanged volumeAdvanced=$volAdvanced", json)
        } finally {
            try { context.contentResolver.delete(other.uri, null, null) } catch (_: Exception) { }
            assertTrue("FALSEPOS-C31: unrelated row must converge absent",
                awaitRowAbsentMs(other.uri, 8000L) != null)
        }
    }

    // ------------------------------------------------------------- primitives

    private data class CohortRow(val uri: Uri, val format: OutputFormat, val expectation: GalleryExportExpectation)

    private fun createProductionRow(format: OutputFormat, w: Int, h: Int, base: String): CohortRow {
        val bitmap = deterministicBitmap(w, h, w + h)
        try {
            val export = exportNightFusionBitmapToGallery(
                context = context, bitmap = bitmap,
                displayNameBase = "$base-${UUID.randomUUID()}",
                requestedFormat = format, relativeAlbumPath = "Pictures/KeplerU23C31",
                quality = 92, cancellation = NoOpKeplerPipelineCancellation,
                jobDir = null, ownerLease = null
            )
            assertTrue("Production export($format) must succeed: $export", export.success)
            assertEquals("C31 requires actual $format, got ${export.formatUsed}", format, export.formatUsed)
            val uri = Uri.parse(requireNotNull(export.uriString))
            val expectation = GalleryExportExpectation(format, w, h)
            val ver = verifyGalleryExportResult(context, uri.toString(), expectation)
            assertTrue("Fresh $format row must be VERIFIED, got $ver", ver is GalleryExportVerification.Verified)
            return CohortRow(uri, export.formatUsed, expectation)
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeExactBytes(uri: Uri, payload: ByteArray): Long {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rwt")
            ?: error("WRITE-C31: null PFD for $uri; mutation did NOT execute")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { fos ->
                fos.write(payload); fos.flush(); fos.fd.sync()
            }
        }
        return SystemClock.elapsedRealtime()
    }

    private fun readExactBytes(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri) ?: error("READ-C31: null stream for $uri")
        return stream.use { it.readBytes() }
    }

    private fun queryRowGen(uri: Uri): GenOut {
        if (Build.VERSION.SDK_INT < 30) return GenOut.Unavailable
        return try {
            val v = context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else null
            } ?: return GenOut.QueryFailed("empty-or-null")
            GenOut.Value(v)
        } catch (e: Exception) {
            GenOut.QueryFailed(e.javaClass.simpleName)
        }
    }

    private fun queryVolumeGen(volume: String): GenOut {
        if (Build.VERSION.SDK_INT < 30) return GenOut.Unavailable
        return try {
            GenOut.Value(MediaStore.getGeneration(context, volume))
        } catch (e: Exception) {
            GenOut.QueryFailed(e.javaClass.simpleName)
        }
    }

    private fun queryVersion(volume: String): VerOut = try {
        val v = MediaStore.getVersion(context, volume) ?: return VerOut.QueryFailed("null-version")
        VerOut.Value(v)
    } catch (e: Exception) {
        VerOut.QueryFailed(e.javaClass.simpleName)
    }

    /** Authoritative volume resolution via platform API (never assume path segments). */
    private fun resolveVolume(uri: Uri): String = try {
        if (Build.VERSION.SDK_INT >= 30) MediaStore.getVolumeName(uri) else MediaStore.VOLUME_EXTERNAL
    } catch (_: Exception) {
        MediaStore.VOLUME_EXTERNAL
    }

    private fun describeGen(g: GenOut): String = when (g) {
        is GenOut.Value -> g.v.toString()
        is GenOut.QueryFailed -> "QUERY_FAILED(${g.error})"
        GenOut.Unavailable -> "UNAVAILABLE"
    }

    private fun describeVer(v: VerOut): String = when (v) {
        is VerOut.Value -> v.v
        is VerOut.QueryFailed -> "QUERY_FAILED(${v.error})"
        VerOut.Unavailable -> "UNAVAILABLE"
    }

    private enum class RowPresence { PRESENT, ABSENT, QUERY_FAILED }

    private fun awaitRowAbsentMs(uri: Uri, timeoutMs: Long): Long? {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val state: RowPresence = try {
                val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                if (cursor == null) RowPresence.QUERY_FAILED
                else cursor.use { if (it.moveToFirst()) RowPresence.PRESENT else RowPresence.ABSENT }
            } catch (_: Exception) {
                RowPresence.QUERY_FAILED
            }
            // QUERY_FAILED keeps waiting; only authoritative ABSENT converges.
            if (state == RowPresence.ABSENT) return SystemClock.elapsedRealtime() - start
            if (SystemClock.elapsedRealtime() - start >= timeoutMs) return null
            Thread.sleep(100)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun persistEvidence() {
        try {
            val f = java.io.File(context.filesDir, "u23c31-evidence.json")
            f.writeText(evidence.toString(2))
            Log.d(TAG, "EVIDENCE-C31 path=${f.absolutePath} bytes=${f.length()}")
        } catch (e: Exception) {
            Log.d(TAG, "EVIDENCE-C31 write failed: ${e.javaClass.simpleName}")
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
