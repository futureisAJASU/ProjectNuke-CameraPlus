package com.projectnuke.keplernightlab

import android.net.Uri
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

@RunWith(RobolectricTestRunner::class)
class GalleryExportVerificationTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte())
    private val heif = byteArrayOf(0, 0, 0, 16, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63, 0, 0, 0, 0)

    private class FakeSource(
        private val bytes: ByteArray,
        private val columns: GalleryMediaColumns,
        bounds: List<Pair<Int, Int>> = listOf(32 to 20),
        private val unavailableStreams: Int = 0
    ) : GalleryExportVerificationSource {
        private val queuedBounds = ArrayDeque(bounds)
        private var opens = 0
        override fun query(uri: Uri) = columns
        override fun open(uri: Uri): InputStream? = if (opens++ < unavailableStreams) null else ByteArrayInputStream(bytes)
        override fun decodeBounds(uri: Uri): Pair<Int, Int> = if (queuedBounds.size > 1) queuedBounds.removeFirst() else queuedBounds.first()
    }

    private fun verify(
        bytes: ByteArray,
        format: OutputFormat,
        mime: String = format.mimeType,
        name: String = "final.${format.extension}",
        bounds: List<Pair<Int, Int>> = listOf(32 to 20),
        unavailableStreams: Int = 0
    ): GalleryExportVerification = verifyGalleryExportResult(
        RuntimeEnvironment.getApplication(),
        "content://test/final",
        GalleryExportExpectation(format, 32, 20),
        FakeSource(bytes, GalleryMediaColumns(mime, name, bytes.size.toLong()), bounds, unavailableStreams),
        retryScheduler = GalleryVerificationRetryScheduler { }
    )

    @Test fun validJpeg_isVerified() = assertTrue(verify(jpeg, OutputFormat.JPEG) is GalleryExportVerification.Verified)
    @Test fun validPng_isVerified() = assertTrue(verify(png, OutputFormat.PNG) is GalleryExportVerification.Verified)
    @Test fun validHeif_usesInjectableDecoderAndVerifier() = assertTrue(verify(heif, OutputFormat.HEIF) is GalleryExportVerification.Verified)
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
