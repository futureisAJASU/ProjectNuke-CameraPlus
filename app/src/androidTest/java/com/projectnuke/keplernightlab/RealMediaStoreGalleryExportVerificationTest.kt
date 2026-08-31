package com.projectnuke.keplernightlab

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID

/**
 * Screen-independent proof against the device's actual MediaStore provider.
 *
 * This class never launches an activity and only queries/deletes the exact rows it inserts.
 */
@RunWith(AndroidJUnit4::class)
class RealMediaStoreGalleryExportVerificationTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context: Context
        get() = instrumentation.targetContext

    @Test
    fun realMediaStoreJpegExportIsVerifiedAndRecoverable() {
        val bitmap = deterministicBitmap(64, 64)
        val displayName = "r1d-${UUID.randomUUID()}.jpg"
        var uri: Uri? = null
        var journalDir: File? = null
        try {
            val export = writeGalleryBitmap(
                context = context,
                bitmap = bitmap,
                displayName = displayName,
                format = OutputFormat.JPEG,
                relativeAlbumPath = TEST_RELATIVE_PATH,
                quality = 92,
                fallbackUsed = false,
                cancellation = NoOpKeplerPipelineCancellation,
                jobDir = null
            )
            uri = requireNotNull(export.uriString) { "Production writer returned no URI: $export" }
                .let(Uri::parse)
            assertTrue("Production writer must report JPEG success: $export", export.success)

            val row = requireRow(uri)
            val readableBytes = readableByteCount(uri)
            val bounds = decodeBounds(uri)
            val pixelDecoded = decodeSampledPixel(uri, bounds.first, bounds.second)
            val verification = verifyGalleryExportResult(
                context = context,
                uriString = uri.toString(),
                expectation = GalleryExportExpectation(OutputFormat.JPEG, bitmap.width, bitmap.height)
            )
            val verified = verification as? GalleryExportVerification.Verified
            println(
                "R1D_JPEG_EVIDENCE=" +
                    "uri=$uri scheme=${uri.scheme} authority=${uri.authority} " +
                    "exists=true pending=${row.pending} mime=${row.mimeType} " +
                    "displayName=${row.displayName} size=${row.size} readableBytes=$readableBytes " +
                    "expected=${bitmap.width}x${bitmap.height} bounds=${bounds.first}x${bounds.second} " +
                    "pixelDecoded=$pixelDecoded detectedFormat=${verified?.detectedFormat?.name ?: "none"} " +
                    "verified=${verified != null} diagnosticReason=null"
            )

            assertFalse("Committed production row must not remain pending", row.pending)
            assertEquals("image/jpeg", row.mimeType)
            assertEquals(displayName, row.displayName)
            assertTrue("MediaStore SIZE must be positive", row.size > 0L)
            assertEquals(row.size, readableBytes)
            assertEquals(bitmap.width to bitmap.height, bounds)
            assertTrue("Sampled pixel decode must succeed", pixelDecoded)
            assertNotNull("Production verifier must return Verified", verified)
            assertEquals(OutputFormat.JPEG, verified?.detectedFormat)
            assertEquals(bitmap.width, verified?.width)
            assertEquals(bitmap.height, verified?.height)
            assertNull("Verified export must have no diagnostic reason", diagnosticReason(verification))

            journalDir = createInspectionJournal(uri, row, bitmap.width, bitmap.height)
            val inspection = ContextMediaStoreExportRecoveryAccess(context).inspect(
                uri,
                MediaStoreExportJournal.list(journalDir!!).single()
            )
            println(
                "R1D_JPEG_RECOVERY=" +
                    "exists=${inspection.exists} pending=${inspection.pending} " +
                    "verified=${inspection.verified} " +
                    "diagnosticReason=${inspection.verificationDiagnosticReason}"
            )
            assertTrue(inspection.exists)
            assertFalse(inspection.pending)
            assertTrue(inspection.verified)
            assertNull(inspection.verificationDiagnosticReason)
        } finally {
            bitmap.recycle()
            deleteExactRowAndAssertAbsent(uri, "jpeg")
            journalDir?.deleteRecursively()
        }
    }

    @Test
    fun realMediaStoreInvalidPayloadIsRejectedWithTypedReason() {
        val displayName = "r1d-${UUID.randomUUID()}.jpg"
        var uri: Uri? = null
        var journalDir: File? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, TEST_RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = requireNotNull(
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            )
            context.contentResolver.openOutputStream(uri!!).use { output ->
                requireNotNull(output).write(byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x00, 0x7f))
            }
            assertEquals(1, context.contentResolver.update(
                uri!!,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            ))

            val row = requireRow(uri!!)
            val journal = createInspectionJournal(uri!!, row, null, null).let { dir ->
                journalDir = dir
                MediaStoreExportJournal.list(dir).single()
            }
            val inspection = ContextMediaStoreExportRecoveryAccess(context).inspect(uri!!, journal)
            println(
                "R1D_BAD_EVIDENCE=" +
                    "uri=$uri scheme=${uri!!.scheme} authority=${uri!!.authority} " +
                    "exists=${inspection.exists} pending=${inspection.pending} " +
                    "verified=${inspection.verified} diagnosticReason=${inspection.verificationDiagnosticReason}"
            )
            assertTrue(inspection.exists)
            assertFalse(inspection.pending)
            assertFalse(inspection.verified)
            assertEquals(GalleryExportVerificationReason.SIGNATURE_INVALID, inspection.verificationDiagnosticReason)
        } finally {
            deleteExactRowAndAssertAbsent(uri, "invalid")
            journalDir?.deleteRecursively()
        }
    }

    @Test
    fun realMediaStoreHeifExportIsVerifiedWhenNativeWriterIsAvailable() {
        val bitmap = deterministicBitmap(64, 64)
        val displayName = "r1d-${UUID.randomUUID()}.heif"
        var uri: Uri? = null
        var journalDir: File? = null
        try {
            val export = runCatching {
                writeGalleryBitmap(
                    context = context,
                    bitmap = bitmap,
                    displayName = displayName,
                    format = OutputFormat.HEIF,
                    relativeAlbumPath = TEST_RELATIVE_PATH,
                    quality = 90,
                    fallbackUsed = false,
                    cancellation = NoOpKeplerPipelineCancellation,
                    jobDir = null
                )
            }.getOrElse { failure ->
                assumeTrue("Native HEIF writer unavailable: ${failure.javaClass.simpleName}", false)
                return
            }
            uri = export.uriString?.let(Uri::parse)
            if (!export.success || uri == null) {
                assumeTrue("Native HEIF writer unavailable: ${export.errorMessage}", false)
                return
            }
            val row = requireRow(uri!!)
            val verification = verifyGalleryExportResult(
                context,
                uri!!.toString(),
                GalleryExportExpectation(OutputFormat.HEIF, bitmap.width, bitmap.height)
            )
            val verified = verification as? GalleryExportVerification.Verified
            println(
                "R1D_HEIF_EVIDENCE=requested=HEIF actual=${verified?.detectedFormat?.name ?: "unsupported"} " +
                    "uri=$uri mime=${row.mimeType} displayName=${row.displayName} size=${row.size} " +
                    "verified=${verified != null} diagnosticReason=${diagnosticReason(verification)}"
            )
            assertFalse(row.pending)
            assertEquals("image/heif", row.mimeType)
            assertNotNull("Native HEIF export must verify when writer is available", verified)
            assertEquals(OutputFormat.HEIF, verified?.detectedFormat)
            assertNull(diagnosticReason(verification))

            journalDir = createInspectionJournal(uri!!, row, bitmap.width, bitmap.height)
            val inspection = ContextMediaStoreExportRecoveryAccess(context).inspect(
                uri!!,
                MediaStoreExportJournal.list(journalDir!!).single()
            )
            assertTrue(inspection.exists)
            assertFalse(inspection.pending)
            assertTrue(inspection.verified)
            assertNull(inspection.verificationDiagnosticReason)
        } finally {
            bitmap.recycle()
            deleteExactRowAndAssertAbsent(uri, "heif")
            journalDir?.deleteRecursively()
        }
    }

    private fun requireRow(uri: Uri): RowEvidence {
        return context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING
            ),
            null,
            null,
            null
        )?.use { cursor ->
            assertTrue("Exact created MediaStore row must exist", cursor.moveToFirst())
            RowEvidence(
                mimeType = cursor.getString(0),
                displayName = cursor.getString(1),
                size = cursor.getLong(2),
                pending = cursor.getInt(3) != 0
            )
        } ?: error("Exact created MediaStore URI returned no cursor: $uri")
    }

    private fun readableByteCount(uri: Uri): Long =
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(4096)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                total += count
            }
            total
        }

    private fun decodeBounds(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        return options.outWidth to options.outHeight
    }

    private fun decodeSampledPixel(uri: Uri, width: Int, height: Int): Boolean {
        val options = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, maxOf(width, height) / 1024)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return false
        return try {
            !decoded.isRecycled && decoded.width > 0 && decoded.height > 0
        } finally {
            decoded.recycle()
        }
    }

    private fun createInspectionJournal(
        uri: Uri,
        row: RowEvidence,
        width: Int?,
        height: Int?
    ): File {
        val dir = File(context.cacheDir, "r1d-journal-${UUID.randomUUID()}")
        assertTrue("Unable to create private inspection journal directory", dir.mkdirs())
        val created = MediaStoreExportJournal.create(
            jobDir = dir,
            role = MediaStoreExportRole.MAIN_IMAGE,
            frameIndex = null,
            displayName = row.displayName,
            relativePath = TEST_RELATIVE_PATH,
            mimeType = row.mimeType,
            collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            expectedSizeBytes = row.size,
            expectedWidth = width,
            expectedHeight = height
        )
        created.transition(dir, MediaStoreExportState.PUBLIC_COMMITTED, uri.toString())
        return dir
    }

    private fun deleteExactRowAndAssertAbsent(uri: Uri?, label: String) {
        if (uri == null) return
        runCatching { context.contentResolver.delete(uri, null, null) }
        val exists = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
        println("R1D_CLEANUP=$label uri=$uri existsAfterDelete=$exists")
        assertFalse("Test-created $label row must be absent after cleanup", exists)
    }

    private fun diagnosticReason(result: GalleryExportVerification): GalleryExportVerificationReason? =
        when (result) {
            is GalleryExportVerification.Verified -> null
            is GalleryExportVerification.RetryableFailure -> result.diagnosticReason
            is GalleryExportVerification.PermanentFailure -> result.diagnosticReason
        }

    private fun deterministicBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until height) {
                for (x in 0 until width) {
                    bitmap.setPixel(
                        x,
                        y,
                        android.graphics.Color.argb(255, (x * 4) % 256, (y * 4) % 256, ((x + y) * 2) % 256)
                    )
                }
            }
        }

    private data class RowEvidence(
        val mimeType: String,
        val displayName: String,
        val size: Long,
        val pending: Boolean
    )

    private companion object {
        const val TEST_RELATIVE_PATH = "Pictures/KeplerR1DeviceProof"
    }
}
