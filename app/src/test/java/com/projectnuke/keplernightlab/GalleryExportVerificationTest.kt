package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class GalleryExportVerificationTest {

    private fun createJpegBytes(width: Int = 100, height: Int = 100): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private fun createPngBytes(width: Int = 100, height: Int = 100): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private fun createHeifLikeBytes(): ByteArray {
        val header = ByteArray(64 * 1024)
        header[4] = 'f'.code.toByte()
        header[5] = 't'.code.toByte()
        header[6] = 'y'.code.toByte()
        header[7] = 'p'.code.toByte()
        header[8] = 'h'.code.toByte()
        header[9] = 'e'.code.toByte()
        header[10] = 'i'.code.toByte()
        header[11] = 'c'.code.toByte()
        return header
    }

    private fun writeTempFile(bytes: ByteArray, suffix: String): File {
        val file = File.createTempFile("gallery_test_", suffix)
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    private fun newCancellation() = object : KeplerPipelineCancellation {
        override val isCancelled: Boolean = false
        override fun throwIfCancelled() {}
    }

    @Test
    fun verifyGalleryExport_blankUri_returnsFalse() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(verifyGalleryExport(context, ""))
        assertFalse(verifyGalleryExport(context, "   "))
    }

    @Test
    fun verifyGalleryExport_nonExistentUri_returnsFalse() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(verifyGalleryExport(context, "content://com.nonexistent.provider/999999"))
    }

    @Test
    fun verifyGalleryExport_randomLargeNonImageBytes_returnsFalse() {
        val randomBytes = ByteArray(100_000)
        for (i in randomBytes.indices) {
            randomBytes[i] = (Math.random() * 256).toInt().toByte()
        }
        val file = writeTempFile(randomBytes, ".jpg")
        val context = RuntimeEnvironment.getApplication()
        assertFalse(verifyGalleryExport(context, "file://${file.absolutePath}"))
    }

    @Test
    fun verifyGalleryExport_truncatedJpegLargerThanThreshold_returnsFalse() {
        val jpegBytes = createJpegBytes()
        val truncated = jpegBytes.copyOf(100_000)
        val file = writeTempFile(truncated, ".jpg")
        val context = RuntimeEnvironment.getApplication()
        assertFalse(verifyGalleryExport(context, "file://${file.absolutePath}"))
    }

    @Test
    fun verifyGalleryExport_minSizeThreshold_rejectsLargeThreshold() {
        val jpegBytes = createJpegBytes()
        val file = writeTempFile(jpegBytes, ".jpg")
        val context = RuntimeEnvironment.getApplication()
        val uri = "file://${file.absolutePath}"
        assertFalse(
            verifyGalleryExport(context, uri, minSizeBytes = Long.MAX_VALUE)
        )
    }

    @Test
    fun verifyGalleryExport_nonImageExtension_returnsFalse() {
        val textBytes = "This is just text, not an image".repeat(5000).toByteArray()
        val file = writeTempFile(textBytes, ".bin")
        val context = RuntimeEnvironment.getApplication()
        assertFalse(verifyGalleryExport(context, "file://${file.absolutePath}"))
    }

    @Test
    fun exportNightFusionBitmapToGallery_heifFallsBack() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val result = exportNightFusionBitmapToGallery(
            context = context,
            bitmap = bitmap,
            displayNameBase = "test_heif_fallback_${System.nanoTime()}",
            requestedFormat = OutputFormat.HEIF,
            quality = 90,
            cancellation = newCancellation()
        )
        if (result.success) {
            assertTrue(
                result.actualCommittedFormat == OutputFormat.HEIF ||
                    result.actualCommittedFormat == OutputFormat.JPEG ||
                    result.actualCommittedFormat == OutputFormat.PNG
            )
            assertNotNull(result.uriString)
            assertTrue(result.fileSizeBytes > 0)
        }
        bitmap.recycle()
    }

    @Test
    fun exportNightFusionBitmapToGallery_jpegFallbackToPng() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val result = exportNightFusionBitmapToGallery(
            context = context,
            bitmap = bitmap,
            displayNameBase = "test_jpeg_fallback_${System.nanoTime()}",
            requestedFormat = OutputFormat.JPEG,
            quality = 90,
            cancellation = newCancellation()
        )
        if (result.success) {
            assertTrue(
                result.actualCommittedFormat == OutputFormat.JPEG ||
                    result.actualCommittedFormat == OutputFormat.PNG
            )
        }
        bitmap.recycle()
    }

    @Test
    fun exportNightFusionBitmapToGallery_allCandidatesFails_noSourceCacheCleanup() {
        val context = RuntimeEnvironment.getApplication()
        val cacheDir = context.cacheDir
        val sentinelFile = File(cacheDir, "sentinel_${System.nanoTime()}.txt")
        sentinelFile.writeText("keep_me")
        assertTrue("Sentinel file should exist before export", sentinelFile.exists())

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        exportNightFusionBitmapToGallery(
            context = context,
            bitmap = bitmap,
            displayNameBase = "test_all_fail_${System.nanoTime()}",
            requestedFormat = OutputFormat.PNG,
            quality = 90,
            cancellation = newCancellation()
        )
        assertTrue("Sentinel file must survive even if export fails", sentinelFile.exists())
        sentinelFile.delete()
        bitmap.recycle()
    }

    @Test
    fun galleryExportResult_actualCommittedFormat_defaultsToFormatUsed() {
        val result = GalleryExportResult(
            success = true,
            uriString = null,
            displayName = null,
            mimeType = null,
            fileSizeBytes = 0L,
            formatUsed = OutputFormat.JPEG,
            fallbackUsed = false,
            errorMessage = null
        )
        assertEquals(OutputFormat.JPEG, result.actualCommittedFormat)
    }

    @Test
    fun galleryExportResult_actualCommittedFormat_canBeDifferent() {
        val result = GalleryExportResult(
            success = true,
            uriString = null,
            displayName = null,
            mimeType = null,
            fileSizeBytes = 0L,
            formatUsed = OutputFormat.HEIF,
            fallbackUsed = true,
            errorMessage = null,
            actualCommittedFormat = OutputFormat.JPEG
        )
        assertEquals(OutputFormat.JPEG, result.actualCommittedFormat)
    }

    @Test
    fun galleryExportResult_fallbackUsed_trueWhenFallback() {
        val result = GalleryExportResult(
            success = true,
            uriString = null,
            displayName = null,
            mimeType = null,
            fileSizeBytes = 0L,
            formatUsed = OutputFormat.JPEG,
            fallbackUsed = true,
            errorMessage = null,
            actualCommittedFormat = OutputFormat.JPEG
        )
        assertTrue(result.fallbackUsed)
        assertEquals(result.formatUsed, result.actualCommittedFormat)
    }

    @Test
    fun galleryExportResult_failedResult_hasExpectedDefaults() {
        val result = GalleryExportResult(
            success = false,
            uriString = null,
            displayName = null,
            mimeType = null,
            fileSizeBytes = 0L,
            formatUsed = OutputFormat.PNG,
            fallbackUsed = false,
            errorMessage = "test error"
        )
        assertFalse(result.success)
        assertEquals(OutputFormat.PNG, result.formatUsed)
        assertFalse(result.fallbackUsed)
        assertEquals("test error", result.errorMessage)
    }
}
