package com.projectnuke.keplernightlab

import org.json.JSONObject
import java.io.File

internal fun updateProcessingStage(
    jobDir: File,
    stage: String,
    status: String,
    mutate: (JSONObject) -> Unit = {},
    attempt: ProcessingAttempt? = null
) {
    KeplerJobMetadata.update(jobDir) { current ->
        val previous = current.optString("currentPipelineStage", "CAPTURE_COMPLETE")
        val terminalStages = setOf(
            "PIPELINE_COMPLETE", "PIPELINE_COMPLETE_PARTIAL", "PIPELINE_FAILED",
            "PIPELINE_CANCELLED", "VERIFIED_EXPORT_COMPLETE", "FAILED", "CANCELLED", "COMPLETE"
        )
        val inProgressStages = setOf(
            "YUV_ALIGNING", "YUV_MERGING", "YUV_DENOISE_SHARPEN", "YUV_EXPORTING",
            "RAW_ALIGNING", "RAW_MERGING", "RAW_EXPORTING",
            "SUPER_RESOLUTION_PROCESSING", "SUPER_RESOLUTION_EXPORTING",
            "SINGLE_FRAME_PROCESSING"
        )
        val sameAttempt = attempt?.let {
            current.optString("processingAttemptId") == it.id &&
                it.operationLease?.let { lease -> KeplerJobMetadata.isOperationOwner(jobDir, lease) } == true
        } ?: false
        val stageAttemptId = current.optString("processingStageAttemptId").takeIf { it.isNotBlank() }
        val newAttemptAfterTerminal = sameAttempt && stageAttemptId != attempt?.id
        val allowed = when (stage) {
            "PROCESSING" -> previous in setOf(
                "CAPTURE_COMPLETE", "PROCESSING"
            ) || (previous in terminalStages && newAttemptAfterTerminal)
            "PROCESSING_OUTPUT_COMMITTED" -> previous == "PROCESSING"
            "PIPELINE_COMPLETE", "VERIFIED_EXPORT_COMPLETE" ->
                previous == "PROCESSING" || previous == "PROCESSING_OUTPUT_COMMITTED" ||
                    previous == "EXPORTING" || previous.startsWith("YUV_") ||
                    previous == "SINGLE_FRAME_PROCESSING"
            "PIPELINE_FAILED", "PIPELINE_CANCELLED", "FAILED", "CANCELLED" ->
                previous != "PIPELINE_COMPLETE" && previous != "VERIFIED_EXPORT_COMPLETE" &&
                    previous != "COMPLETE"
            in inProgressStages -> previous !in terminalStages || newAttemptAfterTerminal
            in terminalStages -> previous !in terminalStages
            else -> false
        }
        check(attempt == null || sameAttempt) { "Processing stage attempt is no longer current" }
        check(allowed) { "Invalid processing stage transition $previous -> $stage" }
        mutate(current)
        current.put("currentPipelineStage", stage)
            .put("processStatus", status)
        attempt?.let { current.put("processingStageAttemptId", it.id) }
    }
}
