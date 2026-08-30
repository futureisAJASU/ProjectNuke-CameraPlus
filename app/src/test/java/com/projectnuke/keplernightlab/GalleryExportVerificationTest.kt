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
        private val usePlatformDecode: Boolean = true,
        private val queryFailure: Exception? = null,
        private val queryMissing: Boolean = false,
        private val openFailure: Exception? = null,
        private val boundsFailure: Exception? = null,
        private val probeFailure: Exception? = null
    ) : GalleryExportVerificationSource {
        private val queuedBounds = ArrayDeque(bounds)
        private var opens = 0
        override fun query(uri: Uri): GalleryMediaColumns? {
            queryFailure?.let { throw it }
            return columns.takeUnless { queryMissing }
        }
        override fun open(uri: Uri): InputStream? {
            openFailure?.let { throw it }
            return if (opens++ < unavailableStreams) null else ByteArrayInputStream(bytes)
        }
        override fun decodeBounds(uri: Uri): Pair<Int, Int> {
            boundsFailure?.let { throw it }
            return if (queuedBounds.size > 1) queuedBounds.removeFirst() else queuedBounds.first()
        }
        override fun decodeProbe(uri: Uri, sampleSize: Int): Boolean {
            probeFailure?.let { throw it }
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
        usePlatformDecode: Boolean = true,
        queryFailure: Exception? = null,
        queryMissing: Boolean = false,
        openFailure: Exception? = null,
        boundsFailure: Exception? = null,
        probeFailure: Exception? = null
    ): GalleryExportVerification = verifyGalleryExportResult(
        RuntimeEnvironment.getApplication(),
        "content://test/final",
        GalleryExportExpectation(format, 32, 20),
        FakeSource(
            bytes = bytes,
            columns = GalleryMediaColumns(mime, name, bytes.size.toLong()),
            bounds = bounds,
            unavailableStreams = unavailableStreams,
            usePlatformDecode = usePlatformDecode,
            queryFailure = queryFailure,
            queryMissing = queryMissing,
            openFailure = openFailure,
            boundsFailure = boundsFailure,
            probeFailure = probeFailure
        ),
        retryScheduler = GalleryVerificationRetryScheduler { }
    )

    private fun diagnostic(result: GalleryExportVerification): GalleryExportVerificationReason = when (result) {
        is GalleryExportVerification.RetryableFailure -> result.diagnosticReason
        is GalleryExportVerification.PermanentFailure -> result.diagnosticReason
        is GalleryExportVerification.Verified -> error("Expected verification failure")
    }

    @Test fun validJpeg_isVerified() = assertTrue(verify(jpeg, OutputFormat.JPEG) is GalleryExportVerification.Verified)
    @Test
    fun ordinaryUriParseFailureRemainsPermanentVerificationFailure() {
        galleryExportUriParseFailureForTest = IllegalArgumentException("bad URI")
        try {
            val result = verify(jpeg, OutputFormat.JPEG)
            assertTrue(result is GalleryExportVerification.PermanentFailure)
            assertEquals(GalleryExportVerificationReason.URI_PARSE_FAILED, diagnostic(result))
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
    @Test
    fun truncatedJpeg_isRejectedWithStreamDiagnostic() {
        val result = verify(jpeg.dropLast(2).toByteArray(), OutputFormat.JPEG)
        assertTrue(result is GalleryExportVerification.PermanentFailure)
        assertEquals(GalleryExportVerificationReason.STREAM_TRUNCATED, diagnostic(result))
    }

    @Test
    fun truncatedPng_isRejectedWithStreamDiagnostic() {
        val result = verify(png.dropLast(12).toByteArray(), OutputFormat.PNG)
        assertTrue(result is GalleryExportVerification.PermanentFailure)
        assertEquals(GalleryExportVerificationReason.STREAM_TRUNCATED, diagnostic(result))
    }

    @Test
    fun badSignature_isRejectedWithSignatureDiagnostic() {
        val result = verify(ByteArray(32) { 0x41 }, OutputFormat.JPEG)
        assertTrue(result is GalleryExportVerification.PermanentFailure)
        assertEquals(GalleryExportVerificationReason.SIGNATURE_INVALID, diagnostic(result))
    }

    @Test
    fun validPngHeaderWithInvalidBody_isRejectedByPixelProbe() {
        val invalidBody = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte()
        )
        val result = verify(invalidBody, OutputFormat.PNG)
        assertTrue(result is GalleryExportVerification.RetryableFailure)
        assertEquals(GalleryExportVerificationReason.PIXEL_PROBE_FAILED, diagnostic(result))
    }

    @Test
    fun rowMissing_streamUnavailable_emptyAndSizeMismatch_haveDistinctDiagnostics() {
        assertEquals(
            GalleryExportVerificationReason.ROW_MISSING,
            diagnostic(verify(jpeg, OutputFormat.JPEG, queryMissing = true))
        )
        assertEquals(
            GalleryExportVerificationReason.CONTENT_STREAM_UNAVAILABLE,
            diagnostic(verify(jpeg, OutputFormat.JPEG, unavailableStreams = 3))
        )
        assertEquals(
            GalleryExportVerificationReason.CONTENT_EMPTY,
            diagnostic(verify(ByteArray(0), OutputFormat.JPEG, usePlatformDecode = false))
        )
        val mismatch = verifyGalleryExportResult(
            RuntimeEnvironment.getApplication(),
            "content://test/final",
            GalleryExportExpectation(OutputFormat.JPEG, 32, 20),
            FakeSource(
                bytes = jpeg,
                columns = GalleryMediaColumns("image/jpeg", "final.jpg", jpeg.size.toLong() + 1L),
                usePlatformDecode = true
            ),
            retryScheduler = GalleryVerificationRetryScheduler { }
        )
        assertEquals(GalleryExportVerificationReason.MEDIASTORE_SIZE_MISMATCH, diagnostic(mismatch))
    }

    @Test
    fun queryFailure_isClassifiedWithoutExceptionMessageLeakage() {
        val result = verify(
            jpeg,
            OutputFormat.JPEG,
            queryFailure = IllegalStateException("C:\\Users\\private\\secret.jpg")
        )
        assertEquals(GalleryExportVerificationReason.MEDIASTORE_QUERY_FAILED, diagnostic(result))
        assertFalse((result as GalleryExportVerification.RetryableFailure).reason.contains("private"))
    }

    @Test
    fun openBoundsAndPixelFailures_havePredicateSpecificDiagnostics() {
        assertEquals(
            GalleryExportVerificationReason.CONTENT_OPEN_FAILED,
            diagnostic(verify(jpeg, OutputFormat.JPEG, openFailure = IllegalStateException("closed")))
        )
        assertEquals(
            GalleryExportVerificationReason.BOUNDS_DECODE_FAILED,
            diagnostic(verify(jpeg, OutputFormat.JPEG, boundsFailure = IllegalStateException("bad bounds")))
        )
        assertEquals(
            GalleryExportVerificationReason.PIXEL_PROBE_FAILED,
            diagnostic(verify(jpeg, OutputFormat.JPEG, probeFailure = IllegalStateException("bad pixels")))
        )
    }

    @Test fun randomLargeNonImage_isRejected() = assertTrue(verify(ByteArray(100_000) { 0x41 }, OutputFormat.JPEG) is GalleryExportVerification.PermanentFailure)
    @Test fun avifBrand_isNotHeif() {
        val avif = heif.clone().also { it[8] = 'a'.code.toByte(); it[9] = 'v'.code.toByte(); it[10] = 'i'.code.toByte(); it[11] = 'f'.code.toByte() }
        assertTrue(verify(avif, OutputFormat.HEIF) is GalleryExportVerification.PermanentFailure)
    }
    @Test fun mimeMismatch_isRejected() {
        val result = verify(jpeg, OutputFormat.JPEG, mime = "image/png")
        assertTrue(result is GalleryExportVerification.PermanentFailure)
        assertEquals(GalleryExportVerificationReason.MIME_MISMATCH, diagnostic(result))
    }
    @Test fun extensionMismatch_isRejected() {
        val result = verify(jpeg, OutputFormat.JPEG, name = "final.png")
        assertTrue(result is GalleryExportVerification.PermanentFailure)
        assertEquals(GalleryExportVerificationReason.EXTENSION_MISMATCH, diagnostic(result))
    }
    @Test fun dimensionMismatch_isRejected() {
        val result = verify(jpeg, OutputFormat.JPEG, bounds = listOf(20 to 32))
        assertTrue(result is GalleryExportVerification.PermanentFailure)
        assertEquals(GalleryExportVerificationReason.DIMENSION_MISMATCH, diagnostic(result))
    }
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
