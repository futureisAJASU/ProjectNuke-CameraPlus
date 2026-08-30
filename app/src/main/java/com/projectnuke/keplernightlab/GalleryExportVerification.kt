package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CancellationException

/** The only verification decision used for a committed final-image export. */
sealed interface GalleryExportVerification {
    data class Verified(
        val detectedFormat: OutputFormat,
        val mediaStoreMime: String,
        val displayName: String,
        val width: Int,
        val height: Int,
        val size: Long
    ) : GalleryExportVerification

    data class RetryableFailure(
        val reason: String,
        val diagnosticReason: GalleryExportVerificationReason = GalleryExportVerificationReason.UNSPECIFIED
    ) : GalleryExportVerification

    data class PermanentFailure(
        val reason: String,
        val diagnosticReason: GalleryExportVerificationReason = GalleryExportVerificationReason.UNSPECIFIED
    ) : GalleryExportVerification
}

/**
 * Bounded, non-sensitive evidence for why a verification predicate did not pass.
 *
 * This is deliberately separate from [GalleryExportVerification.RetryableFailure.reason]: the
 * latter remains a compatibility/user-facing string, while this enum is safe to persist in
 * diagnostics and cannot contain provider exception messages or local paths.
 */
enum class GalleryExportVerificationReason {
    UNSPECIFIED,
    URI_BLANK,
    URI_PARSE_FAILED,
    MEDIASTORE_QUERY_FAILED,
    ROW_MISSING,
    CONTENT_OPEN_FAILED,
    CONTENT_STREAM_UNAVAILABLE,
    CONTENT_EMPTY,
    MEDIASTORE_SIZE_MISMATCH,
    SIGNATURE_INVALID,
    STREAM_TRUNCATED,
    BOUNDS_DECODE_FAILED,
    BOUNDS_INVALID,
    PIXEL_PROBE_FAILED,
    PIXEL_PROBE_NO_IMAGE,
    FORMAT_MISMATCH,
    MIME_MISMATCH,
    DISPLAY_NAME_INVALID,
    DUPLICATED_HEIF_NAME,
    EXTENSION_MISMATCH,
    DIMENSION_MISMATCH,
    JOURNAL_SETTLEMENT_PENDING,
    JOURNAL_PERSISTENCE_FAILED,
    VERIFICATION_INCOMPLETE
}

@Volatile
internal var galleryExportUriParseFailureForTest: Throwable? = null

internal data class GalleryExportExpectation(
    val format: OutputFormat? = null,
    val width: Int? = null,
    val height: Int? = null
)

internal data class GalleryMediaColumns(
    val mimeType: String?,
    val displayName: String?,
    val size: Long?
)

/** Production seam: tests provide the same operations through an in-memory provider. */
internal interface GalleryExportVerificationSource {
    fun query(uri: Uri): GalleryMediaColumns?
    fun open(uri: Uri): InputStream?
    fun decodeBounds(uri: Uri): Pair<Int, Int>
    /** Decodes a sampled pixel payload after bounds/signature checks. */
    fun decodeProbe(uri: Uri, sampleSize: Int): Boolean = true
}

internal fun interface GalleryVerificationRetryScheduler {
    fun beforeRetry(attempt: Int)
}

private class AndroidGalleryExportVerificationSource(
    private val context: Context
) : GalleryExportVerificationSource {
    override fun query(uri: Uri): GalleryMediaColumns? {
        if (uri.scheme == "file") {
            val file = uri.path?.let { path -> java.io.File(path) } ?: return null
            return GalleryMediaColumns(null, file.name, file.length())
        }
        return context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            GalleryMediaColumns(
                mimeType = cursor.getString(0),
                displayName = cursor.getString(1),
                size = cursor.getLong(2).takeIf { it >= 0L }
            )
        }
    }

    override fun open(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)

    override fun decodeBounds(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        return options.outWidth to options.outHeight
    }

    override fun decodeProbe(uri: Uri, sampleSize: Int): Boolean {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return false
        return try {
            !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
        } finally {
            bitmap.recycle()
        }
    }
}

internal fun verifyGalleryExportResult(
    context: Context,
    uriString: String,
    expectation: GalleryExportExpectation? = null,
    source: GalleryExportVerificationSource = AndroidGalleryExportVerificationSource(context),
    retries: Int = 3,
    retryScheduler: GalleryVerificationRetryScheduler = GalleryVerificationRetryScheduler { attempt ->
        Thread.sleep(100L * attempt.coerceAtLeast(1))
    }
): GalleryExportVerification {
    if (uriString.isBlank()) return GalleryExportVerification.PermanentFailure(
        "Committed URI is blank",
        GalleryExportVerificationReason.URI_BLANK
    )
    val uri = try {
        galleryExportUriParseFailureForTest?.let { failure ->
            galleryExportUriParseFailureForTest = null
            throw failure
        }
        Uri.parse(uriString)
    } catch (fatal: Error) {
        throw fatal
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        return GalleryExportVerification.PermanentFailure(
            "Committed URI is invalid",
            GalleryExportVerificationReason.URI_PARSE_FAILED
        )
    }
    var firstRetryableReason: String? = null
    var lastRetryableDiagnosticReason: GalleryExportVerificationReason? = null
    repeat(retries.coerceAtLeast(1)) { index ->
        val verification = verifyOnce(uri, expectation, source)
        when (verification) {
            is GalleryExportVerification.Verified,
            is GalleryExportVerification.PermanentFailure -> return verification
            is GalleryExportVerification.RetryableFailure -> {
                if (firstRetryableReason == null) {
                    firstRetryableReason = verification.reason
                }
                // Keep the legacy first-failure text, but make typed evidence authoritative for
                // the last predicate that actually failed after all retry attempts.
                lastRetryableDiagnosticReason = verification.diagnosticReason
                if (index + 1 < retries.coerceAtLeast(1)) retryScheduler.beforeRetry(index + 1)
            }
        }
    }
    return GalleryExportVerification.RetryableFailure(
        firstRetryableReason ?: "Verification did not complete",
        lastRetryableDiagnosticReason ?: GalleryExportVerificationReason.VERIFICATION_INCOMPLETE
    )
}

private fun verifyOnce(
    uri: Uri,
    expectation: GalleryExportExpectation?,
    source: GalleryExportVerificationSource
): GalleryExportVerification {
    val columns = try {
        source.query(uri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        return GalleryExportVerification.RetryableFailure(
            "MediaStore query failed: ${error.javaClass.simpleName}",
            GalleryExportVerificationReason.MEDIASTORE_QUERY_FAILED
        )
    } ?: return GalleryExportVerification.RetryableFailure(
        "MediaStore row is unavailable",
        GalleryExportVerificationReason.ROW_MISSING
    )

    val probe = try {
        source.open(uri)?.use(::probeImageStream)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        return GalleryExportVerification.RetryableFailure(
            "Committed content is unreadable: ${error.javaClass.simpleName}",
            GalleryExportVerificationReason.CONTENT_OPEN_FAILED
        )
    } ?: return GalleryExportVerification.RetryableFailure(
        "Committed content stream is unavailable",
        GalleryExportVerificationReason.CONTENT_STREAM_UNAVAILABLE
    )
    if (probe.size <= 0L) return GalleryExportVerification.RetryableFailure(
        "Committed content is empty",
        GalleryExportVerificationReason.CONTENT_EMPTY
    )
    if (columns.size != null && columns.size > 0L && columns.size != probe.size) {
        return GalleryExportVerification.RetryableFailure(
            "MediaStore size ${columns.size} does not match readable size ${probe.size}",
            GalleryExportVerificationReason.MEDIASTORE_SIZE_MISMATCH
        )
    }
    val format = probe.format
        ?: return GalleryExportVerification.PermanentFailure(
            "Unrecognized or unsupported image signature",
            GalleryExportVerificationReason.SIGNATURE_INVALID
        )
    if (!probe.complete) return GalleryExportVerification.PermanentFailure(
        "Truncated or malformed ${format.label} payload",
        GalleryExportVerificationReason.STREAM_TRUNCATED
    )

    val (width, height) = try {
        source.decodeBounds(uri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        return GalleryExportVerification.RetryableFailure(
            "Bounds decode failed: ${error.javaClass.simpleName}",
            GalleryExportVerificationReason.BOUNDS_DECODE_FAILED
        )
    }
    if (width <= 0 || height <= 0) return GalleryExportVerification.RetryableFailure(
        "Decoded bounds are invalid: ${width}x${height}",
        GalleryExportVerificationReason.BOUNDS_INVALID
    )
    val sample = sampledProbeSize(width, height)
    val pixelsDecoded = try {
        source.decodeProbe(uri, sample)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        return GalleryExportVerification.RetryableFailure(
            "Pixel decode probe failed: ${error.javaClass.simpleName}",
            GalleryExportVerificationReason.PIXEL_PROBE_FAILED
        )
    }
    if (!pixelsDecoded) return GalleryExportVerification.RetryableFailure(
        "Pixel decode probe returned no image",
        GalleryExportVerificationReason.PIXEL_PROBE_NO_IMAGE
    )

    val expected = expectation ?: return GalleryExportVerification.Verified(
        format, columns.mimeType.orEmpty(), columns.displayName.orEmpty(), width, height, probe.size
    )
    if (expected.format != null && format != expected.format) {
        return GalleryExportVerification.PermanentFailure(
            "Format mismatch: expected ${expected.format.label}, detected ${format.label}",
            GalleryExportVerificationReason.FORMAT_MISMATCH
        )
    }
    val expectedFormat = expected.format ?: format
    if (!columns.mimeType.equals(expectedFormat.mimeType, ignoreCase = true)) {
        return GalleryExportVerification.PermanentFailure(
            "MediaStore MIME mismatch: expected ${expectedFormat.mimeType}, actual ${columns.mimeType ?: "missing"}",
            GalleryExportVerificationReason.MIME_MISMATCH
        )
    }
    val displayName = columns.displayName
    val nameLower = displayName?.lowercase(Locale.US)
    if (nameLower.isNullOrBlank()) {
        return GalleryExportVerification.PermanentFailure(
            "Display-name extension mismatch: expected ${expectedExtensionLabel(expectedFormat)}, actual ${displayName ?: "missing"}",
            GalleryExportVerificationReason.DISPLAY_NAME_INVALID
        )
    }
    if (isDuplicatedHeifGeneratedName(nameLower)) {
        return GalleryExportVerification.PermanentFailure(
            "Display-name is a duplicated malformed generated name: actual $displayName",
            GalleryExportVerificationReason.DUPLICATED_HEIF_NAME
        )
    }
    if (!acceptsDisplayNameExtension(expectedFormat, nameLower)) {
        return GalleryExportVerification.PermanentFailure(
            "Display-name extension mismatch: expected ${expectedExtensionLabel(expectedFormat)}, actual $displayName",
            GalleryExportVerificationReason.EXTENSION_MISMATCH
        )
    }
    if (expected.width != null && expected.height != null &&
        (width != expected.width || height != expected.height)
    ) {
        return GalleryExportVerification.PermanentFailure(
            "Dimension mismatch: expected ${expected.width}x${expected.height}, decoded ${width}x${height}",
            GalleryExportVerificationReason.DIMENSION_MISMATCH
        )
    }
    return GalleryExportVerification.Verified(
        format, columns.mimeType!!, columns.displayName!!, width, height, probe.size
    )
}

private data class ImageProbe(val format: OutputFormat?, val complete: Boolean, val size: Long)

/**
 * Display-name policy for committed public rows.
 *
 * Newly created rows use the canonical MIME-consistent pair (image/heif + .heif).
 * Verification accepts legitimate terminal HEIF aliases — .heif (canonical) and
 * .heic (legacy) — because HEIF truth is the payload signature/container, never
 * the name.  Duplicated malformed generated names (.heic.heif / .heif.heic, as
 * produced by platform canonicalization stacking an extension on a mismatched
 * supplied name) are rejected: they are evidence of a broken generation path,
 * not of a legitimate legacy row.
 */
private fun isDuplicatedHeifGeneratedName(nameLower: String): Boolean =
    nameLower.endsWith(".heic.heif") || nameLower.endsWith(".heif.heic")

private fun acceptsDisplayNameExtension(format: OutputFormat, nameLower: String): Boolean = when (format) {
    OutputFormat.HEIF -> nameLower.endsWith(".heif") || nameLower.endsWith(".heic")
    else -> nameLower.endsWith(".${format.extension}")
}

private fun expectedExtensionLabel(format: OutputFormat): String = when (format) {
    OutputFormat.HEIF -> ".heif (or legacy .heic)"
    else -> ".${format.extension}"
}

private fun probeImageStream(input: InputStream): ImageProbe {
    // ISO-BMFF compatible brands live in the ftyp box. Keep this bounded but large enough for
    // normal compatible-brand lists; a larger/unreadable declaration fails closed.
    val first = ByteArray(1024)
    var firstCount = 0
    val tail = ByteArray(16)
    var tailCount = 0
    var size = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        if (firstCount < first.size) {
            val copied = minOf(read, first.size - firstCount)
            buffer.copyInto(first, firstCount, 0, copied)
            firstCount += copied
        }
        for (i in 0 until read) {
            tail[(tailCount++) % tail.size] = buffer[i]
        }
        size += read
    }
    if (firstCount < 4) return ImageProbe(null, false, size)
    val format = detectFormat(first, firstCount)
    val orderedTail = ByteArray(minOf(tailCount, tail.size)) { index ->
        tail[(tailCount - minOf(tailCount, tail.size) + index) % tail.size]
    }
    val complete = when (format) {
        OutputFormat.JPEG -> orderedTail.size >= 2 && orderedTail.takeLast(2).toByteArray().contentEquals(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
        OutputFormat.PNG -> orderedTail.size >= 12 && orderedTail.takeLast(12).toByteArray().contentEquals(
            byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte())
        )
        OutputFormat.HEIF -> size >= 16L
        null -> false
    }
    return ImageProbe(format, complete, size)
}

private fun sampledProbeSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > 1024 || height / sample > 1024) sample *= 2
    return sample
}

private fun detectFormat(header: ByteArray, count: Int): OutputFormat? = when {
    count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> OutputFormat.JPEG
    count >= 8 && header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> OutputFormat.PNG
    isSupportedHeifFtyp(header, count) -> OutputFormat.HEIF
    else -> null
}

private fun isSupportedHeifFtyp(header: ByteArray, count: Int): Boolean {
    if (count < 16 || header.copyOfRange(4, 8).toString(Charsets.US_ASCII) != "ftyp") return false
    val boxSize = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
        ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
    if (boxSize < 16 || boxSize > count) return false
    fun brand(offset: Int) = header.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
    val brands = buildSet {
        add(brand(8))
        var offset = 16
        while (offset + 4 <= boxSize) {
            add(brand(offset))
            offset += 4
        }
    }
    if ("avif" in brands || "avis" in brands) return false
    val heifSpecific = setOf("heic", "heix", "hevc", "hevx", "heim", "heis")
    return brands.any { it in heifSpecific } ||
        ("mif1" in brands || "msf1" in brands) && brands.any { it in heifSpecific }
}
