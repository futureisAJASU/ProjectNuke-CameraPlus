package com.projectnuke.keplernightlab

import org.json.JSONObject
import java.io.File

internal fun updateProcessingStage(
    jobDir: File,
    stage: String,
    status: String,
    mutate: (JSONObject) -> Unit = {}
) {
    KeplerJobMetadata.update(jobDir) { current ->
        val previous = current.optString("currentPipelineStage", "CAPTURE_COMPLETE")
        val inProgressStages = setOf(
            "YUV_ALIGNING", "YUV_MERGING", "YUV_DENOISE_SHARPEN", "YUV_EXPORTING",
            "RAW_ALIGNING", "RAW_MERGING", "RAW_EXPORTING",
            "SUPER_RESOLUTION_PROCESSING", "SUPER_RESOLUTION_EXPORTING",
            "SINGLE_FRAME_PROCESSING"
        )
        val allowed = when (stage) {
            "PROCESSING" -> previous in setOf(
                "CAPTURE_COMPLETE", "PROCESSING", "PIPELINE_FAILED", "FAILED", "CANCELLED", "PIPELINE_CANCELLED"
            )
            "PROCESSING_OUTPUT_COMMITTED" -> previous == "PROCESSING"
            "PIPELINE_COMPLETE", "VERIFIED_EXPORT_COMPLETE" ->
                previous == "PROCESSING" || previous == "PROCESSING_OUTPUT_COMMITTED" ||
                    previous == "EXPORTING" || previous.startsWith("YUV_") ||
                    previous == "SINGLE_FRAME_PROCESSING"
            "PIPELINE_FAILED", "PIPELINE_CANCELLED", "FAILED", "CANCELLED" ->
                previous != "PIPELINE_COMPLETE" && previous != "VERIFIED_EXPORT_COMPLETE" &&
                    previous != "COMPLETE"
            in inProgressStages -> previous !in setOf(
                "PIPELINE_COMPLETE", "VERIFIED_EXPORT_COMPLETE", "COMPLETE"
            )
            else -> false
        }
        check(allowed) { "Invalid processing stage transition $previous -> $stage" }
        mutate(current)
        current.put("currentPipelineStage", stage)
            .put("processStatus", status)
    }
}
