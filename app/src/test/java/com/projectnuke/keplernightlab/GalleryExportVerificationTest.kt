package com.projectnuke.keplernightlab

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class GalleryExportVerificationTest {
    private fun encoded(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 20, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            check(bitmap.compress(format, 100, out))
            bitmap.recycle()
            out.toByteArray()
        }
    }
    private val jpeg = encoded(Bitmap.CompressFormat.JPEG)
    private val png = encoded(Bitmap.CompressFormat.PNG)
    private val heif = byteArrayOf(0, 0, 0, 16, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63, 0, 0, 0, 0)

    private class FakeSource(
        private val bytes: ByteArray,
        private val columns: GalleryMediaColumns,
        bounds: List<Pair<Int, Int>> = listOf(32 to 20),
        private val unavailableStreams: Int = 0,
        private val usePlatformDecode: Boolean = true
    ) : GalleryExportVerificationSource {
        private val queuedBounds = ArrayDeque(bounds)
        private var opens = 0
        override fun query(uri: Uri) = columns
        override fun open(uri: Uri): InputStream? = if (opens++ < unavailableStreams) null else ByteArrayInputStream(bytes)
        override fun decodeBounds(uri: Uri): Pair<Int, Int> = if (queuedBounds.size > 1) queuedBounds.removeFirst() else queuedBounds.first()
        override fun decodeProbe(uri: Uri, sampleSize: Int): Boolean {
            if (!usePlatformDecode) return true
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
            return try { bitmap.width > 0 && bitmap.height > 0 } finally { bitmap.recycle() }
        }
    }

    private fun verify(
        bytes: ByteArray,
        format: OutputFormat,
        mime: String = format.mimeType,
        name: String = "final.${format.extension}",
        bounds: List<Pair<Int, Int>> = listOf(32 to 20),
        unavailableStreams: Int = 0,
        usePlatformDecode: Boolean = true
    ): GalleryExportVerification = verifyGalleryExportResult(
        RuntimeEnvironment.getApplication(),
        "content://test/final",
        GalleryExportExpectation(format, 32, 20),
        FakeSource(bytes, GalleryMediaColumns(mime, name, bytes.size.toLong()), bounds, unavailableStreams, usePlatformDecode),
        retryScheduler = GalleryVerificationRetryScheduler { }
    )

    @Test fun validJpeg_isVerified() = assertTrue(verify(jpeg, OutputFormat.JPEG) is GalleryExportVerification.Verified)
    @Test
    fun ordinaryUriParseFailureRemainsPermanentVerificationFailure() {
        galleryExportUriParseFailureForTest = IllegalArgumentException("bad URI")
        try {
            assertTrue(verify(jpeg, OutputFormat.JPEG) is GalleryExportVerification.PermanentFailure)
        } finally {
            galleryExportUriParseFailureForTest = null
        }
    }

    @Test
    fun fatalUriParseFailurePropagatesInsteadOfPermanentFailure() {
        val fatal = AssertionError("fatal URI parser")
        galleryExportUriParseFailureForTest = fatal
        try {
            try {
                verify(jpeg, OutputFormat.JPEG)
                throw AssertionError("fatal URI parser was swallowed")
            } catch (failure: AssertionError) {
                assertEquals(fatal, failure)
            }
        } finally {
            galleryExportUriParseFailureForTest = null
        }
    }
    @Test fun validPng_isVerified() = assertTrue(verify(png, OutputFormat.PNG) is GalleryExportVerification.Verified)
    @Test fun validHeif_usesInjectableDecoderAndVerifier() = assertTrue(verify(heif, OutputFormat.HEIF, usePlatformDecode = false) is GalleryExportVerification.Verified)
    @Test fun truncatedJpeg_isRejected() = assertTrue(verify(jpeg.dropLast(2).toByteArray(), OutputFormat.JPEG) is GalleryExportVerification.PermanentFailure)
    @Test fun truncatedPng_isRejected() = assertTrue(verify(png.dropLast(12).toByteArray(), OutputFormat.PNG) is GalleryExportVerification.PermanentFailure)
    @Test fun randomLargeNonImage_isRejected() = assertTrue(verify(ByteArray(100_000) { 0x41 }, OutputFormat.JPEG) is GalleryExportVerification.PermanentFailure)
    @Test fun avifBrand_isNotHeif() {
        val avif = heif.clone().also { it[8] = 'a'.code.toByte(); it[9] = 'v'.code.toByte(); it[10] = 'i'.code.toByte(); it[11] = 'f'.code.toByte() }
        assertTrue(verify(avif, OutputFormat.HEIF) is GalleryExportVerification.PermanentFailure)
    }
    @Test fun mimeMismatch_isRejected() = assertTrue(verify(jpeg, OutputFormat.JPEG, mime = "image/png") is GalleryExportVerification.PermanentFailure)
    @Test fun extensionMismatch_isRejected() = assertTrue(verify(jpeg, OutputFormat.JPEG, name = "final.png") is GalleryExportVerification.PermanentFailure)
    @Test fun dimensionMismatch_isRejected() = assertTrue(verify(jpeg, OutputFormat.JPEG, bounds = listOf(20 to 32)) is GalleryExportVerification.PermanentFailure)
    @Test fun unavailableStreamOnce_thenRetriesCompleteVerification() = assertTrue(verify(jpeg, OutputFormat.JPEG, unavailableStreams = 1) is GalleryExportVerification.Verified)
    @Test fun decodeFailureOnce_thenRetriesCompleteVerification() = assertTrue(verify(jpeg, OutputFormat.JPEG, bounds = listOf(0 to 0, 32 to 20)) is GalleryExportVerification.Verified)
    @Test fun verificationCarriesCommittedTruth() {
        val verified = verify(png, OutputFormat.PNG) as GalleryExportVerification.Verified
        assertEquals(OutputFormat.PNG, verified.detectedFormat)
        assertEquals("image/png", verified.mediaStoreMime)
        assertEquals("final.png", verified.displayName)
        assertEquals(32, verified.width)
        assertEquals(png.size.toLong(), verified.size)
    }
    @Test fun compatibilityWrapperDoesNotAcceptBlankUri() {
        assertFalse(verifyGalleryExport(RuntimeEnvironment.getApplication(), ""))
    }
}
