package com.projectnuke.keplernightlab

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.heifwriter.HeifWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

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

data class RawSidecarExportResult(
    val success: Boolean,
    val exportedFiles: List<String>,
    val errorMessage: String?
)

fun exportNightFusionBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    displayNameBase: String,
    requestedFormat: OutputFormat,
    relativeAlbumPath: String = "Pictures/Kepler",
    quality: Int = 92
): GalleryExportResult {
    val attempts = when (requestedFormat) {
        OutputFormat.HEIF -> listOf(OutputFormat.HEIF, OutputFormat.JPEG, OutputFormat.PNG)
        OutputFormat.JPEG -> listOf(OutputFormat.JPEG, OutputFormat.PNG)
        OutputFormat.PNG -> listOf(OutputFormat.PNG)
    }
    val errors = mutableListOf<String>()
    attempts.forEach { format ->
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
        errors += "${format.label}: ${result.errorMessage}"
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

fun verifyGalleryExport(
    context: Context,
    uriString: String,
    minSizeBytes: Long = 50_000L
): Boolean {
    if (uriString.isBlank()) return false
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
    val size = queryMediaSize(context, uri)
    if (size < minSizeBytes) return false
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                true
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize >= minSizeBytes } ?: false
            }
        } ?: false
    }.getOrDefault(false)
}

fun exportRawSidecarsToPublicStorage(
    context: Context,
    jobDir: File,
    displayNameBase: String,
    relativeRawPath: String = "Pictures/Kepler/RAW"
): RawSidecarExportResult {
    val dngFiles = jobDir.listFiles()
        ?.filter { it.isFile && it.extension.equals("dng", ignoreCase = true) }
        ?.sortedBy { it.name }
        .orEmpty()
    if (dngFiles.isEmpty()) {
        return RawSidecarExportResult(false, emptyList(), "No DNG sidecars found")
    }

    val exported = mutableListOf<String>()
    dngFiles.forEachIndexed { index, file ->
        val exportName = "${displayNameBase}_${index.toString().padStart(2, '0')}.dng"
        val result = insertPublicFile(
            context = context,
            displayName = exportName,
            mimeType = "image/x-adobe-dng",
            relativePath = relativeRawPath,
            collectionUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ) { output ->
            FileInputStream(file).use { input -> input.copyTo(output) }
        } ?: insertPublicFile(
            context = context,
            displayName = exportName,
            mimeType = "image/x-adobe-dng",
            relativePath = "Download/Kepler/RAW",
            collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        ) { output ->
            FileInputStream(file).use { input -> input.copyTo(output) }
        }

        if (result == null) {
            return RawSidecarExportResult(
                success = exported.isNotEmpty(),
                exportedFiles = exported,
                errorMessage = "Failed exporting ${file.name}"
            )
        }
        if (result.second < file.length().coerceAtLeast(1L)) {
            return RawSidecarExportResult(
                success = exported.isNotEmpty(),
                exportedFiles = exported,
                errorMessage = "Export verification failed for ${file.name}"
            )
        }
        exported += result.first.toString()
    }

    return RawSidecarExportResult(true, exported, null)
}

fun queryMediaSize(context: Context, uri: Uri): Long {
    context.contentResolver.query(
        uri,
        arrayOf(MediaStore.MediaColumns.SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    return context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
}

fun updateExportMetadata(
    jobDir: File,
    export: GalleryExportResult?,
    verified: Boolean,
    finalOutputFormat: FinalOutputFormat,
    rawSidecarResult: RawSidecarExportResult? = null,
    rawSidecarIgnored: Boolean = false
) {
    val jobFile = File(jobDir, "job.json")
    val job = if (jobFile.exists()) JSONObject(jobFile.readText()) else JSONObject()
    job.put("finalOutputFormatSetting", finalOutputFormat.name)
        .put("exportStatus", when {
            export == null -> "FAILED"
            verified -> "EXPORTED"
            else -> "EXPORT_UNVERIFIED"
        })
        .put("exportVerified", verified)
        .put("exportUri", export?.uriString ?: JSONObject.NULL)
        .put("exportDisplayName", export?.displayName ?: JSONObject.NULL)
        .put("exportMimeType", export?.mimeType ?: JSONObject.NULL)
        .put("exportFormatRequested", requestedOutputFormatForSetting(finalOutputFormat).label)
        .put("exportFormatUsed", export?.formatUsed?.label ?: JSONObject.NULL)
        .put("exportFallbackUsed", export?.fallbackUsed ?: false)
        .put("exportFileSizeBytes", export?.fileSizeBytes ?: 0L)
        .put("rawSidecarRequested", finalOutputFormat.shouldExportRawSidecar)
        .put("rawSidecarExportStatus", when {
            rawSidecarIgnored -> "UNAVAILABLE"
            rawSidecarResult == null && finalOutputFormat.shouldExportRawSidecar -> "SKIPPED"
            rawSidecarResult == null -> "NOT_REQUESTED"
            rawSidecarResult.success -> "EXPORTED"
            else -> "FAILED"
        })
        .put("rawSidecarExportedFiles", JSONArray(rawSidecarResult?.exportedFiles ?: emptyList<String>()))
        .put("rawSidecarError", when {
            rawSidecarIgnored -> "RAW sidecar unavailable for YUV pipeline."
            else -> rawSidecarResult?.errorMessage ?: JSONObject.NULL
        })
        .put("exportedAt", System.currentTimeMillis())
    jobFile.writeText(job.toString(2))
}

fun updateExportFailure(
    jobDir: File,
    error: String,
    finalOutputFormat: FinalOutputFormat,
    rawSidecarIgnored: Boolean = false
) {
    val jobFile = File(jobDir, "job.json")
    val job = if (jobFile.exists()) JSONObject(jobFile.readText()) else JSONObject()
    job.put("finalOutputFormatSetting", finalOutputFormat.name)
        .put("processStatus", "EXPORT_FAILED_KEEPING_CACHE")
        .put("exportStatus", "FAILED")
        .put("exportVerified", false)
        .put("exportError", error)
        .put("rawSidecarRequested", finalOutputFormat.shouldExportRawSidecar)
        .put("rawSidecarExportStatus", if (rawSidecarIgnored) "UNAVAILABLE" else "SKIPPED")
        .put("rawSidecarError", if (rawSidecarIgnored) "RAW sidecar unavailable for YUV pipeline." else JSONObject.NULL)
        .put("cleanupStatus", "SKIPPED")
        .put("exportedAt", System.currentTimeMillis())
    jobFile.writeText(job.toString(2))
}

fun requestedOutputFormatForSetting(finalOutputFormat: FinalOutputFormat): OutputFormat = when {
    finalOutputFormat.shouldExportHeif -> OutputFormat.HEIF
    finalOutputFormat.shouldExportJpeg -> OutputFormat.JPEG
    else -> OutputFormat.PNG
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
    val inserted = insertPublicFile(
        context = context,
        displayName = displayName,
        mimeType = format.mimeType,
        relativePath = relativeAlbumPath,
        collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    ) { output ->
        val ok = when (format) {
            OutputFormat.HEIF -> writeHeifViaTempFile(context, bitmap, quality, output)
            OutputFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            OutputFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        if (!ok) error("${format.label} encode returned false")
    } ?: return GalleryExportResult(
        success = false,
        uriString = null,
        displayName = displayName,
        mimeType = format.mimeType,
        fileSizeBytes = 0L,
        formatUsed = format,
        fallbackUsed = fallbackUsed,
        errorMessage = "MediaStore insert/write failed"
    )

    return GalleryExportResult(
        success = true,
        uriString = inserted.first.toString(),
        displayName = displayName,
        mimeType = format.mimeType,
        fileSizeBytes = inserted.second,
        formatUsed = format,
        fallbackUsed = fallbackUsed,
        errorMessage = null
    )
}

private fun insertPublicFile(
    context: Context,
    displayName: String,
    mimeType: String,
    relativePath: String,
    collectionUri: Uri,
    writer: (OutputStream) -> Unit
): Pair<Uri, Long>? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000L)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    var uri: Uri? = null
    return try {
        uri = resolver.insert(collectionUri, values) ?: return null
        resolver.openOutputStream(uri)?.use(writer) ?: error("openOutputStream returned null")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        uri to queryMediaSize(context, uri)
    } catch (_: Exception) {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        null
    }
}

private fun writeHeifViaTempFile(
    context: Context,
    bitmap: Bitmap,
    quality: Int,
    output: OutputStream
): Boolean {
    val tempFile = File.createTempFile("kepler_export_", ".heic", context.cacheDir)
    var writer: HeifWriter? = null
    return try {
        val createdWriter = HeifWriter.Builder(
            tempFile.absolutePath,
            bitmap.width,
            bitmap.height,
            HeifWriter.INPUT_MODE_BITMAP
        )
            .setQuality(quality)
            .build()
        writer = createdWriter
        createdWriter.start()
        createdWriter.addBitmap(bitmap)
        createdWriter.stop(5_000)
        FileInputStream(tempFile).use { input -> input.copyTo(output) }
        true
    } finally {
        runCatching { writer?.close() }
            .onFailure { Log.w("KeplerGalleryExporter", "Failed to close HEIF writer.", it) }
        if (tempFile.exists() && !tempFile.delete()) {
            Log.w("KeplerGalleryExporter", "Failed to delete temporary HEIF file: ${tempFile.absolutePath}")
        }
    }
}
