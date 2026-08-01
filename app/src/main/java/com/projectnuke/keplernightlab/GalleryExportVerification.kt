package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.InputStream
import java.util.Locale

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

    data class RetryableFailure(val reason: String) : GalleryExportVerification
    data class PermanentFailure(val reason: String) : GalleryExportVerification
}

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
    if (uriString.isBlank()) return GalleryExportVerification.PermanentFailure("Committed URI is blank")
    val uri = runCatching { Uri.parse(uriString) }.getOrElse {
        return GalleryExportVerification.PermanentFailure("Committed URI is invalid: ${it.message}")
    }
    var firstRetryableReason: String? = null
    repeat(retries.coerceAtLeast(1)) { index ->
        val verification = verifyOnce(uri, expectation, source)
        when (verification) {
            is GalleryExportVerification.Verified,
            is GalleryExportVerification.PermanentFailure -> return verification
            is GalleryExportVerification.RetryableFailure -> {
                if (firstRetryableReason == null) firstRetryableReason = verification.reason
                if (index + 1 < retries.coerceAtLeast(1)) retryScheduler.beforeRetry(index + 1)
            }
        }
    }
    return GalleryExportVerification.RetryableFailure(firstRetryableReason ?: "Verification did not complete")
}

private fun verifyOnce(
    uri: Uri,
    expectation: GalleryExportExpectation?,
    source: GalleryExportVerificationSource
): GalleryExportVerification {
    val columns = try {
        source.query(uri)
    } catch (error: Exception) {
        return GalleryExportVerification.RetryableFailure("MediaStore query failed: ${error.javaClass.simpleName}: ${error.message}")
    } ?: return GalleryExportVerification.RetryableFailure("MediaStore row is unavailable")

    val probe = try {
        source.open(uri)?.use(::probeImageStream)
    } catch (error: Exception) {
        return GalleryExportVerification.RetryableFailure("Committed content is unreadable: ${error.javaClass.simpleName}: ${error.message}")
    } ?: return GalleryExportVerification.RetryableFailure("Committed content stream is unavailable")
    if (probe.size <= 0L) return GalleryExportVerification.RetryableFailure("Committed content is empty")
    if (columns.size != null && columns.size > 0L && columns.size != probe.size) {
        return GalleryExportVerification.RetryableFailure(
            "MediaStore size ${columns.size} does not match readable size ${probe.size}"
        )
    }
    val format = probe.format
        ?: return GalleryExportVerification.PermanentFailure("Unrecognized or unsupported image signature")
    if (!probe.complete) return GalleryExportVerification.PermanentFailure("Truncated or malformed ${format.label} payload")

    val (width, height) = try {
        source.decodeBounds(uri)
    } catch (error: Exception) {
        return GalleryExportVerification.RetryableFailure("Bounds decode failed: ${error.javaClass.simpleName}: ${error.message}")
    }
    if (width <= 0 || height <= 0) return GalleryExportVerification.RetryableFailure("Decoded bounds are invalid: ${width}x${height}")

    val expected = expectation ?: return GalleryExportVerification.Verified(
        format, columns.mimeType.orEmpty(), columns.displayName.orEmpty(), width, height, probe.size
    )
    if (expected.format != null && format != expected.format) {
        return GalleryExportVerification.PermanentFailure(
            "Format mismatch: expected ${expected.format.label}, detected ${format.label}"
        )
    }
    val expectedFormat = expected.format ?: format
    if (!columns.mimeType.equals(expectedFormat.mimeType, ignoreCase = true)) {
        return GalleryExportVerification.PermanentFailure(
            "MediaStore MIME mismatch: expected ${expectedFormat.mimeType}, actual ${columns.mimeType ?: "missing"}"
        )
    }
    val expectedExtension = ".${expectedFormat.extension}"
    if (columns.displayName.isNullOrBlank() || !columns.displayName.lowercase(Locale.US).endsWith(expectedExtension)) {
        return GalleryExportVerification.PermanentFailure(
            "Display-name extension mismatch: expected $expectedExtension, actual ${columns.displayName ?: "missing"}"
        )
    }
    if (expected.width != null && expected.height != null &&
        (width != expected.width || height != expected.height)
    ) {
        return GalleryExportVerification.PermanentFailure(
            "Dimension mismatch: expected ${expected.width}x${expected.height}, decoded ${width}x${height}"
        )
    }
    return GalleryExportVerification.Verified(
        format, columns.mimeType!!, columns.displayName!!, width, height, probe.size
    )
}

private data class ImageProbe(val format: OutputFormat?, val complete: Boolean, val size: Long)

private fun probeImageStream(input: InputStream): ImageProbe {
    val first = ByteArray(64)
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

private fun detectFormat(header: ByteArray, count: Int): OutputFormat? = when {
    count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> OutputFormat.JPEG
    count >= 8 && header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> OutputFormat.PNG
    count >= 12 && header.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" &&
        header.copyOfRange(8, 12).toString(Charsets.US_ASCII) in setOf("heic", "heix", "hevc", "heim", "heis") -> OutputFormat.HEIF
    else -> null
}
