package com.projectnuke.keplernightlab

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U2.3-C2 — CORRECTIVE device characterization (SM-S921N, Android 17 / API 37).
 *
 * Corrects every evidence defect of the first U2.3-C run:
 * - every content mutation uses a non-null [android.os.ParcelFileDescriptor] ("rwt") handle,
 *   full-payload write, flush, fsync, close-before-query; a null handle FAILS the test;
 * - every mutation proves content before/after (length, SHA-256, head/tail bytes) by reading
 *   the exact URI back; same-size requires length-equal + SHA-different + corrupt bytes present;
 * - same-size payloads are deterministic signature kills (JPEG SOI, HEIF ftyp) at fixed offsets;
 * - different-size payloads assert length difference BEFORE the write and exact byte equality
 *   AFTER readback;
 * - provider signals are sampled on a settled schedule (0/+100/+500/+1000 ms), final state rules;
 * - cohort is production-faithful JPEG + native HEIF, both created through
 *   [exportNightFusionBitmapToGallery] (never Bitmap.CompressFormat.HEIF);
 * - substage verifier timing (query/stream/bounds/pixel/total) for BOTH formats, >=10 samples;
 * - deletion requires bounded convergence (row actually absent), not just delete() == 1.
 *
 * UI-independent: ContentResolver only. No Camera, no Gallery, no UiAutomator.
 */
@RunWith(AndroidJUnit4::class)
class U23MediaStoreCharacterizationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val TAG = "U23C2"
    private val evidence = JSONObject()

    @Test
    fun characterizeMediaStoreSignalsC2() {
        val device = JSONObject()
            .put("model", Build.MODEL)
            .put("release", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
        evidence.put("device", device)
        log("START-C2 device=${Build.MODEL} release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        log("PROVIDER-C2 volumeGeneration=${providerGeneration()} volumeVersion=${providerVersion()}")

        val ownedUris = mutableListOf<Uri>()
        try {
            // Cohort: production-faithful JPEG + native HEIF through the production export path.
            val jpeg = createProductionRow(OutputFormat.JPEG, 128, 128, "u23c2-jpeg")
            val heif = createProductionRow(OutputFormat.HEIF, 128, 128, "u23c2-heif")
            ownedUris.add(jpeg.uri)
            ownedUris.add(heif.uri)
            log("COHORT-C2 jpeg=${jpeg.uri} heif=${heif.uri}")

            // Substage timing on valid unchanged rows (10+ samples per format), before any mutation.
            val jpegTiming = measureSubstageTiming(jpeg.uri, GalleryExportExpectation(OutputFormat.JPEG, 128, 128))
            val heifTiming = measureSubstageTiming(heif.uri, GalleryExportExpectation(OutputFormat.HEIF, 128, 128))
            evidence.put("timingJpeg", jpegTiming.json)
            evidence.put("timingHeif", heifTiming.json)
            log("TIMING-C2 jpeg ${jpegTiming.summary}")
            log("TIMING-C2 heif ${heifTiming.summary}")

            evidence.put("jpeg", runFormatMatrix(jpeg, ownedUris))
            evidence.put("heif", runFormatMatrix(heif, ownedUris))

            persistEvidence()
            log("DONE-C2")
        } finally {
            ownedUris.toList().forEach { uri ->
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            }
            // Verify no owned row remains (bounded convergence per row).
            ownedUris.forEach { uri ->
                val absent = awaitRowAbsent(uri, timeoutMs = 5000L)
                Log.d(TAG, "CLEANUP-C2 uri=$uri absent=$absent")
            }
            persistEvidence()
            Log.d(TAG, "CLEANUP-C2 completed")
        }
    }

    // ------------------------------------------------------------------ matrix

    private fun runFormatMatrix(row: CohortRow, ownedUris: MutableList<Uri>): JSONObject {
        val out = JSONObject().put("uri", row.uri.toString()).put("format", row.actualFormat.name)
        log("MATRIX-C2 start format=${row.actualFormat} uri=${row.uri}")

        // A. Unchanged control.
        val snapA0 = querySnapshot(row.uri)
        val verA0 = verifyGalleryExportResult(context, row.uri.toString(), row.expectation)
        Thread.sleep(100)
        val snapA1 = querySnapshot(row.uri)
        val verA1 = verifyGalleryExportResult(context, row.uri.toString(), row.expectation)
        assertTrue("A: fresh production row must verify VERIFIED, got ${describe(verA0)}", verA0 is GalleryExportVerification.Verified)
        assertTrue("A: unchanged row must stay VERIFIED, got ${describe(verA1)}", verA1 is GalleryExportVerification.Verified)
        out.put("unchanged", JSONObject()
            .put("genBefore", snapA0.generationModified).put("genAfter", snapA1.generationModified)
            .put("sizeBefore", snapA0.size).put("sizeAfter", snapA1.size)
            .put("before", describe(verA0)).put("after", describe(verA1)))
        log("UNCHANGED-C2 fmt=${row.actualFormat} gen=${snapA0.generationModified}->${snapA1.generationModified} ver=${describe(verA1)}")

        // B. Metadata-only update (rename preserving a valid extension).
        val snapB0 = querySnapshot(row.uri)
        val renamed = "u23c2-renamed-${UUID.randomUUID().toString().take(8)}.${row.actualFormat.extension}"
        assertEquals(1, context.contentResolver.update(
            row.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, renamed) }, null, null))
        val snapB1 = settled(row.uri).last()
        val verB = verifyGalleryExportResult(context, row.uri.toString(), row.expectation)
        assertTrue("B: renamed row must stay VERIFIED, got ${describe(verB)}", verB is GalleryExportVerification.Verified)
        out.put("metadataRename", JSONObject()
            .put("nameBefore", snapB0.displayName).put("nameAfter", snapB1.displayName)
            .put("genBefore", snapB0.generationModified).put("genAfter", snapB1.generationModified)
            .put("settled", snapsToJson(settled(row.uri))).put("verifier", describe(verB)))
        log("METADATA-C2 fmt=${row.actualFormat} name=${snapB0.displayName}->${snapB1.displayName} gen=${snapB0.generationModified}->${snapB1.generationModified} ver=${describe(verB)}")

        // C. IS_PENDING transition 0 -> 1 -> 0.
        val snapC0 = querySnapshot(row.uri)
        assertEquals(1, context.contentResolver.update(
            row.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null))
        val snapC1 = querySnapshot(row.uri)
        assertEquals(1, context.contentResolver.update(
            row.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
        val settledC = settled(row.uri)
        out.put("pending", JSONObject()
            .put("pending0", snapC0.isPending).put("pending1", snapC1.isPending).put("pending2", settledC.last().isPending)
            .put("gen0", snapC0.generationModified).put("gen1", snapC1.generationModified).put("gen2", settledC.last().generationModified))
        log("PENDING-C2 fmt=${row.actualFormat} pending=${snapC0.isPending}->${snapC1.isPending}->${settledC.last().isPending} gen=${snapC0.generationModified}->${snapC1.generationModified}->${settledC.last().generationModified}")

        // D. Confirmed same-size content corruption (deterministic signature kill).
        out.put("sameSize", runSameSizeCorruption(row))

        // E. Confirmed different-size replacement (valid payload, asserted lengths).
        out.put("differentSize", runDifferentSizeReplacement(row, ownedUris))

        // F. Deletion with bounded convergence.
        val verF = verifyGalleryExportResult(context, row.uri.toString(), row.expectation)
        assertEquals("F: delete() must report exactly 1 row", 1, context.contentResolver.delete(row.uri, null, null))
        val convergedMs = awaitRowAbsentMs(row.uri, timeoutMs = 8000L)
        assertTrue("F: exact test row must converge to absent within 8000ms", convergedMs != null)
        out.put("deletion", JSONObject()
            .put("verifierBeforeDelete", describe(verF)).put("deleteResult", 1).put("convergedMs", convergedMs))
        log("DELETION-C2 fmt=${row.actualFormat} convergedMs=$convergedMs")
        return out
    }

    private fun runSameSizeCorruption(row: CohortRow): JSONObject {
        val beforeBytes = readExactBytes(row.uri)
        val beforeSnap = querySnapshot(row.uri)
        val verBefore = verifyGalleryExportResult(context, row.uri.toString(), row.expectation)
        assertTrue("D: pre-mutation row must be VERIFIED, got ${describe(verBefore)}", verBefore is GalleryExportVerification.Verified)

        val mutated = beforeBytes.copyOf()
        when (row.actualFormat) {
            OutputFormat.JPEG -> {
                require(mutated.size >= 16) { "D: JPEG payload too small to corrupt deterministically" }
                require(mutated[0] == 0xFF.toByte() && mutated[1] == 0xD8.toByte() && mutated[2] == 0xFF.toByte()) {
                    "D: original lacks JPEG SOI signature; cannot build signature-kill counterexample"
                }
                mutated[0] = 0x00; mutated[1] = 0x00; mutated[2] = 0x00
            }
            OutputFormat.HEIF -> {
                require(mutated.size >= 16) { "D: HEIF payload too small to corrupt deterministically" }
                val box = mutated.copyOfRange(4, 8).toString(Charsets.US_ASCII)
                require(box == "ftyp") { "D: original lacks HEIF ftyp box (found '$box'); cannot build counterexample" }
                mutated[4] = 'X'.code.toByte(); mutated[5] = 'X'.code.toByte()
                mutated[6] = 'X'.code.toByte(); mutated[7] = 'X'.code.toByte()
            }
            else -> fail("D: unexpected format ${row.actualFormat}")
        }
        require(mutated.size == beforeBytes.size) { "D: same-size payload must preserve exact length" }

        writeExactBytes(row.uri, mutated)

        // Prove readback.
        val afterBytes = readExactBytes(row.uri)
        assertEquals("D: readback length must equal original length", beforeBytes.size, afterBytes.size)
        assertTrue("D: readback SHA must differ (mutation proof)", sha256(beforeBytes) != sha256(afterBytes))
        assertTrue("D: readback must equal written payload byte-for-byte", afterBytes.contentEquals(mutated))
        when (row.actualFormat) {
            OutputFormat.JPEG -> assertTrue("D: invalid SOI must be present in readback",
                afterBytes[0] == 0x00.toByte() && afterBytes[1] == 0x00.toByte() && afterBytes[2] == 0x00.toByte())
            else -> assertEquals("D: killed ftyp must be present in readback", "XXXX",
                afterBytes.copyOfRange(4, 8).toString(Charsets.US_ASCII))
        }

        // Settled provider signals; the FINAL sample is the result.
        val samples = settled(row.uri)
        val final = samples.last()
        assertEquals("D: row URI/_ID must remain stable", beforeSnap.id, final.id)

        val afterVer = verifyGalleryExportResult(context, row.uri.toString(), row.expectation)
        val out = JSONObject()
            .put("beforeLen", beforeBytes.size).put("afterLen", afterBytes.size)
            .put("beforeSha", sha256(beforeBytes)).put("afterSha", sha256(afterBytes))
            .put("beforeHead", headHex(beforeBytes)).put("afterHead", headHex(afterBytes))
            .put("genBefore", beforeSnap.generationModified)
            .put("settled", snapsToJson(samples))
            .put("genFinal", final.generationModified)
            .put("sizeFinal", final.size)
            .put("verifier", describe(afterVer))
        log("SAMESIZE-C2 fmt=${row.actualFormat} len=${beforeBytes.size}->${afterBytes.size} " +
            "sha=${sha256(beforeBytes).take(12)}->${sha256(afterBytes).take(12)} " +
            "head=${headHex(beforeBytes)}->${headHex(afterBytes)} " +
            "gen=${beforeSnap.generationModified}->${samples.map { it.generationModified }} " +
            "ver=${describe(afterVer)}")
        if (afterVer is GalleryExportVerification.Verified) {
            fail("D VERIFIER CORRECTNESS BUG fmt=${row.actualFormat}: readback proves invalid " +
                "signature (${headHex(afterBytes)}) at unchanged length, yet production verifier " +
                "returned VERIFIED. STOP: investigate verifier before any U2.3 work.")
        }
        assertTrue("D: invalid-signature payload must yield PermanentFailure/SIGNATURE_INVALID, got ${describe(afterVer)}",
            afterVer is GalleryExportVerification.PermanentFailure &&
                afterVer.diagnosticReason == GalleryExportVerificationReason.SIGNATURE_INVALID)
        return out
    }

    private fun runDifferentSizeReplacement(row: CohortRow, ownedUris: MutableList<Uri>): JSONObject {
        val beforeBytes = readExactBytes(row.uri)
        val beforeSnap = querySnapshot(row.uri)
        // Build a valid replacement payload of a DIFFERENT size. For HEIF, harvest bytes from a
        // temporary production HEIF export at different dimensions so the payload stays HEIF-valid.
        val replacement: ByteArray
        val replacementExpectation: GalleryExportExpectation
        if (row.actualFormat == OutputFormat.HEIF) {
            val temp = createProductionRow(OutputFormat.HEIF, 64, 64, "u23c2-tmpheif")
            ownedUris.add(temp.uri)
            try {
                replacement = readExactBytes(temp.uri)
            } finally {
                context.contentResolver.delete(temp.uri, null, null)
                awaitRowAbsent(temp.uri, timeoutMs = 8000L)
                ownedUris.remove(temp.uri)
            }
            replacementExpectation = GalleryExportExpectation(OutputFormat.HEIF, 64, 64)
        } else {
            val bmp = deterministicBitmap(64, 64, 7)
            try {
                val s = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, s)
                replacement = s.toByteArray()
            } finally {
                bmp.recycle()
            }
            replacementExpectation = GalleryExportExpectation(OutputFormat.JPEG, 64, 64)
        }
        var adjusted = replacement
        if (adjusted.size == beforeBytes.size) {
            adjusted = adjusted + ByteArray(64) { 0x41 }
        }
        assertTrue("E: replacement length must differ before writing", adjusted.size != beforeBytes.size)

        writeExactBytes(row.uri, adjusted)
        waitForSize(row.uri, adjusted.size.toLong(), timeoutMs = 5000L)

        val afterBytes = readExactBytes(row.uri)
        assertEquals("E: readback size must equal replacement size", adjusted.size, afterBytes.size)
        assertEquals("E: readback SHA must equal replacement SHA", sha256(adjusted), sha256(afterBytes))
        assertTrue("E: readback size must differ from original", afterBytes.size != beforeBytes.size)

        val samples = settled(row.uri)
        val final = samples.last()
        assertEquals("E: row URI/_ID must remain stable", beforeSnap.id, final.id)
        val afterVer = verifyGalleryExportResult(context, row.uri.toString(), replacementExpectation)
        val out = JSONObject()
            .put("beforeLen", beforeBytes.size).put("replacementLen", adjusted.size).put("afterLen", afterBytes.size)
            .put("beforeSha", sha256(beforeBytes)).put("afterSha", sha256(afterBytes))
            .put("genBefore", beforeSnap.generationModified)
            .put("settled", snapsToJson(samples))
            .put("genFinal", final.generationModified)
            .put("sizeFinal", final.size)
            .put("verifier", describe(afterVer))
        log("DIFFSIZE-C2 fmt=${row.actualFormat} len=${beforeBytes.size}->${adjusted.size}->${afterBytes.size} " +
            "gen=${beforeSnap.generationModified}->${samples.map { it.generationModified }} ver=${describe(afterVer)}")
        return out
    }

    // ------------------------------------------------------------- primitives

    private data class CohortRow(val uri: Uri, val actualFormat: OutputFormat, val expectation: GalleryExportExpectation)

    private fun createProductionRow(format: OutputFormat, w: Int, h: Int, base: String): CohortRow {
        val bitmap = deterministicBitmap(w, h, w + h)
        try {
            val export = exportNightFusionBitmapToGallery(
                context = context,
                bitmap = bitmap,
                displayNameBase = "$base-${UUID.randomUUID()}",
                requestedFormat = format,
                relativeAlbumPath = "Pictures/KeplerU23C2",
                quality = 92,
                cancellation = NoOpKeplerPipelineCancellation,
                jobDir = null,
                ownerLease = null
            )
            assertTrue("Production export($format) must succeed: $export", export.success)
            assertTrue("Production export($format) must be VERIFIED, got ${describe(export.verification)}",
                export.publicCommitState == GalleryExportCommitState.VERIFIED)
            assertEquals("HEIF characterization requires actual HEIF, got ${export.formatUsed}", format, export.formatUsed)
            val uri = Uri.parse(requireNotNull(export.uriString) { "Export URI must be non-null" })
            val expectation = GalleryExportExpectation(format, w, h)
            val ver = verifyGalleryExportResult(context, uri.toString(), expectation)
            assertTrue("Fresh production $format row must verify VERIFIED, got ${describe(ver)}",
                ver is GalleryExportVerification.Verified)
            val snap = querySnapshot(uri)
            log("ROW-C2 fmt=$format uri=$uri size=${snap.size} gen=${snap.generationModified} ver=${describe(ver)}")
            return CohortRow(uri, export.formatUsed, expectation)
        } finally {
            bitmap.recycle()
        }
    }

    /** Supported MediaStore write with mandatory non-null handle, full write, flush, fsync, close. */
    private fun writeExactBytes(uri: Uri, payload: ByteArray) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rwt")
            ?: error("WRITE-C2: openFileDescriptor(rwt) returned null for $uri; mutation did NOT execute")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { fos ->
                fos.write(payload)
                fos.flush()
                fos.fd.sync()
            }
        }
    }

    private fun readExactBytes(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("READ-C2: openInputStream returned null for $uri")
        return stream.use { it.readBytes() }
    }

    private fun waitForSize(uri: Uri, expected: Long, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (querySnapshot(uri).size == expected) return
            Thread.sleep(100)
        }
    }

    /** Settled sampling: immediate, +100ms, +500ms, +1000ms. Final sample rules. */
    private fun settled(uri: Uri): List<Snapshot> {
        val out = mutableListOf(querySnapshot(uri))
        Thread.sleep(100); out.add(querySnapshot(uri))
        Thread.sleep(400); out.add(querySnapshot(uri))
        Thread.sleep(500); out.add(querySnapshot(uri))
        return out
    }

    private fun awaitRowAbsent(uri: Uri, timeoutMs: Long): Boolean = awaitRowAbsentMs(uri, timeoutMs) != null

    private fun awaitRowAbsentMs(uri: Uri, timeoutMs: Long): Long? {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + timeoutMs
        while (true) {
            val count = try {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { if (it.moveToFirst()) 1 else 0 } ?: 0
            } catch (_: Exception) {
                0
            }
            if (count == 0) return SystemClock.elapsedRealtime() - start
            if (SystemClock.elapsedRealtime() >= deadline) return null
            Thread.sleep(100)
        }
    }

    // ------------------------------------------------------------- snapshots

    private data class Snapshot(
        val id: Long, val volume: String?, val isPending: Int, val size: Long,
        val dateAdded: Long, val dateModified: Long, val mimeType: String?,
        val displayName: String?, val width: Int, val height: Int,
        val generationAdded: Long?, val generationModified: Long?
    )

    private fun querySnapshot(uri: Uri): Snapshot {
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )
        if (Build.VERSION.SDK_INT >= 30) {
            cols.add(MediaStore.MediaColumns.GENERATION_ADDED)
            cols.add(MediaStore.MediaColumns.GENERATION_MODIFIED)
        }
        val cursor = context.contentResolver.query(uri, cols.toTypedArray(), null, null, null)
            ?: error("SNAP-C2: null cursor for $uri")
        cursor.use {
            assertTrue("SNAP-C2: empty cursor for $uri", it.moveToFirst())
            fun idx(name: String) = it.getColumnIndexOrThrow(name)
            return Snapshot(
                id = it.getLong(idx(MediaStore.MediaColumns._ID)),
                volume = uri.pathSegments.firstOrNull(),
                isPending = it.getInt(idx(MediaStore.MediaColumns.IS_PENDING)),
                size = it.getLong(idx(MediaStore.MediaColumns.SIZE)),
                dateAdded = it.getLong(idx(MediaStore.MediaColumns.DATE_ADDED)),
                dateModified = it.getLong(idx(MediaStore.MediaColumns.DATE_MODIFIED)),
                mimeType = it.getString(idx(MediaStore.MediaColumns.MIME_TYPE)),
                displayName = it.getString(idx(MediaStore.MediaColumns.DISPLAY_NAME)),
                width = it.getInt(idx(MediaStore.MediaColumns.WIDTH)),
                height = it.getInt(idx(MediaStore.MediaColumns.HEIGHT)),
                generationAdded = if (Build.VERSION.SDK_INT >= 30) it.getLong(idx(MediaStore.MediaColumns.GENERATION_ADDED)) else null,
                generationModified = if (Build.VERSION.SDK_INT >= 30) it.getLong(idx(MediaStore.MediaColumns.GENERATION_MODIFIED)) else null
            )
        }
    }

    private fun snapsToJson(samples: List<Snapshot>): String =
        samples.map { "${it.generationModified}/${it.size}/${it.dateModified}/${it.isPending}" }.joinToString(" | ")

    private fun providerGeneration(): String = try {
        if (Build.VERSION.SDK_INT >= 30) {
            MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL).toString()
        } else "unsupported-sdk"
    } catch (e: Exception) {
        "error:${e.javaClass.simpleName}"
    }

    private fun providerVersion(): String = try {
        MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL).toString()
    } catch (e: Exception) {
        "error:${e.javaClass.simpleName}"
    }

    // ---------------------------------------------------------------- timing

    private data class Timing(val summary: String, val json: JSONObject)

    private fun measureSubstageTiming(uri: Uri, expectation: GalleryExportExpectation): Timing {
        val n = 12
        val query = mutableListOf<Double>()
        val stream = mutableListOf<Double>()
        val bounds = mutableListOf<Double>()
        val pixel = mutableListOf<Double>()
        val total = mutableListOf<Double>()
        repeat(n) {
            query.add(ms { context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.close() })
            stream.add(ms { context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes() } })
            bounds.add(ms {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            })
            pixel.add(ms {
                val o = BitmapFactory.Options().apply { inSampleSize = 1; inPreferredConfig = Bitmap.Config.RGB_565 }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }?.recycle()
            })
            total.add(ms { verifyGalleryExportResult(context, uri.toString(), expectation) })
        }
        fun stats(v: List<Double>): JSONObject {
            val s = v.sorted()
            val median = if (s.size % 2 == 0) (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 else s[s.size / 2]
            return JSONObject()
                .put("n", s.size).put("median", r3(median)).put("min", r3(s.first()))
                .put("max", r3(s.last())).put("p90", r3(s[((s.size * 0.9).toInt()).coerceAtMost(s.size - 1)]))
        }
        val json = JSONObject()
            .put("query", stats(query)).put("stream", stats(stream)).put("bounds", stats(bounds))
            .put("pixel", stats(pixel)).put("total", stats(total))
            .put("residualNote", "structural/container parsing not separately instrumented; residual = total - (query+stream+bounds+pixel) is unseparated")
        val summary = "n=$n query=${stats(query).getDouble("median")}ms stream=${stats(stream).getDouble("median")}ms " +
            "bounds=${stats(bounds).getDouble("median")}ms pixel=${stats(pixel).getDouble("median")}ms total=${stats(total).getDouble("median")}ms"
        return Timing(summary, json)
    }

    private fun ms(block: () -> Unit): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1e6
    }

    private fun r3(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0

    // ------------------------------------------------------------------ misc

    private fun describe(v: GalleryExportVerification?): String = when (v) {
        is GalleryExportVerification.Verified -> "Verified(fmt=${v.detectedFormat})"
        is GalleryExportVerification.PermanentFailure -> "PermanentFailure(reason=${v.diagnosticReason})"
        is GalleryExportVerification.RetryableFailure -> "RetryableFailure(reason=${v.diagnosticReason})"
        null -> "null"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun headHex(bytes: ByteArray): String =
        bytes.take(16).joinToString("") { "%02X".format(it) }

    private fun persistEvidence() {
        try {
            val f = java.io.File(context.filesDir, "u23c2-evidence.json")
            f.writeText(evidence.toString(2))
            Log.d(TAG, "EVIDENCE-C2 path=${f.absolutePath} bytes=${f.length()}")
        } catch (e: Exception) {
            Log.d(TAG, "EVIDENCE-C2 write failed: ${e.javaClass.simpleName}")
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
