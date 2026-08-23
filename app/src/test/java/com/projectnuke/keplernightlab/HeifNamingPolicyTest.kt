package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.Locale

/**
 * Phase 2: canonical HEIF naming policy.
 *
 * The previous pair (image/heif + .heic) was inconsistent with the device's
 * MIME-to-extension canonicalization: Samsung/Android MediaStore appended .heif
 * to the supplied .heic display name, committing malformed duplicated rows like
 * Kepler_20260824_011409.heic.heif which verification then rejected.
 *
 * Policy under test:
 *  - newly created public rows use the canonical pair image/heif + .heif;
 *  - generated display names are single-extension (nothing for MediaStore to append);
 *  - HEIF verification truth remains payload/container based; legitimate terminal
 *    aliases .heif (canonical) and .heic (legacy) are accepted when payload AND
 *    MIME are valid; duplicated malformed generated names are rejected;
 *  - non-HEIF formats keep their strict single-canonical-extension policy.
 */
@RunWith(RobolectricTestRunner::class)
class HeifNamingPolicyTest {

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

    /** Minimal valid HEIF container: ftyp box with major brand 'heic'. */
    private val heif = byteArrayOf(0, 0, 0, 16, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63, 0, 0, 0, 0)

    private class FakeSource(
        private val bytes: ByteArray,
        private val columns: GalleryMediaColumns
    ) : GalleryExportVerificationSource {
        override fun query(uri: Uri) = columns
        override fun open(uri: Uri): InputStream = ByteArrayInputStream(bytes)
        override fun decodeBounds(uri: Uri): Pair<Int, Int> = 32 to 20
        // The JVM cannot decode HEIF pixels; the injectable probe stands in for
        // the platform decoder exactly as in GalleryExportVerificationTest.
        override fun decodeProbe(uri: Uri, sampleSize: Int): Boolean = true
    }

    private fun verify(
        bytes: ByteArray,
        format: OutputFormat,
        mime: String = format.mimeType,
        name: String
    ): GalleryExportVerification = verifyGalleryExportResult(
        RuntimeEnvironment.getApplication(),
        "content://test/final",
        GalleryExportExpectation(format, 32, 20),
        FakeSource(bytes, GalleryMediaColumns(mime, name, bytes.size.toLong())),
        retryScheduler = GalleryVerificationRetryScheduler { }
    )

    @Test
    fun heifExport_usesMimeConsistentDisplayName() {
        // Canonical pair: the supplied display name extension matches what the
        // platform derives from image/heif, so MediaStore never rewrites it.
        assertEquals("image/heif", OutputFormat.HEIF.mimeType)
        assertEquals("heif", OutputFormat.HEIF.extension)
        val displayNameBase = "Kepler_20260824_011409"
        val suppliedName = "$displayNameBase.${OutputFormat.HEIF.extension}"
        assertEquals("Kepler_20260824_011409.heif", suppliedName)
        assertTrue(suppliedName.endsWith(".heif"))
        assertFalse(suppliedName.endsWith(".heic"))
        // A row committed under the canonical name verifies cleanly.
        assertTrue(verify(heif, OutputFormat.HEIF, name = suppliedName) is GalleryExportVerification.Verified)
    }

    @Test
    fun heifDisplayName_doesNotBecomeDoubleExtension() {
        // Generation contract: base + ONE extension; nothing to canonicalize.
        val base = "Kepler_testjob"
        val generated = "$base.${OutputFormat.HEIF.extension}"
        assertFalse(generated.contains(".heic"))
        assertEquals(1, REGEX_EXTENSION_COUNT.findAll(generated.lowercase(Locale.US)).count())
        // Even if a legacy/platform bug produced the physical S24 evidence shape,
        // verification rejects it instead of accepting a malformed row.
        val malformedFromPhysicalDevice = "Kepler_20260824_011409.heic.heif"
        val result = verify(heif, OutputFormat.HEIF, name = malformedFromPhysicalDevice)
        assertTrue(result is GalleryExportVerification.PermanentFailure)
        assertTrue(
            (result as GalleryExportVerification.PermanentFailure).reason.contains("duplicated")
        )
        // The mirrored stacking order is equally malformed.
        assertTrue(
            verify(heif, OutputFormat.HEIF, name = "$base.heif.heic")
                is GalleryExportVerification.PermanentFailure
        )
    }

    @Test
    fun heifVerifier_acceptsCanonicalHeif() {
        val verified = verify(heif, OutputFormat.HEIF, name = "final.heif")
        assertTrue(verified is GalleryExportVerification.Verified)
        verified as GalleryExportVerification.Verified
        assertEquals(OutputFormat.HEIF, verified.detectedFormat)
        assertEquals("image/heif", verified.mediaStoreMime)
        assertEquals("final.heif", verified.displayName)
    }

    @Test
    fun heifVerifier_acceptsLegacyHeicWhenPayloadAndMimeAreValid() {
        // Legacy terminal alias: real HEIF payload + image/heif MIME + .heic name.
        assertTrue(verify(heif, OutputFormat.HEIF, name = "final.heic") is GalleryExportVerification.Verified)
        // Alias with wrong MIME must still be rejected (no weakening).
        assertTrue(
            verify(heif, OutputFormat.HEIF, mime = "image/jpeg", name = "final.heic")
                is GalleryExportVerification.PermanentFailure
        )
    }

    @Test
    fun heifVerifier_rejectsUnrelatedExtension() {
        assertTrue(
            verify(heif, OutputFormat.HEIF, name = "final.jpg") is GalleryExportVerification.PermanentFailure
        )
        assertTrue(
            verify(heif, OutputFormat.HEIF, name = "final.png") is GalleryExportVerification.PermanentFailure
        )
        assertTrue(
            verify(heif, OutputFormat.HEIF, name = "final") is GalleryExportVerification.PermanentFailure
        )
    }

    @Test
    fun fallbackPerFormatTruthRemainsStrict() {
        // Non-HEIF formats keep exact single canonical extension requirements.
        assertTrue(verify(jpeg, OutputFormat.JPEG, name = "final.jpg") is GalleryExportVerification.Verified)
        assertTrue(
            verify(jpeg, OutputFormat.JPEG, name = "final.png") is GalleryExportVerification.PermanentFailure
        )
        assertTrue(
            verify(jpeg, OutputFormat.JPEG, name = "final.heif") is GalleryExportVerification.PermanentFailure
        )
        assertTrue(verify(png, OutputFormat.PNG, name = "final.png") is GalleryExportVerification.Verified)
        assertTrue(
            verify(png, OutputFormat.PNG, name = "final.heif") is GalleryExportVerification.PermanentFailure
        )
        assertTrue(
            verify(png, OutputFormat.PNG, name = "final.jpg") is GalleryExportVerification.PermanentFailure
        )
        // Payload-signature truth is unchanged: AVIF brands are not accepted as HEIF.
        val avif = heif.clone().also {
            it[8] = 'a'.code.toByte(); it[9] = 'v'.code.toByte(); it[10] = 'i'.code.toByte(); it[11] = 'f'.code.toByte()
        }
        assertTrue(verify(avif, OutputFormat.HEIF, name = "final.heif") is GalleryExportVerification.PermanentFailure)
    }

    private companion object {
        /** Counts file-style extensions in a generated display name. */
        val REGEX_EXTENSION_COUNT = Regex("\\.[a-z0-9]+")
    }
}
