package com.projectnuke.keplernightlab

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.heifwriter.HeifWriter
import java.io.File
import java.io.FileInputStream

data class GalleryExportResult(
    val success: Boolean,
    val uriString: String?,
    val displayName: String?,
    val mimeType: String?,
    val fileSizeBytes: Long,
    val formatUsed: OutputFormat,
    val fallbackUsed: Boolean,
    val errorMessage: String?
)

fun exportNightFusionBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    displayNameBase: String,
    requestedFormat: OutputFormat = OutputFormat.HEIF,
    relativeAlbumPath: String = "Pictures/Kepler",
    quality: Int = 92
): GalleryExportResult {
    val errors = mutableListOf<String>()
    val formats = when (requestedFormat) {
        OutputFormat.HEIF -> listOf(OutputFormat.HEIF, OutputFormat.JPEG, OutputFormat.PNG)
        OutputFormat.JPEG -> listOf(OutputFormat.JPEG, OutputFormat.PNG)
        OutputFormat.PNG -> listOf(OutputFormat.PNG)
    }

    formats.forEach { format ->
        val result = writeGalleryBitmap(
            context = context,
            bitmap = bitmap,
            displayName = "$displayNameBase.${format.extension}",
            format = format,
            relativeAlbumPath = relativeAlbumPath,
            quality = quality,
            fallbackUsed = format != requestedFormat
        )
        if (result.success) return result
        errors.add("${format.label}: ${result.errorMessage}")
    }

    return GalleryExportResult(
        success = false,
        uriString = null,
        displayName = null,
        mimeType = null,
        fileSizeBytes = 0L,
        formatUsed = requestedFormat,
        fallbackUsed = false,
        errorMessage = errors.joinToString("; ")
    )
}

private fun writeGalleryBitmap(
    context: Context,
    bitmap: Bitmap,
    displayName: String,
    format: OutputFormat,
    relativeAlbumPath: String,
    quality: Int,
    fallbackUsed: Boolean
): GalleryExportResult {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, relativeAlbumPath)
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    var uri: Uri? = null

    return try {
        uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert returned null")

        resolver.openOutputStream(uri!!)?.use { output ->
            when (format) {
                OutputFormat.HEIF -> writeHeifViaTempFile(context, bitmap, quality, output)
                OutputFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                OutputFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }.also { ok ->
                if (!ok) error("${format.label} encode returned false")
            }
        } ?: error("openOutputStream returned null")

        resolver.update(
            uri!!,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null
        )

        val size = queryMediaSize(context, uri!!)
        GalleryExportResult(
            success = true,
            uriString = uri.toString(),
            displayName = displayName,
            mimeType = format.mimeType,
            fileSizeBytes = size,
            formatUsed = format,
            fallbackUsed = fallbackUsed,
            errorMessage = null
        )
    } catch (e: Exception) {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        GalleryExportResult(
            success = false,
            uriString = null,
            displayName = displayName,
            mimeType = format.mimeType,
            fileSizeBytes = 0L,
            formatUsed = format,
            fallbackUsed = fallbackUsed,
            errorMessage = "${e.javaClass.simpleName}: ${e.message}"
        )
    }
}

private fun writeHeifViaTempFile(
    context: Context,
    bitmap: Bitmap,
    quality: Int,
    output: java.io.OutputStream
): Boolean {
    val tempFile = File.createTempFile("kepler_export_", ".heic", context.cacheDir)
    return try {
        val writer = HeifWriter.Builder(
            tempFile.absolutePath,
            bitmap.width,
            bitmap.height,
            HeifWriter.INPUT_MODE_BITMAP
        )
            .setQuality(quality)
            .build()
        writer.start()
        writer.addBitmap(bitmap)
        writer.stop(5_000)
        writer.close()
        FileInputStream(tempFile).use { input -> input.copyTo(output) }
        true
    } finally {
        tempFile.delete()
    }
}

fun verifyGalleryExport(
    context: Context,
    uriString: String,
    minSizeBytes: Long = 50_000L
): Boolean {
    if (uriString.isBlank()) return false
    val uri = Uri.parse(uriString)
    val size = queryMediaSize(context, uri)
    if (size < minSizeBytes) return false

    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, options)
            options.outWidth > 0 && options.outHeight > 0
        } ?: false
    }.getOrDefault(true)
}

fun queryMediaSize(context: Context, uri: Uri): Long {
    context.contentResolver.query(
        uri,
        arrayOf(MediaStore.Images.Media.SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            return cursor.getLong(0)
        }
    }

    return context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        descriptor.statSize
    } ?: 0L
}
