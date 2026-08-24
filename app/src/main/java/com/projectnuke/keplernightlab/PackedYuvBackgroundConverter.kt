package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream

/**
 * Phase-7 background-side adapter for PACKED_YUV_V1 jobs: converts every
 * durable .yuvpack source into the EXACT input representation the existing
 * Night Fusion pipeline consumes for PNG jobs (frame_NN_color.png), so fusion,
 * export and quality stages run unchanged on mixed-format history.
 *
 * Authority rules:
 *  - Runs ONLY when job.json's [YuvPersistenceStrategy.JOB_KEY] equals
 *    PACKED_YUV_V1 (durable metadata key, never filename guessing).
 *  - Every payload is proven with [PackedYuvFrameStore.verifyFull] BEFORE any
 *    converted artifact is written; a digest failure throws and the job fails
 *    closed (never silently downgraded to partial inputs).
 *  - Idempotent: already-converted frames are verified against their packed
 *    source again but the PNG is rewritten only when absent.
 *
 * Conversion is intentionally placed on the serialized BACKGROUND lane: the
 * whole point of the A/B is that the foreground shutter path skips RGB
 * conversion + PNG compression entirely.
 */
internal object PackedYuvBackgroundConverter {

    internal data class Result(
        val convertedFrames: Int,
        val durationMs: Long
    )

    /** True when this job's durable metadata selects the packed strategy. */
    internal fun isSelected(jobJson: org.json.JSONObject): Boolean =
        YuvPersistenceStrategy.fromNameOrDefault(
            jobJson.optString(YuvPersistenceStrategy.JOB_KEY)
        ) == YuvPersistenceStrategy.PACKED_YUV_V1

    /**
     * Converts all packed sources of one job into PNG fusion inputs and updates
     * the durable manifest entries in place. Throws on any verification or
     * conversion failure (fail-closed).
     */
    fun convertJob(jobDir: File, jobJson: org.json.JSONObject): Result {
        val startedAt = System.currentTimeMillis()
        if (!isSelected(jobJson)) return Result(0, 0)
        val frames = requireNotNull(jobJson.optJSONArray("frames")) {
            "PACKED_YUV_V1 job has no frames manifest: ${jobDir.name}"
        }
        var converted = 0
        for (index in 0 until frames.length()) {
            val frame = frames.optJSONObject(index) ?: continue
            val packedName = frame.optString("packedSourceFilename")
                .ifBlank { frame.optString("filename") }
            if (!packedName.endsWith(".yuvpack")) continue
            val packedFile = File(jobDir, packedName)

            // Fail-closed content truth for EVERY frame, every recovery pass.
            val decoded = PackedYuvFrameStore.verifyFull(packedFile)
            val pngName = yuvFrameFileName(decoded.frameIndex, YuvPersistenceStrategy.PNG)
            val pngFile = File(jobDir, pngName)
            if (!pngFile.exists()) {
                writePng(decoded, decoded.rotationDegrees, pngFile)
                converted++
            }

            frame.put("filename", pngName)
            frame.put("packedSourceFilename", packedName)
        }
        jobJson.put("packedSourcesConverted", true)
        jobJson.put("packedSourcesConvertedCount", converted)
        val durationMs = System.currentTimeMillis() - startedAt
        jobJson.put("unpackConvertMs", durationMs)
        KeplerJobMetadata.write(jobDir, jobJson)
        return Result(converted, durationMs)
    }

    /** Planar YUV420 -> ARGB (BT.601 limited range) -> optional rotation -> PNG. */
    private fun writePng(frame: PackedYuvFrameStore.PackedFrame, rotationDegrees: Int, outFile: File) {
        val width = frame.width
        val height = frame.height
        val y = frame.y
        val u = frame.u
        val v = frame.v
        val chromaWidth = (width + 1) / 2
        val pixels = IntArray(width * height)
        var p = 0
        for (row in 0 until height) {
            val uRow = (row / 2) * chromaWidth
            for (col in 0 until width) {
                val luma = (y[row * width + col].toInt() and 0xFF)
                val chromaIndex = uRow + col / 2
                val cb = (u[chromaIndex].toInt() and 0xFF) - 128
                val cr = (v[chromaIndex].toInt() and 0xFF) - 128
                val yy = luma - 16
                var r = (1_045_973 * yy + 1_645_647 * cr) shr 20
                var g = (1_044_692 * yy - 410_197 * cb - 854_069 * cr) shr 20
                var b = (1_044_823 * yy + 2_118_241 * cb) shr 20
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                pixels[p++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val oriented = rotateIfNeeded(bitmap, rotationDegrees)
        try {
            val temp = File(outFile.parentFile, ".${outFile.name}.${System.nanoTime()}.tmp")
            FileOutputStream(temp).use { out ->
                oriented.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
                out.fd.sync()
            }
            KeplerJobMetadata.atomicReplace(temp, outFile)
        } finally {
            if (oriented !== bitmap) oriented.recycle()
            bitmap.recycle()
        }
    }

    private fun rotateIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return if (rotated.sameAs(bitmap)) bitmap else rotated
    }
}
