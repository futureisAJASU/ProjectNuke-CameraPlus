package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val FUSION_DIAG_MAX_DIM = 960

data class FusionPreviewMetrics(
    val sharpness: Double,
    val noiseEstimate: Double,
    val lowGradientRatio: Double,
    val saturatedPixelRatio: Double,
    val underexposedPixelRatio: Double
)

data class FusionQualityDiagnosticPack(
    val metrics: JSONObject,
    val hints: JSONArray
)

fun saveBoundedDiagnosticPreview(
    bitmap: Bitmap,
    file: File,
    maxDimension: Int = FUSION_DIAG_MAX_DIM
): Bitmap {
    val bounded = boundedBitmap(bitmap, maxDimension)
    FileOutputStream(file).use { out ->
        check(bounded.compress(Bitmap.CompressFormat.PNG, 92, out)) {
            "Diagnostic preview compress failed: ${file.name}"
        }
    }
    return bounded
}

fun loadBoundedDiagnosticPreview(file: File, maxDimension: Int = FUSION_DIAG_MAX_DIM): Bitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    )
}

fun writeFusionQualityDiagnostics(
    job: JSONObject,
    jobDir: File,
    prefix: String,
    reference: Bitmap?,
    fused: Bitmap?,
    denoised: Bitmap?,
    finalImage: Bitmap?,
    compareFileName: String,
    cropPrefix: String = "diagnostic_crop"
): FusionQualityDiagnosticPack {
    val refMetrics = reference?.let(::measureFusionPreview)
    val fusedMetrics = fused?.let(::measureFusionPreview)
    val denoisedMetrics = denoised?.let(::measureFusionPreview)
    val finalMetrics = finalImage?.let(::measureFusionPreview)
    val metrics = JSONObject()
        .put("qualityDiagnosticVersion", "fusion_quality_diag_v1")
        .put("referenceSharpness", refMetrics?.sharpness ?: JSONObject.NULL)
        .put("fusedSharpness", fusedMetrics?.sharpness ?: JSONObject.NULL)
        .put("denoisedSharpness", denoisedMetrics?.sharpness ?: JSONObject.NULL)
        .put("finalSharpness", finalMetrics?.sharpness ?: JSONObject.NULL)
        .put("referenceNoiseEstimate", refMetrics?.noiseEstimate ?: JSONObject.NULL)
        .put("fusedNoiseEstimate", fusedMetrics?.noiseEstimate ?: JSONObject.NULL)
        .put("denoisedNoiseEstimate", denoisedMetrics?.noiseEstimate ?: JSONObject.NULL)
        .put("finalNoiseEstimate", finalMetrics?.noiseEstimate ?: JSONObject.NULL)
        .put("referenceLowGradientRegionRatio", refMetrics?.lowGradientRatio ?: JSONObject.NULL)
        .put("fusedLowGradientRegionRatio", fusedMetrics?.lowGradientRatio ?: JSONObject.NULL)
        .put("denoisedLowGradientRegionRatio", denoisedMetrics?.lowGradientRatio ?: JSONObject.NULL)
        .put("finalLowGradientRegionRatio", finalMetrics?.lowGradientRatio ?: JSONObject.NULL)
        .put("finalSaturatedPixelRatio", finalMetrics?.saturatedPixelRatio ?: JSONObject.NULL)
        .put("finalUnderexposedPixelRatio", finalMetrics?.underexposedPixelRatio ?: JSONObject.NULL)
        .put("sharpnessDropReferenceToFused", dropRatio(refMetrics?.sharpness, fusedMetrics?.sharpness))
        .put("sharpnessDropFusedToFinal", dropRatio(fusedMetrics?.sharpness, finalMetrics?.sharpness))
        .put("noiseReductionReferenceToFused", dropRatio(refMetrics?.noiseEstimate, fusedMetrics?.noiseEstimate))
        .put("noiseReductionFusedToFinal", dropRatio(fusedMetrics?.noiseEstimate, finalMetrics?.noiseEstimate))

    val hints = classifyFusionQuality(refMetrics, fusedMetrics, denoisedMetrics, finalMetrics)
    metrics.put("fusionQualityHint", hints.optString(0, "OK"))
        .put("fusionQualityHints", hints)
        .put("referencePreservedPixelRatio", preservedRatio(refMetrics, finalMetrics))
        .put("qualityDiagnosticCompareFile", compareFileName)
        .put("diagnosticCropPrefix", cropPrefix)
    job.putAll(metrics)

    if (reference != null && finalImage != null) {
        saveCompareSheet(reference, finalImage, File(jobDir, compareFileName))
        saveDiagnosticCropSheet(reference, fused ?: finalImage, finalImage, File(jobDir, "${cropPrefix}_sheet.png"))
    }
    return FusionQualityDiagnosticPack(metrics, hints)
}

private fun JSONObject.putAll(other: JSONObject): JSONObject {
    other.keys().forEach { key -> put(key, other.get(key)) }
    return this
}

private fun boundedBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val scale = min(1f, maxDimension.toFloat() / max(bitmap.width, bitmap.height).toFloat())
    if (scale >= 1f) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
    val w = max(1, (bitmap.width * scale).toInt())
    val h = max(1, (bitmap.height * scale).toInt())
    return Bitmap.createScaledBitmap(bitmap, w, h, true)
}

private fun saveCompareSheet(reference: Bitmap, finalImage: Bitmap, file: File) {
    val w = min(reference.width, finalImage.width)
    val h = min(reference.height, finalImage.height)
    val ref = Bitmap.createScaledBitmap(reference, w, h, true)
    val fin = Bitmap.createScaledBitmap(finalImage, w, h, true)
    val sheet = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888)
    try {
        Canvas(sheet).apply {
            drawBitmap(ref, 0f, 0f, null)
            drawBitmap(fin, w.toFloat(), 0f, null)
        }
        savePng(sheet, file)
    } finally {
        ref.recycle()
        fin.recycle()
        sheet.recycle()
    }
}

private fun saveDiagnosticCropSheet(reference: Bitmap, fused: Bitmap, finalImage: Bitmap, file: File) {
    val size = min(220, min(reference.width, reference.height).coerceAtLeast(1))
    val crops = cropRects(reference.width, reference.height, size)
    val sheet = Bitmap.createBitmap(size * 3, size * crops.size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet)
    try {
        crops.forEachIndexed { row, rect ->
            canvas.drawBitmap(reference, rect, Rect(0, row * size, size, (row + 1) * size), null)
            canvas.drawBitmap(fused, scaleRect(rect, fused.width, fused.height, reference.width, reference.height), Rect(size, row * size, size * 2, (row + 1) * size), null)
            canvas.drawBitmap(finalImage, scaleRect(rect, finalImage.width, finalImage.height, reference.width, reference.height), Rect(size * 2, row * size, size * 3, (row + 1) * size), null)
        }
        savePng(sheet, file)
    } finally {
        sheet.recycle()
    }
}

private fun cropRects(width: Int, height: Int, size: Int): List<Rect> {
    fun rect(cx: Int, cy: Int): Rect {
        val left = (cx - size / 2).coerceIn(0, (width - size).coerceAtLeast(0))
        val top = (cy - size / 2).coerceIn(0, (height - size).coerceAtLeast(0))
        return Rect(left, top, left + size.coerceAtMost(width), top + size.coerceAtMost(height))
    }
    return listOf(
        rect(width / 2, height / 2),
        rect(width / 4, height / 4),
        rect(width * 3 / 4, height / 3),
        rect(width / 3, height * 2 / 3)
    )
}

private fun scaleRect(rect: Rect, width: Int, height: Int, baseWidth: Int, baseHeight: Int): Rect {
    val sx = width.toDouble() / baseWidth.toDouble().coerceAtLeast(1.0)
    val sy = height.toDouble() / baseHeight.toDouble().coerceAtLeast(1.0)
    return Rect(
        (rect.left * sx).toInt().coerceIn(0, width - 1),
        (rect.top * sy).toInt().coerceIn(0, height - 1),
        (rect.right * sx).toInt().coerceIn(1, width),
        (rect.bottom * sy).toInt().coerceIn(1, height)
    )
}

private fun measureFusionPreview(bitmap: Bitmap): FusionPreviewMetrics {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    var sharp = 0.0
    var sharpCount = 0
    var noise = 0.0
    var noiseCount = 0
    var lowGrad = 0
    var sat = 0
    var under = 0
    var count = 0
    val step = max(1, pixels.size / 180_000)
    var i = width + 1
    while (i < pixels.size - width - 1) {
        val x = i % width
        if (x > 0 && x < width - 1) {
            val c = luma(pixels[i])
            val left = luma(pixels[i - 1])
            val right = luma(pixels[i + 1])
            val up = luma(pixels[i - width])
            val down = luma(pixels[i + width])
            val grad = (abs(c - left) + abs(c - right) + abs(c - up) + abs(c - down)) / 4.0
            val lap = abs(4.0 * c - left - right - up - down)
            sharp += lap
            sharpCount++
            if (grad < 4.0) {
                lowGrad++
                noise += lap
                noiseCount++
            }
            if (c > 248.0) sat++
            if (c < 8.0) under++
            count++
        }
        i += step
    }
    val denom = max(1, count).toDouble()
    return FusionPreviewMetrics(
        sharpness = sharp / max(1, sharpCount).toDouble() / 255.0,
        noiseEstimate = noise / max(1, noiseCount).toDouble() / 255.0,
        lowGradientRatio = lowGrad / denom,
        saturatedPixelRatio = sat / denom,
        underexposedPixelRatio = under / denom
    )
}

private fun classifyFusionQuality(
    ref: FusionPreviewMetrics?,
    fused: FusionPreviewMetrics?,
    denoised: FusionPreviewMetrics?,
    finalImage: FusionPreviewMetrics?
): JSONArray {
    val hints = JSONArray()
    if (ref != null && ref.sharpness < 0.018) hints.put("SOURCE_FRAME_SOFT")
    if (ref != null && fused != null) {
        val sharpDrop = ratio(ref.sharpness - fused.sharpness, ref.sharpness)
        val noiseDrop = ratio(ref.noiseEstimate - fused.noiseEstimate, ref.noiseEstimate)
        if (sharpDrop > 0.28 && noiseDrop < 0.12) hints.put("ALIGNMENT_SOFTENING_SUSPECTED")
        if (fused.sharpness < ref.sharpness * 0.9 && fused.noiseEstimate >= ref.noiseEstimate * 0.9) hints.put("FUSION_NOT_IMPROVING_REFERENCE")
    }
    if (fused != null && denoised != null) {
        val sharpDrop = ratio(fused.sharpness - denoised.sharpness, fused.sharpness)
        val noiseDrop = ratio(fused.noiseEstimate - denoised.noiseEstimate, fused.noiseEstimate)
        if (sharpDrop > 0.22 && noiseDrop < 0.18) hints.put("DENOISE_OVERSMOOTH_SUSPECTED")
        if (noiseDrop < 0.08) hints.put("DENOISE_TOO_WEAK_SUSPECTED")
    }
    if (fused != null && finalImage != null) {
        val finalDrop = ratio(fused.sharpness - finalImage.sharpness, fused.sharpness)
        if (finalDrop > 0.25) hints.put("PREVIEW_OR_EXPORT_SOFTENING_SUSPECTED")
        if (finalImage.noiseEstimate > fused.noiseEstimate * 0.9) hints.put("SHARPEN_TOO_WEAK_SUSPECTED")
    }
    if (hints.length() == 0) hints.put("OK")
    return hints
}

private fun dropRatio(before: Double?, after: Double?): Any =
    if (before == null || after == null || before <= 0.0) JSONObject.NULL else ratio(before - after, before)

private fun preservedRatio(ref: FusionPreviewMetrics?, finalImage: FusionPreviewMetrics?): Any =
    if (ref == null || finalImage == null || ref.sharpness <= 0.0) {
        JSONObject.NULL
    } else {
        (finalImage.sharpness / ref.sharpness).coerceIn(0.0, 4.0)
    }

private fun ratio(value: Double, base: Double): Double =
    if (base <= 0.0) 0.0 else (value / base).coerceIn(-4.0, 4.0)

private fun luma(color: Int): Double =
    Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114

private fun savePng(bitmap: Bitmap, file: File) {
    FileOutputStream(file).use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)) { "PNG write failed: ${file.name}" }
    }
}
