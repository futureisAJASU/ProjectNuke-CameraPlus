package com.projectnuke.keplernightlab

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal const val HARDWARE_E2E_SCHEMA_VERSION = 1
internal const val HARDWARE_E2E_REPORT_DIRECTORY = "hardware-e2e"
private const val HARDWARE_E2E_MAX_REPORTS = 12
private const val HARDWARE_E2E_TAG = "KeplerHardwareE2E"

internal enum class HardwareE2EClassification {
    PASS,
    FAIL,
    INCOMPLETE,
    SKIPPED_UNSUPPORTED
}

internal enum class HardwareE2EClassificationReason {
    PASS_SUCCESS,
    FAIL_PIPELINE_TERMINAL,
    FAIL_OUTPUT_NOT_COMMITTED,
    FAIL_LIVE_OPERATION_REMAINS,
    FAIL_STATE_CONTRADICTION,
    FAIL_FRAME_ACCOUNTING,
    FAIL_EXPORT_STATE,
    FAIL_PARTIAL_NOT_ALLOWED,
    INCOMPLETE_JOB_CORRELATION,
    INCOMPLETE_REPORT,
    INCOMPLETE_HARNESS,
    SKIPPED_CAPABILITY
}

internal enum class HardwareE2EJobCorrelation {
    EXACT,
    PROBABLE,
    AMBIGUOUS,
    NONE
}

internal data class HardwareE2ERunScenario(
    val requestedTestScenario: String,
    val selectedPipelineMode: String,
    val captureMode: String,
    val requestedLensSlot: String,
    val requestedResolution: String,
    val frameCountPolicy: String,
    val effectiveRequestedFrames: Int,
    val requestedZoom: Float,
    val requestedOutputFormat: String,
    val requestedCameraId: String? = null,
    val requestedRoute: String? = null,
    val allowPartialCompletion: Boolean = false,
    val requiresExport: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestedTestScenario", requestedTestScenario)
        put("selectedPipelineMode", selectedPipelineMode)
        put("captureMode", captureMode)
        put("requestedLensSlot", requestedLensSlot)
        put("requestedResolution", requestedResolution)
        put("frameCountPolicy", frameCountPolicy)
        put("effectiveRequestedFrames", effectiveRequestedFrames)
        put("requestedZoom", requestedZoom.toDouble())
        put("requestedOutputFormat", requestedOutputFormat)
        requestedCameraId?.let { put("requestedCameraId", it) }
        requestedRoute?.let { put("requestedRoute", it) }
        put("allowPartialCompletion", allowPartialCompletion)
        put("requiresExport", requiresExport)
    }
}

internal data class HardwareE2EEventRecord(
    val checkpoint: String,
    val eventType: String,
    val elapsedMs: Long,
    val wallClockTimestamp: Long,
    val generation: Long,
    val requestedFrames: Int,
    val savedFrames: Int,
    val receivedImages: Int,
    val completedResults: Int,
    val message: String?,
    val terminalKind: String? = null,
    val requiredOutputCommitted: Boolean = false,
    val publicExportCommitted: Boolean = false,
    val verified: Boolean = false,
    val captureResourcesSettled: Boolean = true,
    /** True only for CaptureStageComplete events that proved the durable handoff. */
    val handoffEvidenceComplete: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("checkpoint", checkpoint)
        put("eventType", eventType)
        put("elapsedMs", elapsedMs)
        put("wallClockTimestamp", wallClockTimestamp)
        put("generation", generation)
        put("requestedFrames", requestedFrames)
        put("savedFrames", savedFrames)
        put("receivedImages", receivedImages)
        put("completedResults", completedResults)
        message?.let { put("message", it) }
        terminalKind?.let { put("terminalKind", it) }
        put("requiredOutputCommitted", requiredOutputCommitted)
        put("publicExportCommitted", publicExportCommitted)
        put("verified", verified)
        put("captureResourcesSettled", captureResourcesSettled)
        if (handoffEvidenceComplete) put("handoffEvidenceComplete", true)
    }
}

internal data class HardwareE2EJobSummary(
    val jobDirectory: String,
    val readable: Boolean,
    val jobType: String,
    val captureMode: String,
    val createdAt: Long,
    val status: String,
    val processStatus: String,
    val exportStatus: String,
    val exportVerified: Boolean,
    val requiredOutputFilePresent: Boolean,
    val requestedFrames: Int,
    val attemptedFrames: Int?,
    val savedFrames: Int,
    val receivedImages: Int?,
    val completedResults: Int?,
    val failedCaptures: Int,
    val partialCapture: Boolean,
    val cleanupType: String,
    val cameraId: String,
    val physicalCameraId: String,
    val requestedPhysicalCameraId: String,
    val dngSidecarSaved: Boolean?,
    val dngSidecarSkipReason: String,
    val dngSidecarStatuses: List<String>,
    val frameManifestCount: Int,
    val rawMetadata: Map<String, String>,
    val selectedRoute: String,
    val actualRoute: String,
    val processingTiming: Map<String, Long>,
    val memoryFields: Map<String, String>,
    val activeOperationId: String,
    val activeOperationKind: String,
    val activeRuntimeSessionId: String,
    val terminalOperationId: String,
    val liveOperationRegistered: Boolean,
    val fileNames: List<String>,
    val error: String?,
    val yuvReceivedFrames: Int? = null,
    val yuvPersistedFrames: Int? = null,
    val yuvFailedFrames: Int? = null,
    val yuvDroppedFrames: Int? = null,
    val yuvCompletedResults: Int? = null,
    val yuvFirstWorkerFailureClass: String? = null,
    val yuvFirstWorkerFailureMessage: String? = null,
    val yuvFirstWorkerFailureFrameIndex: Int? = null,
    val yuvFirstWorkerFailureRootCauseClass: String? = null,
    val yuvFirstWorkerFailureRootCauseMessage: String? = null,
    val yuvFirstWorkerFailureStage: String? = null,
    val yuvQueuedWork: Int? = null,
    val yuvInFlightWork: Int? = null,
    val yuvBufferedFrames: Int? = null,
    val yuvReservedAdoptionCount: Int? = null,
    val rawPublicExportAttemptStatus: String? = null,
    val rawPublicExportAttemptError: String? = null,
    val rawPublicExportAttemptAt: Long? = null,
    val exportAttemptedFormats: List<String> = emptyList(),
    val exportCandidateFailureReasons: List<String> = emptyList(),
    val processingArtifactJournalCount: Int = 0,
    val processingArtifactJournalStates: List<String> = emptyList(),
    val processingArtifactJournalFinalNames: List<String> = emptyList(),
    val processingArtifactInvalidJournalCount: Int = 0,
    /**
     * Structured capture-stage timing evidence (Phase 4): parsed from the
     * persisted "captureTiming" projection.  Null when the job has none.
     */
    val captureTiming: HardwareE2ECaptureTiming? = null,
    /** Bounded background-lane stage durations from "backgroundStageTimings". */
    val backgroundStageTimings: Map<String, Long> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("jobDirectory", jobDirectory)
        put("readable", readable)
        put("jobType", jobType)
        put("captureMode", captureMode)
        put("createdAt", createdAt)
        put("status", status)
        put("processStatus", processStatus)
        put("exportStatus", exportStatus)
        put("exportVerified", exportVerified)
        put("requiredOutputFilePresent", requiredOutputFilePresent)
        put("requestedFrames", requestedFrames)
        attemptedFrames?.let { put("attemptedFrames", it) }
        put("savedFrames", savedFrames)
        receivedImages?.let { put("receivedImages", it) }
        completedResults?.let { put("completedResults", it) }
        put("failedCaptures", failedCaptures)
        put("partialCapture", partialCapture)
        put("cleanupType", cleanupType)
        put("cameraId", cameraId)
        put("physicalCameraId", physicalCameraId)
        put("requestedPhysicalCameraId", requestedPhysicalCameraId)
        dngSidecarSaved?.let { put("dngSidecarSaved", it) }
        put("dngSidecarSkipReason", dngSidecarSkipReason)
        put("dngSidecarStatuses", JSONArray(dngSidecarStatuses))
        put("frameManifestCount", frameManifestCount)
        put("rawMetadata", JSONObject(rawMetadata))
        put("selectedRoute", selectedRoute)
        put("actualRoute", actualRoute)
        put("processingTiming", JSONObject(processingTiming))
        put("memoryFields", JSONObject(memoryFields))
        put("activeOperationId", activeOperationId)
        put("activeOperationKind", activeOperationKind)
        put("activeRuntimeSessionId", activeRuntimeSessionId)
        put("terminalOperationId", terminalOperationId)
        put("liveOperationRegistered", liveOperationRegistered)
        put("fileNames", JSONArray(fileNames))
        yuvReceivedFrames?.let { put("yuvReceivedFrames", it) }
        yuvPersistedFrames?.let { put("yuvPersistedFrames", it) }
        yuvFailedFrames?.let { put("yuvFailedFrames", it) }
        yuvDroppedFrames?.let { put("yuvDroppedFrames", it) }
        yuvCompletedResults?.let { put("yuvCompletedResults", it) }
        yuvFirstWorkerFailureClass?.let { put("yuvFirstWorkerFailureClass", it) }
        yuvFirstWorkerFailureMessage?.let { put("yuvFirstWorkerFailureMessage", it) }
        yuvFirstWorkerFailureFrameIndex?.let { put("yuvFirstWorkerFailureFrameIndex", it) }
        yuvFirstWorkerFailureRootCauseClass?.let { put("yuvFirstWorkerFailureRootCauseClass", it) }
        yuvFirstWorkerFailureRootCauseMessage?.let { put("yuvFirstWorkerFailureRootCauseMessage", it) }
        yuvFirstWorkerFailureStage?.let { put("yuvFirstWorkerFailureStage", it) }
        yuvQueuedWork?.let { put("yuvQueuedWork", it) }
        yuvInFlightWork?.let { put("yuvInFlightWork", it) }
        yuvBufferedFrames?.let { put("yuvBufferedFrames", it) }
        yuvReservedAdoptionCount?.let { put("yuvReservedAdoptionCount", it) }
        rawPublicExportAttemptStatus?.let { put("rawPublicExportAttemptStatus", it) }
        rawPublicExportAttemptError?.let { put("rawPublicExportAttemptError", it) }
        rawPublicExportAttemptAt?.let { put("rawPublicExportAttemptAt", it) }
        if (exportAttemptedFormats.isNotEmpty()) {
            put("exportAttemptedFormats", JSONArray(exportAttemptedFormats))
        }
        if (exportCandidateFailureReasons.isNotEmpty()) {
            put("exportCandidateFailureReasons", JSONArray(exportCandidateFailureReasons))
        }
        if (processingArtifactJournalCount > 0) {
            put("processingArtifactJournalCount", processingArtifactJournalCount)
            put("processingArtifactJournalStates", JSONArray(processingArtifactJournalStates))
            put("processingArtifactJournalFinalNames", JSONArray(processingArtifactJournalFinalNames))
        }
        if (processingArtifactInvalidJournalCount > 0) {
            put("processingArtifactInvalidJournalCount", processingArtifactInvalidJournalCount)
        }
        captureTiming?.let { put("captureTiming", it.toJson()) }
        if (backgroundStageTimings.isNotEmpty()) {
            put("backgroundStageTimings", JSONObject(backgroundStageTimings))
        }
        error?.let { put("error", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): HardwareE2EJobSummary = HardwareE2EJobSummary(
            jobDirectory = json.optString("jobDirectory"),
            readable = json.optBoolean("readable"),
            jobType = json.optString("jobType"),
            captureMode = json.optString("captureMode"),
            createdAt = json.optLong("createdAt"),
            status = json.optString("status"),
            processStatus = json.optString("processStatus"),
            exportStatus = json.optString("exportStatus"),
            exportVerified = json.optBoolean("exportVerified"),
            requiredOutputFilePresent = json.optBoolean("requiredOutputFilePresent"),
            requestedFrames = json.optInt("requestedFrames"),
            attemptedFrames = json.optNullableInt("attemptedFrames"),
            savedFrames = json.optInt("savedFrames"),
            receivedImages = json.optNullableInt("receivedImages"),
            completedResults = json.optNullableInt("completedResults"),
            failedCaptures = json.optInt("failedCaptures"),
            partialCapture = json.optBoolean("partialCapture"),
            cleanupType = json.optString("cleanupType"),
            cameraId = json.optString("cameraId"),
            physicalCameraId = json.optString("physicalCameraId"),
            requestedPhysicalCameraId = json.optString("requestedPhysicalCameraId"),
            dngSidecarSaved = json.optNullableBoolean("dngSidecarSaved"),
            dngSidecarSkipReason = json.optString("dngSidecarSkipReason"),
            dngSidecarStatuses = json.optJSONArray("dngSidecarStatuses").toStringList(),
            frameManifestCount = json.optInt("frameManifestCount"),
            rawMetadata = json.optJSONObject("rawMetadata").toStringMap(),
            selectedRoute = json.optString("selectedRoute"),
            actualRoute = json.optString("actualRoute"),
            processingTiming = json.optJSONObject("processingTiming").toLongMap(),
            memoryFields = json.optJSONObject("memoryFields").toStringMap(),
            activeOperationId = json.optString(ACTIVE_OPERATION_ID),
            activeOperationKind = json.optString(ACTIVE_OPERATION_KIND),
            activeRuntimeSessionId = json.optString(ACTIVE_RUNTIME_SESSION_ID),
            terminalOperationId = json.optString(TERMINAL_OPERATION_ID),
            liveOperationRegistered = json.optBoolean("liveOperationRegistered"),
            fileNames = json.optJSONArray("fileNames").toStringList(),
            error = json.optString("error").takeIf { it.isNotBlank() },
            yuvReceivedFrames = json.optNullableInt("yuvReceivedFrames"),
            yuvPersistedFrames = json.optNullableInt("yuvPersistedFrames"),
            yuvFailedFrames = json.optNullableInt("yuvFailedFrames"),
            yuvDroppedFrames = json.optNullableInt("yuvDroppedFrames"),
            yuvCompletedResults = json.optNullableInt("yuvCompletedResults"),
            yuvFirstWorkerFailureClass = json.optNonBlankString("yuvFirstWorkerFailureClass"),
            yuvFirstWorkerFailureMessage = json.optNonBlankString("yuvFirstWorkerFailureMessage"),
            yuvFirstWorkerFailureFrameIndex = json.optNullableInt("yuvFirstWorkerFailureFrameIndex"),
            yuvFirstWorkerFailureRootCauseClass = json.optNonBlankString("yuvFirstWorkerFailureRootCauseClass"),
            yuvFirstWorkerFailureRootCauseMessage = json.optNonBlankString("yuvFirstWorkerFailureRootCauseMessage"),
            yuvFirstWorkerFailureStage = json.optNonBlankString("yuvFirstWorkerFailureStage"),
            yuvQueuedWork = json.optNullableInt("yuvQueuedWork"),
            yuvInFlightWork = json.optNullableInt("yuvInFlightWork"),
            yuvBufferedFrames = json.optNullableInt("yuvBufferedFrames"),
            yuvReservedAdoptionCount = json.optNullableInt("yuvReservedAdoptionCount"),
            rawPublicExportAttemptStatus = json.optNonBlankString("rawPublicExportAttemptStatus"),
            rawPublicExportAttemptError = json.optNonBlankString("rawPublicExportAttemptError"),
            rawPublicExportAttemptAt = json.optNullableLong("rawPublicExportAttemptAt"),
            exportAttemptedFormats = json.optJSONArray("exportAttemptedFormats").toStringList(),
            exportCandidateFailureReasons = json.optJSONArray("exportCandidateFailureReasons").toStringList(),
            processingArtifactJournalCount = json.optInt("processingArtifactJournalCount", 0),
            processingArtifactJournalStates = json.optJSONArray("processingArtifactJournalStates").toStringList(),
            processingArtifactJournalFinalNames = json.optJSONArray("processingArtifactJournalFinalNames").toStringList(),
            processingArtifactInvalidJournalCount = json.optInt("processingArtifactInvalidJournalCount", 0),
            captureTiming = json.optJSONObject("captureTiming")?.let(HardwareE2ECaptureTiming::fromJson),
            backgroundStageTimings = json.optJSONObject("backgroundStageTimings").toLongMap()
        )
    }
}

internal data class HardwareE2ERunReport(
    val schemaVersion: Int,
    val runId: String,
    val runtimeSessionId: String,
    val processStartTimestamp: Long,
    val runStartWallClockTimestamp: Long,
    val runEndWallClockTimestamp: Long?,
    val scenario: HardwareE2ERunScenario,
    val appPackage: String,
    val appVersion: String,
    val debugBuild: Boolean,
    val androidSdk: Int,
    val manufacturer: String,
    val deviceModel: String,
    val buildFingerprint: String,
    val eventHistory: List<HardwareE2EEventRecord>,
    val progressCounts: Map<String, Int>,
    val terminalEvent: String?,
    val terminalFlags: Map<String, Boolean>,
    val latestJobDirectory: String?,
    /**
     * RESULT identity captured from the terminal event (SR output directory).
     * Null for YUV/RAW where result == request identity. Finalization reads
     * THIS directory's durable metadata when present; routing keeps using
     * [latestJobDirectory] (the request identity).
     */
    val resultJobDirectoryPath: String? = null,
    val finalJob: HardwareE2EJobSummary?,
    /**
     * Flattened physical-diagnosis timings promoted from the finalized job
     * summary (Phase 4).  Null when no capture timing evidence exists.
     */
    val cameraAcquisitionMs: Long? = null,
    val persistenceDrainMs: Long? = null,
    val handoffSettlementMs: Long? = null,
    val captureStageTotalMs: Long? = null,
    /** Background processing-lane duration ("backgroundStageTimings.processingMs"). */
    val backgroundProcessingMs: Long? = null,
    /** Background export-lane duration ("backgroundStageTimings.exportMs"). */
    val backgroundExportMs: Long? = null,
    val status: HardwareE2EClassification,
    val failure: String? = null,
    val classificationReason: HardwareE2EClassificationReason = HardwareE2EClassificationReason.INCOMPLETE_REPORT,
    val jobCorrelation: HardwareE2EJobCorrelation = HardwareE2EJobCorrelation.NONE,
    val jobCorrelationReason: String? = null,
    val jobCandidateCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("runId", runId)
        put("runtimeSessionId", runtimeSessionId)
        put("processStartTimestamp", processStartTimestamp)
        put("runStartWallClockTimestamp", runStartWallClockTimestamp)
        runEndWallClockTimestamp?.let { put("runEndWallClockTimestamp", it) }
        put("appPackage", appPackage)
        put("appVersion", appVersion)
        put("debugBuild", debugBuild)
        put("androidSdk", androidSdk)
        put("manufacturer", manufacturer)
        put("deviceModel", deviceModel)
        put("buildFingerprint", buildFingerprint)
        put("scenario", scenario.toJson())
        put("events", JSONArray(eventHistory.map { it.toJson() }))
        put("progressCounts", JSONObject(progressCounts))
        terminalEvent?.let { put("terminalEvent", it) }
        put("terminalFlags", JSONObject(terminalFlags))
        latestJobDirectory?.let { put("latestJobDirectory", it) }
        resultJobDirectoryPath?.let { put("resultJobDirectoryPath", it) }
        finalJob?.let { put("finalJob", it.toJson()) }
        cameraAcquisitionMs?.let { put("cameraAcquisitionMs", it) }
        persistenceDrainMs?.let { put("persistenceDrainMs", it) }
        handoffSettlementMs?.let { put("handoffSettlementMs", it) }
        captureStageTotalMs?.let { put("captureStageTotalMs", it) }
        backgroundProcessingMs?.let { put("backgroundProcessingMs", it) }
        backgroundExportMs?.let { put("backgroundExportMs", it) }
        put("status", status.name)
        put("classificationReason", classificationReason.name)
        put("jobCorrelation", jobCorrelation.name)
        jobCorrelationReason?.let { put("jobCorrelationReason", it) }
        put("jobCandidateCount", jobCandidateCount)
        failure?.let { put("failure", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): HardwareE2ERunReport {
            val scenarioJson = json.optJSONObject("scenario") ?: JSONObject()
            val events = json.optJSONArray("events") ?: JSONArray()
            val progress = json.optJSONObject("progressCounts") ?: JSONObject()
            val flags = json.optJSONObject("terminalFlags") ?: JSONObject()
            return HardwareE2ERunReport(
                schemaVersion = json.optInt("schemaVersion", 0),
                runId = json.optString("runId"),
                runtimeSessionId = json.optString("runtimeSessionId"),
                processStartTimestamp = json.optLong("processStartTimestamp"),
                runStartWallClockTimestamp = json.optLong("runStartWallClockTimestamp"),
                runEndWallClockTimestamp = json.optLong("runEndWallClockTimestamp", 0L).takeIf { it > 0L },
                scenario = HardwareE2ERunScenario(
                    requestedTestScenario = scenarioJson.optString("requestedTestScenario"),
                    selectedPipelineMode = scenarioJson.optString("selectedPipelineMode"),
                    captureMode = scenarioJson.optString("captureMode"),
                    requestedLensSlot = scenarioJson.optString("requestedLensSlot"),
                    requestedResolution = scenarioJson.optString("requestedResolution"),
                    frameCountPolicy = scenarioJson.optString("frameCountPolicy"),
                    effectiveRequestedFrames = scenarioJson.optInt("effectiveRequestedFrames"),
                    requestedZoom = scenarioJson.optDouble("requestedZoom", 1.0).toFloat(),
                    requestedOutputFormat = scenarioJson.optString("requestedOutputFormat"),
                    requestedCameraId = scenarioJson.optString("requestedCameraId").takeIf { it.isNotBlank() },
                    requestedRoute = scenarioJson.optString("requestedRoute").takeIf { it.isNotBlank() },
                    allowPartialCompletion = scenarioJson.optBoolean("allowPartialCompletion", false),
                    requiresExport = scenarioJson.optBoolean("requiresExport", true)
                ),
                appPackage = json.optString("appPackage"),
                appVersion = json.optString("appVersion"),
                debugBuild = json.optBoolean("debugBuild"),
                androidSdk = json.optInt("androidSdk"),
                manufacturer = json.optString("manufacturer"),
                deviceModel = json.optString("deviceModel"),
                buildFingerprint = json.optString("buildFingerprint"),
                eventHistory = buildList {
                    repeat(events.length()) { index ->
                        events.optJSONObject(index)?.let { event ->
                            add(
                                HardwareE2EEventRecord(
                                    checkpoint = event.optString("checkpoint"),
                                    eventType = event.optString("eventType"),
                                    elapsedMs = event.optLong("elapsedMs"),
                                    wallClockTimestamp = event.optLong("wallClockTimestamp"),
                                    generation = event.optLong("generation"),
                                    requestedFrames = event.optInt("requestedFrames"),
                                    savedFrames = event.optInt("savedFrames"),
                                    receivedImages = event.optInt("receivedImages"),
                                    completedResults = event.optInt("completedResults"),
                                    message = event.optString("message").takeIf { it.isNotBlank() },
                                    terminalKind = event.optString("terminalKind").takeIf { it.isNotBlank() },
                                    requiredOutputCommitted = event.optBoolean("requiredOutputCommitted"),
                                    publicExportCommitted = event.optBoolean("publicExportCommitted"),
                                    verified = event.optBoolean("verified"),
                                    captureResourcesSettled = event.optBoolean("captureResourcesSettled", true),
                                    handoffEvidenceComplete = event.optBoolean("handoffEvidenceComplete", false)
                                )
                            )
                        }
                    }
                },
                progressCounts = buildMap {
                    val names = progress.keys()
                    while (names.hasNext()) {
                        val name = names.next()
                        put(name, progress.optInt(name))
                    }
                },
                terminalEvent = json.optString("terminalEvent").takeIf { it.isNotBlank() },
                terminalFlags = buildMap {
                    val names = flags.keys()
                    while (names.hasNext()) {
                        val name = names.next()
                        put(name, flags.optBoolean(name))
                    }
                },
                latestJobDirectory = json.optString("latestJobDirectory").takeIf { it.isNotBlank() },
                resultJobDirectoryPath = json.optString("resultJobDirectoryPath").takeIf { it.isNotBlank() },
                finalJob = json.optJSONObject("finalJob")?.let(HardwareE2EJobSummary::fromJson),
                cameraAcquisitionMs = json.optNullableLong("cameraAcquisitionMs"),
                persistenceDrainMs = json.optNullableLong("persistenceDrainMs"),
                handoffSettlementMs = json.optNullableLong("handoffSettlementMs"),
                captureStageTotalMs = json.optNullableLong("captureStageTotalMs"),
                backgroundProcessingMs = json.optNullableLong("backgroundProcessingMs"),
                backgroundExportMs = json.optNullableLong("backgroundExportMs"),
                status = runCatching {
                    HardwareE2EClassification.valueOf(json.optString("status"))
                }.getOrDefault(HardwareE2EClassification.INCOMPLETE),
                failure = json.optString("failure").takeIf { it.isNotBlank() },
                classificationReason = runCatching {
                    HardwareE2EClassificationReason.valueOf(json.optString("classificationReason"))
                }.getOrDefault(HardwareE2EClassificationReason.INCOMPLETE_REPORT),
                jobCorrelation = runCatching {
                    HardwareE2EJobCorrelation.valueOf(json.optString("jobCorrelation"))
                }.getOrDefault(HardwareE2EJobCorrelation.NONE),
                jobCorrelationReason = json.optString("jobCorrelationReason").takeIf { it.isNotBlank() },
                jobCandidateCount = json.optInt("jobCandidateCount")
            )
        }
    }
}

private fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return buildMap {
        val names = keys()
        while (names.hasNext()) {
            val name = names.next()
            put(name, optString(name))
        }
    }
}

private fun JSONObject?.toLongMap(): Map<String, Long> {
    if (this == null) return emptyMap()
    return buildMap {
        val names = keys()
        while (names.hasNext()) {
            val name = names.next()
            put(name, optLong(name))
        }
    }
}

private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

/**
 * Bounded per-frame persistence timing derived from the persisted
 * "captureTiming" instants (nanoseconds on the monotonic clock).  Durations are
 * REAL segment boundaries - never inferred:
 *  - conversionMs: worker start -> conversion completed (buffered path; null when unset)
 *  - encodeMs: conversion (or worker start) -> encode finished = PNG compression
 *    + candidate write span
 *  - fsyncMs: conversion -> durable sink sync returned (buffered path)
 *  - verifyMs: final-file write -> verification succeeded
 */
internal data class HardwareE2EFrameTiming(
    val frameIndex: Int,
    val conversionMs: Long? = null,
    val encodeMs: Long? = null,
    val fsyncMs: Long? = null,
    val verifyMs: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("frameIndex", frameIndex)
        conversionMs?.let { put("conversionMs", it) }
        encodeMs?.let { put("encodeMs", it) }
        fsyncMs?.let { put("fsyncMs", it) }
        verifyMs?.let { put("verifyMs", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): HardwareE2EFrameTiming = HardwareE2EFrameTiming(
            frameIndex = json.optInt("frameIndex"),
            conversionMs = json.optNullableLong("conversionMs"),
            encodeMs = json.optNullableLong("encodeMs"),
            fsyncMs = json.optNullableLong("fsyncMs"),
            verifyMs = json.optNullableLong("verifyMs")
        )
    }
}

private fun nsSegmentMs(fromNs: Long?, toNs: Long?): Long? =
    if (fromNs == null || toNs == null || fromNs <= 0L || toNs <= 0L || toNs < fromNs) null else (toNs - fromNs) / 1_000_000L

/**
 * Structured snapshot of the persisted capture-stage timing evidence for ONE
 * job.  Pure data: round-trips through JSON without touching the filesystem.
 */
internal data class HardwareE2ECaptureTiming(
    val requestedFrames: Int,
    val cameraAcquisitionMs: Long,
    val persistenceDrainMs: Long,
    val handoffSettlementMs: Long,
    val captureStageTotalMs: Long,
    /** cameraAcquisitionCompleteAt -> persistenceDrainCompleteAt (canonical report name). */
    val postAcquisitionPersistenceMs: Long = 0L,
    /** THE 100%-to-shutter interval: cameraAcquisitionCompleteAt -> captureStageCompleteAt. */
    val postAcquisitionToShutterMs: Long = 0L,
    /** persistenceDrainCompleteAt -> processingHandoffPublishedAt. */
    val handoffPublicationMs: Long = 0L,
    /** processingHandoffPublishedAt -> captureResourcesSettledAt (owner exit). */
    val captureSettlementMs: Long = 0L,
    /** Last per-frame commit -> processingHandoffPublishedAt (RAW terminal metadata work). */
    val rawMetadataSettlementMs: Long = 0L,
    /** RAW aggregate durable-payload bytes; 0 for YUV jobs. */
    val rawBytesPersisted: Long = 0L,
    /** RAW aggregate measured write spans (fsync excluded); 0 for YUV jobs. */
    val rawPersistenceWriteMs: Long = 0L,
    /** RAW aggregate measured fsync spans; 0 for YUV jobs. */
    val rawPersistenceSyncMs: Long = 0L,
    /** Sum of per-frame encode segments across persisted frames (0 when unset). */
    val aggregateEncodeMs: Long = 0L,
    /** Sum of per-frame candidate-fsync segments (buffered path; 0 when unset). */
    val aggregateFsyncMs: Long = 0L,
    /** Sum of per-frame final-verify segments (0 when unset). */
    val aggregateVerifyMs: Long = 0L,
    /** Slowest single-frame encode segment - the foreground bottleneck signal. */
    val maxFrameEncodeMs: Long = 0L,
    val frames: List<HardwareE2EFrameTiming> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestedFrames", requestedFrames)
        put("cameraAcquisitionMs", cameraAcquisitionMs)
        put("persistenceDrainMs", persistenceDrainMs)
        put("handoffSettlementMs", handoffSettlementMs)
        put("captureStageTotalMs", captureStageTotalMs)
        put("postAcquisitionPersistenceMs", postAcquisitionPersistenceMs)
        put("postAcquisitionToShutterMs", postAcquisitionToShutterMs)
        put("handoffPublicationMs", handoffPublicationMs)
        put("captureSettlementMs", captureSettlementMs)
        put("rawMetadataSettlementMs", rawMetadataSettlementMs)
        put("rawBytesPersisted", rawBytesPersisted)
        put("rawPersistenceWriteMs", rawPersistenceWriteMs)
        put("rawPersistenceSyncMs", rawPersistenceSyncMs)
        put("aggregateEncodeMs", aggregateEncodeMs)
        put("aggregateFsyncMs", aggregateFsyncMs)
        put("aggregateVerifyMs", aggregateVerifyMs)
        put("maxFrameEncodeMs", maxFrameEncodeMs)
        put("frames", JSONArray(frames.map { it.toJson() }))
    }

    companion object {
        fun fromJson(json: JSONObject): HardwareE2ECaptureTiming = HardwareE2ECaptureTiming(
            requestedFrames = json.optInt("requestedFrames"),
            cameraAcquisitionMs = json.optLong("cameraAcquisitionMs"),
            persistenceDrainMs = json.optLong("persistenceDrainMs"),
            handoffSettlementMs = json.optLong("handoffSettlementMs"),
            captureStageTotalMs = json.optLong("captureStageTotalMs"),
            postAcquisitionPersistenceMs = json.optLong("postAcquisitionPersistenceMs"),
            postAcquisitionToShutterMs = json.optLong("postAcquisitionToShutterMs"),
            handoffPublicationMs = json.optLong("handoffPublicationMs"),
            captureSettlementMs = json.optLong("captureSettlementMs"),
            rawMetadataSettlementMs = json.optLong("rawMetadataSettlementMs"),
            rawBytesPersisted = json.optLong("rawBytesPersisted"),
            rawPersistenceWriteMs = json.optLong("rawPersistenceWriteMs"),
            rawPersistenceSyncMs = json.optLong("rawPersistenceSyncMs"),
            aggregateEncodeMs = json.optLong("aggregateEncodeMs"),
            aggregateFsyncMs = json.optLong("aggregateFsyncMs"),
            aggregateVerifyMs = json.optLong("aggregateVerifyMs"),
            maxFrameEncodeMs = json.optLong("maxFrameEncodeMs"),
            frames = buildList {
                val array = json.optJSONArray("frames") ?: JSONArray()
                repeat(array.length()) { index ->
                    array.optJSONObject(index)?.let { add(HardwareE2EFrameTiming.fromJson(it)) }
                }
            }
        )

        /**
         * Derives the structured timing from the persisted "captureTiming" JSON
         * projection of [CaptureTimingLedger].  Bounded arithmetic only.
         */
        fun fromCaptureTimingJson(timing: JSONObject): HardwareE2ECaptureTiming {
            val framesArray = timing.optJSONArray("frames") ?: JSONArray()
            val frameTimings = buildList {
                repeat(framesArray.length().coerceAtMost(MAX_TIMING_FRAMES)) { index ->
                    val frame = framesArray.optJSONObject(index) ?: return@repeat
                    val conversion = nsSegmentMs(
                        frame.optLong("workerStartedAt").takeIf { it > 0 },
                        frame.optLong("conversionCompletedAt").takeIf { it > 0 }
                    )
                    val encodeStart = frame.optLong("conversionCompletedAt")
                        .takeIf { it > 0 } ?: frame.optLong("workerStartedAt").takeIf { it > 0 }
                    val encode = nsSegmentMs(encodeStart, frame.optLong("encodeFinishedAt").takeIf { it > 0 })
                    val fsync = nsSegmentMs(
                        frame.optLong("conversionCompletedAt").takeIf { it > 0 },
                        frame.optLong("fsyncFinishedAt").takeIf { it > 0 }
                    )
                    val verify = nsSegmentMs(
                        frame.optLong("writeFinishedAt").takeIf { it > 0 },
                        frame.optLong("verifiedAt").takeIf { it > 0 }
                    )
                    add(
                        HardwareE2EFrameTiming(
                            frameIndex = frame.optInt("frameIndex", index),
                            conversionMs = conversion,
                            encodeMs = encode,
                            fsyncMs = fsync,
                            verifyMs = verify
                        )
                    )
                }
            }
            return HardwareE2ECaptureTiming(
                requestedFrames = timing.optInt("requestedFrames"),
                cameraAcquisitionMs = timing.optLong("cameraAcquisitionMs"),
                persistenceDrainMs = timing.optLong("persistenceDrainMs"),
                handoffSettlementMs = timing.optLong("handoffSettlementMs"),
                captureStageTotalMs = timing.optLong("captureStageTotalMs"),
                postAcquisitionPersistenceMs = timing.optLong("postAcquisitionPersistenceMs"),
                postAcquisitionToShutterMs = timing.optLong("postAcquisitionToShutterMs"),
                handoffPublicationMs = timing.optLong("handoffPublicationMs"),
                captureSettlementMs = timing.optLong("captureSettlementMs"),
                rawMetadataSettlementMs = timing.optLong("rawMetadataSettlementMs"),
                rawBytesPersisted = timing.optLong("rawBytesPersisted"),
                rawPersistenceWriteMs = timing.optLong("rawPersistenceWriteMs"),
                rawPersistenceSyncMs = timing.optLong("rawPersistenceSyncMs"),
                aggregateEncodeMs = frameTimings.sumOf { it.encodeMs ?: 0L },
                aggregateFsyncMs = frameTimings.sumOf { it.fsyncMs ?: 0L },
                aggregateVerifyMs = frameTimings.sumOf { it.verifyMs ?: 0L },
                maxFrameEncodeMs = frameTimings.mapNotNull { it.encodeMs }.maxOrNull() ?: 0L,
                frames = frameTimings
            )
        }

        private const val MAX_TIMING_FRAMES = 32
    }
}

// Diagnostics treat blank as unknown; the faithful shared optNullableString
// preserves "" exactly, so report parsing uses this blank-normalized variant.
private fun JSONObject.optNonBlankString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        repeat(length()) { index -> optString(index).takeIf { it.isNotBlank() }?.let(::add) }
    }
}

internal object HardwareE2EReportCodec {
    fun encode(report: HardwareE2ERunReport): String = report.toJson().toString(2)

    fun decode(json: String): HardwareE2ERunReport =
        HardwareE2ERunReport.fromJson(JSONObject(json))
}

internal object HardwareE2EReportStore {
    fun directory(context: Context): File = File(context.filesDir, HARDWARE_E2E_REPORT_DIRECTORY)

    fun latestFile(context: Context): File = File(directory(context), "latest.json")

    fun file(context: Context, runId: String): File = File(directory(context), "$runId.json")

    fun read(context: Context, runId: String): HardwareE2ERunReport? = read(directory(context), runId)

    fun read(reportDirectory: File, runId: String): HardwareE2ERunReport? =
        runCatching {
            File(reportDirectory, "$runId.json")
                .takeIf { it.isFile }
                ?.readText()
                ?.let(HardwareE2EReportCodec::decode)
        }.getOrNull()

    fun readReports(context: Context): List<HardwareE2ERunReport> = readReports(directory(context))

    fun readReports(reportDirectory: File): List<HardwareE2ERunReport> =
        reportDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" && it.name != "latest.json" }
            .sortedByDescending(File::lastModified)
            .mapNotNull { file -> runCatching { HardwareE2EReportCodec.decode(file.readText()) }.getOrNull() }

    fun readLatest(context: Context): HardwareE2ERunReport? =
        runCatching {
            latestFile(context).takeIf { it.isFile }?.readText()?.let(HardwareE2EReportCodec::decode)
        }.getOrNull()

    fun findLatestAfter(
        context: Context,
        previousRunId: String?,
        invocationStartWallClock: Long,
        expectedScenario: String,
        expectedPipeline: String
    ): HardwareE2ERunReport? = findLatestAfter(directory(context), previousRunId, invocationStartWallClock, expectedScenario, expectedPipeline)

    fun findLatestAfter(
        reportDirectory: File,
        previousRunId: String?,
        invocationStartWallClock: Long,
        expectedScenario: String,
        expectedPipeline: String
    ): HardwareE2ERunReport? = readReports(reportDirectory).firstOrNull { report ->
        report.runId != previousRunId &&
            report.runStartWallClockTimestamp >= invocationStartWallClock &&
            report.scenario.requestedTestScenario == expectedScenario &&
            report.scenario.selectedPipelineMode == expectedPipeline
    }

    /**
     * Stage-B pinning lookup: finds ONE new run whose identity is unambiguous
     * at its evidenced capture handoff. Unlike [findLatestAfter] this never
     * resolves runs through a mutable "latest matching pipeline" scan; see
     * [HardwareE2EStageBRunPinning.selectNewHandoffRun].
     */
    fun findNewHandoffRunAfter(
        context: Context,
        excludedRunIds: Set<String>,
        invocationStartWallClock: Long,
        expectedScenario: String,
        expectedPipeline: String
    ): HardwareE2ERunReport? = findNewHandoffRunAfter(
        directory(context), excludedRunIds, invocationStartWallClock, expectedScenario, expectedPipeline
    )

    fun findNewHandoffRunAfter(
        reportDirectory: File,
        excludedRunIds: Set<String>,
        invocationStartWallClock: Long,
        expectedScenario: String,
        expectedPipeline: String
    ): HardwareE2ERunReport? = HardwareE2EStageBRunPinning.selectNewHandoffRun(
        reports = readReports(reportDirectory),
        excludedRunIds = excludedRunIds,
        invocationStartWallClock = invocationStartWallClock,
        expectedScenario = expectedScenario,
        expectedPipeline = expectedPipeline
    )
}

/**
 * Stage-B sequential-run identity pinning (pure, JVM-testable).
 *
 * RAPID SEQUENTIAL pairs can share one pipeline (YUV->YUV, RAW->RAW). The
 * persisted report source is sorted newest-first, so discovering A and B only
 * AFTER both captures handed off would let the newest matching report be B and
 * misidentify it as A. The fix is ordering, not search: each run identity is
 * PINNED at the moment its own evidenced CaptureStageComplete exists - before
 * the next capture starts - and terminal waits read that EXACT run id.
 */
internal object HardwareE2EStageBRunPinning {

    const val CAPTURE_STAGE_COMPLETE_CHECKPOINT = "CAPTURE_STAGE_COMPLETE"

    /** True when the run's own event history proves the durable handoff boundary. */
    fun hasEvidencedCaptureStageComplete(report: HardwareE2ERunReport): Boolean =
        report.eventHistory.any {
            it.checkpoint == CAPTURE_STAGE_COMPLETE_CHECKPOINT && it.handoffEvidenceComplete
        }

    /**
     * Terminal gate for an EXACT pinned run id: never satisfied by any newer
     * matching run. A pinned run must have published its terminal AND carry its
     * finalization result (or failure).
     */
    fun isTerminalReady(report: HardwareE2ERunReport): Boolean =
        report.terminalEvent != null && (report.finalJob != null || report.failure != null)

    /**
     * Selects the new run to pin among already-evidenced candidates:
     *  - excludes every run captured in [excludedRunIds] (baseline + pinned);
     *  - requires a run started at/after [invocationStartWallClock];
     *  - requires the exact scenario + pipeline of THIS capture attempt;
     *  - requires evidenced CaptureStageComplete in the run's event history.
     *
     * Ties on start timestamp cannot occur for sequential captures, but when
     * several candidates somehow exist the EARLIEST-started one wins: identity
     * is assigned in creation order, so a newer run can never be mistaken for
     * an older unpinned one.
     */
    fun selectNewHandoffRun(
        reports: List<HardwareE2ERunReport>,
        excludedRunIds: Set<String>,
        invocationStartWallClock: Long,
        expectedScenario: String,
        expectedPipeline: String
    ): HardwareE2ERunReport? = reports
        .asSequence()
        .filter { it.runId !in excludedRunIds }
        .filter { it.runStartWallClockTimestamp >= invocationStartWallClock }
        .filter { it.scenario.requestedTestScenario == expectedScenario }
        .filter { it.scenario.selectedPipelineMode == expectedPipeline }
        .filter(::hasEvidencedCaptureStageComplete)
        .minByOrNull { it.runStartWallClockTimestamp }

    /** True when the second capture must be reconfigured (persist + recreate) BEFORE its click. */
    fun requiresConfigurationBeforeSecondClick(pipelineA: String, pipelineB: String): Boolean =
        pipelineA != pipelineB

    /**
     * Deterministic Stage-B harness plan. CONFIGURE_PIPELINE_B sits strictly
     * between A's handoff pin and B's click for mixed pairs: CameraScreen reads
     * the Compose pipelineMode AT the shutter click, so persisting B's mode
     * after the click would silently capture B with A's pipeline.
     */
    enum class Step {
        CONFIGURE_PIPELINE_A,
        CLICK_CAPTURE_A,
        AWAIT_AND_PIN_HANDOFF_A,
        CONFIGURE_PIPELINE_B,
        CLICK_CAPTURE_B,
        AWAIT_AND_PIN_HANDOFF_B,
        RELEASE_HEAVY_LANE,
        AWAIT_TERMINAL_A_BY_PINNED_ID,
        AWAIT_TERMINAL_B_BY_PINNED_ID
    }

    fun stageBPlan(pipelineA: String, pipelineB: String): List<Step> = buildList {
        add(Step.CONFIGURE_PIPELINE_A)
        add(Step.CLICK_CAPTURE_A)
        add(Step.AWAIT_AND_PIN_HANDOFF_A)
        if (requiresConfigurationBeforeSecondClick(pipelineA, pipelineB)) {
            add(Step.CONFIGURE_PIPELINE_B)
        }
        add(Step.CLICK_CAPTURE_B)
        add(Step.AWAIT_AND_PIN_HANDOFF_B)
        add(Step.RELEASE_HEAVY_LANE)
        add(Step.AWAIT_TERMINAL_A_BY_PINNED_ID)
        add(Step.AWAIT_TERMINAL_B_BY_PINNED_ID)
    }
}

internal class HardwareE2ERunRecorder private constructor(
    private val reportDirectory: File,
    private val environment: HardwareE2EEnvironment,
    private val jobFinder: () -> List<File>
) {
    private val enabled = environment.debugBuild
    private val lock = Any()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KeplerHardwareE2ERecorder").apply { isDaemon = true }
    }

    // Phase 7E: multiple pipeline lifetimes may overlap once capture admission
    // frees at durable handoff. Runs are tracked individually; events route to
    // their own run through the pipeline generation, and an exact job directory
    // bound at CaptureStageComplete pins terminal correlation even when newer
    // foreground captures have started.
    private var current: HardwareE2ERunReport? = null
    private val runsByRunId = LinkedHashMap<String, HardwareE2ERunReport>()
    private val runIdByGeneration = HashMap<Long, String>()
    // Exact job-directory -> run binding. Established at the evidenced
    // CaptureStageComplete of a run and used as the FIRST routing priority for
    // background events whose generation is 0 (the foreground generation is no
    // longer authoritative after durable handoff). Entries are intentionally
    // retained for the recorder lifetime: a late terminal/finalization for an
    // old job must still resolve its own run even after newer captures started.
    private val runIdByJobDirectory = HashMap<String, String>()
    private val baselinesByRunId = HashMap<String, Set<String>>()
    private val baselineFailuresByRunId = HashMap<String, String?>()
    private val startedAtNanosByRunId = HashMap<String, Long>()
    private var latestRunSequence = 0L
    private val sequenceByRunId = HashMap<String, Long>()
    private var latestRunId: String? = null

    fun start(scenario: HardwareE2ERunScenario): String? {
        if (!enabled) return null
        val now = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        val baseline = runCatching { jobFinder().map(File::getAbsolutePath).toSet() }
        val report = HardwareE2ERunReport(
            schemaVersion = HARDWARE_E2E_SCHEMA_VERSION,
            runId = runId,
            runtimeSessionId = environment.runtimeSessionId,
            processStartTimestamp = environment.processStartTimestamp,
            runStartWallClockTimestamp = now,
            runEndWallClockTimestamp = null,
            scenario = scenario,
            appPackage = environment.appPackage,
            appVersion = environment.appVersion,
            debugBuild = environment.debugBuild,
            androidSdk = environment.androidSdk,
            manufacturer = environment.manufacturer,
            deviceModel = environment.deviceModel,
            buildFingerprint = environment.buildFingerprint,
            eventHistory = emptyList(),
            progressCounts = emptyMap(),
            terminalEvent = null,
            terminalFlags = emptyMap(),
            latestJobDirectory = null,
            finalJob = null,
            status = HardwareE2EClassification.INCOMPLETE,
            classificationReason = HardwareE2EClassificationReason.INCOMPLETE_REPORT,
            jobCorrelationReason = baseline.exceptionOrNull()?.let {
                "baseline job snapshot failed: ${it.javaClass.simpleName}: ${it.message}"
            }
        )
        synchronized(lock) {
            runsByRunId[runId] = report
            baselinesByRunId[runId] = baseline.getOrDefault(emptySet())
            baselineFailuresByRunId[runId] = baseline.exceptionOrNull()?.message
            current = report
            startedAtNanosByRunId[runId] = System.nanoTime()
            sequenceByRunId[runId] = ++latestRunSequence
            latestRunId = runId
        }
        recordCheckpoint("RUN_STARTED", null, null, runId)
        return runId
    }

    fun recordEvent(event: CameraPipelineEvent) {
        recordRouted(event, exactJobDirectory = null)
    }

    /**
     * Records one process-scoped background event envelope. ROUTING uses ONLY
     * the exact REQUEST job-directory binding (for SR the source capture job):
     * a background event with an exact request job that has no bound run is
     * dropped rather than being attributed to the current run. Generation 0
     * must never fall back to "current" here.
     */
    fun recordBackgroundEvent(background: BackgroundPipelineEvent) {
        val event = background.event
        val checkpoint = checkpointFor(event)
        val targetRunId = synchronized(lock) {
            runIdByJobDirectory[background.requestJobDirectory.absolutePath]
        } ?: return
        recordToRun(event, checkpoint, targetRunId)
    }

    private fun checkpointFor(event: CameraPipelineEvent): String = when (event) {
        is CameraPipelineEvent.Started -> "CAPTURE_STARTED"
        is CameraPipelineEvent.CaptureProgress -> "CAPTURE_PROGRESS"
        is CameraPipelineEvent.CaptureStageComplete -> "CAPTURE_STAGE_COMPLETE"
        is CameraPipelineEvent.ProcessingStage -> "PROCESSING_STARTED"
        is CameraPipelineEvent.ExportStage -> "EXPORT_STARTED"
        is CameraPipelineEvent.Terminal -> when (event.kind) {
            CameraPipelineEvent.Terminal.Kind.COMPLETE,
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL -> "TERMINAL_COMPLETE"
            CameraPipelineEvent.Terminal.Kind.FAILED -> "TERMINAL_FAILED"
            CameraPipelineEvent.Terminal.Kind.CANCELLED -> "TERMINAL_CANCELLED"
        }
    }

    /**
     * Routing priority: (1) exact job-directory mapping, (2) foreground
     * generation mapping, (3) current run only for truly unbound foreground
     * events before an exact job exists.
     */
    private fun recordRouted(event: CameraPipelineEvent, exactJobDirectory: File?) {
        val checkpoint = checkpointFor(event)
        val targetRunId = synchronized(lock) {
            val boundByJob = exactJobDirectory?.absolutePath?.let { runIdByJobDirectory[it] }
            when {
                boundByJob != null -> boundByJob
                event.generation != 0L -> runIdByGeneration[event.generation]
                    ?: current?.runId?.also { runIdByGeneration[event.generation] = it }
                else -> current?.runId
            }
        } ?: return
        if (event is CameraPipelineEvent.CaptureStageComplete &&
            event.handoffEvidenceComplete &&
            event.jobDirectoryPath != null
        ) {
            synchronized(lock) { runIdByJobDirectory[event.jobDirectoryPath] = targetRunId }
        }
        recordToRun(event, checkpoint, targetRunId)
    }

    private fun recordToRun(
        event: CameraPipelineEvent,
        checkpoint: String,
        targetRunId: String
    ) {
        val record = HardwareE2EEventRecord(
            checkpoint = checkpoint,
            eventType = event.javaClass.simpleName,
            elapsedMs = elapsedMillis(targetRunId),
            wallClockTimestamp = System.currentTimeMillis(),
            generation = event.generation,
            requestedFrames = event.counts.requestedFrames,
            savedFrames = event.counts.savedFrames,
            receivedImages = event.counts.receivedImages,
            completedResults = event.counts.completedResults,
            message = event.message,
            terminalKind = (event as? CameraPipelineEvent.Terminal)?.kind?.name,
            requiredOutputCommitted = (event as? CameraPipelineEvent.Terminal)?.requiredOutputCommitted == true,
            publicExportCommitted = (event as? CameraPipelineEvent.Terminal)?.publicExportCommitted == true,
            verified = (event as? CameraPipelineEvent.Terminal)?.verified == true,
            captureResourcesSettled = (event as? CameraPipelineEvent.Terminal)?.captureResourcesSettled ?: true,
            handoffEvidenceComplete = (event as? CameraPipelineEvent.CaptureStageComplete)
                ?.handoffEvidenceComplete == true
        )
        mutateRun(targetRunId) { report ->
            val nextCounts = report.progressCounts.toMutableMap()
            nextCounts[checkpoint] = (nextCounts[checkpoint] ?: 0) + 1
            report.copy(
                eventHistory = report.eventHistory + record,
                progressCounts = nextCounts,
                terminalEvent = record.terminalKind ?: report.terminalEvent,
                terminalFlags = if (event is CameraPipelineEvent.Terminal) {
                    mapOf(
                        "requiredOutputCommitted" to event.requiredOutputCommitted,
                        "publicExportCommitted" to event.publicExportCommitted,
                        "verified" to event.verified,
                        "captureResourcesSettled" to event.captureResourcesSettled
                    )
                } else report.terminalFlags,
                latestJobDirectory = (event as? CameraPipelineEvent.CaptureStageComplete)
                    ?.jobDirectoryPath ?: report.latestJobDirectory,
                resultJobDirectoryPath = (event as? CameraPipelineEvent.Terminal)
                    ?.resultJobDirectoryPath ?: report.resultJobDirectoryPath,
                runEndWallClockTimestamp = if (event is CameraPipelineEvent.Terminal) {
                    record.wallClockTimestamp
                } else report.runEndWallClockTimestamp,
                status = if (event is CameraPipelineEvent.Terminal) {
                    when (event.kind) {
                        CameraPipelineEvent.Terminal.Kind.COMPLETE,
                        CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL -> HardwareE2EClassification.PASS
                        CameraPipelineEvent.Terminal.Kind.FAILED,
                        CameraPipelineEvent.Terminal.Kind.CANCELLED -> HardwareE2EClassification.FAIL
                    }
                } else report.status
            )
        }
        if (event is CameraPipelineEvent.Terminal) {
            if (event.publicExportCommitted) {
                recordCheckpoint("PUBLIC_OUTPUT_COMMITTED", null, null, targetRunId)
            }
            if (event.captureResourcesSettled) {
                recordCheckpoint("OWNER_SETTLED", null, null, targetRunId)
            }
            finalizeAfterTerminal(targetRunId)
        }
    }

    fun recordCheckpoint(checkpoint: String, jobDirectory: File?, message: String?) {
        recordCheckpoint(checkpoint, jobDirectory, message, null)
    }

    fun recordCheckpoint(checkpoint: String, jobDirectory: File?, message: String?, targetRunId: String?) {
        val effectiveRunId = targetRunId ?: synchronized(lock) { current?.runId } ?: return
        val record = HardwareE2EEventRecord(
                checkpoint = checkpoint,
                eventType = "checkpoint",
                elapsedMs = elapsedMillis(effectiveRunId),
                wallClockTimestamp = System.currentTimeMillis(),
                generation = 0L,
                requestedFrames = 0,
                savedFrames = 0,
                receivedImages = 0,
                completedResults = 0,
                message = message
            )
        mutateRun(effectiveRunId) { report ->
            val nextCounts = report.progressCounts.toMutableMap()
            nextCounts[checkpoint] = (nextCounts[checkpoint] ?: 0) + 1
            report.copy(
                eventHistory = report.eventHistory + record,
                progressCounts = nextCounts,
                latestJobDirectory = jobDirectory?.absolutePath ?: report.latestJobDirectory
            )
        }
    }

    fun recordSkipped(reason: String) {
        if (!enabled) return
        mutate {
            it.copy(
                status = HardwareE2EClassification.SKIPPED_UNSUPPORTED,
                classificationReason = HardwareE2EClassificationReason.SKIPPED_CAPABILITY,
                failure = reason
            )
        }
    }

    fun currentRunId(): String? = synchronized(lock) { current?.runId }

    fun snapshot(): HardwareE2ERunReport? = synchronized(lock) { current }

    /** Test/diagnostic accessor: every tracked run in start order. */
    internal fun snapshotsForTest(): List<HardwareE2ERunReport> =
        synchronized(lock) { runsByRunId.values.toList() }

    fun awaitIdle(timeoutMillis: Long = 5_000L): Boolean {
        val marker = writer.submit { Unit }
        return runCatching { marker.get(timeoutMillis, TimeUnit.MILLISECONDS); true }.getOrDefault(false)
    }

    fun close() {
        writer.shutdown()
    }

    private fun mutate(transform: (HardwareE2ERunReport) -> HardwareE2ERunReport) {
        if (!enabled) return
        val runId = synchronized(lock) { current?.runId } ?: return
        mutateRun(runId, transform)
    }

    private fun mutateRun(
        runId: String,
        transform: (HardwareE2ERunReport) -> HardwareE2ERunReport
    ) {
        if (!enabled) return
        val snapshot = synchronized(lock) {
            val updated = runsByRunId[runId]?.let(transform) ?: return
            runsByRunId[runId] = updated
            if (current?.runId == runId) current = updated
            updated
        }
        enqueuePersist(snapshot)
    }

    private fun finalizeAfterTerminal(runId: String?) {
        if (runId == null) return
        writer.execute {
            val reportBefore = synchronized(lock) { runsByRunId[runId] } ?: return@execute
            val correlation = correlateJob(reportBefore, runId)
            synchronized(lock) {
                val report = runsByRunId[runId] ?: return@execute
                val decision = classify(report, correlation)
                val updated = report.copy(
                    latestJobDirectory = correlation.summary?.jobDirectory ?: report.latestJobDirectory,
                    finalJob = correlation.summary,
                    cameraAcquisitionMs = correlation.summary?.captureTiming?.cameraAcquisitionMs,
                    persistenceDrainMs = correlation.summary?.captureTiming?.persistenceDrainMs,
                    handoffSettlementMs = correlation.summary?.captureTiming?.handoffSettlementMs,
                    captureStageTotalMs = correlation.summary?.captureTiming?.captureStageTotalMs,
                    backgroundProcessingMs = correlation.summary?.backgroundStageTimings?.get("processingMs"),
                    backgroundExportMs = correlation.summary?.backgroundStageTimings?.get("exportMs"),
                    status = decision.status,
                    failure = if (decision.status == HardwareE2EClassification.PASS) {
                        null
                    } else {
                        decision.reason.name + (decision.detail?.let { ": $it" } ?: "")
                    },
                    classificationReason = decision.reason,
                    jobCorrelation = correlation.kind,
                    jobCorrelationReason = correlation.detail,
                    jobCandidateCount = correlation.candidateCount
                )
                runsByRunId[runId] = updated
                if (current?.runId == runId) current = updated
                persistNow(updated)
            }
        }
    }

    private data class JobIdentity(
        val directory: File,
        val job: JSONObject,
        val createdAt: Long,
        val fresh: Boolean,
        val metadataMatches: Boolean,
        val metadataComplete: Boolean
    )

    private data class JobCorrelationResult(
        val kind: HardwareE2EJobCorrelation,
        val summary: HardwareE2EJobSummary?,
        val detail: String?,
        val candidateCount: Int
    )

    private data class ClassificationDecision(
        val status: HardwareE2EClassification,
        val reason: HardwareE2EClassificationReason,
        val detail: String? = null
    )

    private fun correlateJob(report: HardwareE2ERunReport, runId: String): JobCorrelationResult {
        // RESULT identity wins for FINALIZATION: when the terminal event carried
        // an explicit result job directory (SR output), the durable metadata of
        // THAT directory is what this run's artifacts live in. Never inferred
        // through a "latest job" lookup — it is exact terminal-carried evidence.
        val resultPath = report.resultJobDirectoryPath
        if (resultPath != null) {
            val resultDir = File(resultPath)
            val resultJob = runCatching {
                JSONObject(NoFollowFileSystem.readTextVerified(File(resultDir, JOB_JSON_FILE_NAME)))
            }.getOrNull()
            if (resultJob != null && resultDir.isDirectory) {
                return JobCorrelationResult(
                    HardwareE2EJobCorrelation.EXACT,
                    readJobSummary(resultDir),
                    "exact result identity captured from terminal event",
                    1
                )
            }
        }
        // Exact REQUEST binding wins otherwise: the foreground diagnostic run was
        // associated with this exact job directory at its durable
        // CaptureStageComplete, so a newer capture can never steal or be blamed
        // for this run's terminal.
        val boundPath = report.latestJobDirectory
        if (boundPath != null) {
            val boundDir = File(boundPath)
            val job = runCatching {
                JSONObject(NoFollowFileSystem.readTextVerified(File(boundDir, JOB_JSON_FILE_NAME)))
            }.getOrNull()
            if (job != null) {
                return JobCorrelationResult(
                    HardwareE2EJobCorrelation.EXACT,
                    readJobSummary(boundDir),
                    "exact job directory bound at capture handoff",
                    1
                )
            }
        }
        baselinesByRunId[runId]?.let { baselinePaths ->
            if (baselineSnapshotFailureFor(runId) != null) {
                return JobCorrelationResult(
                    HardwareE2EJobCorrelation.NONE,
                    null,
                    "baseline job snapshot failed: ${baselineFailuresByRunId[runId]}",
                    0
                )
            }
            return correlateAgainstBaseline(report, baselinePaths)
        }
        return JobCorrelationResult(HardwareE2EJobCorrelation.NONE, null, "no baseline available", 0)
    }

    private fun baselineSnapshotFailureFor(runId: String): String? = baselineFailuresByRunId[runId]

    private fun correlateAgainstBaseline(
        report: HardwareE2ERunReport,
        baselineJobPaths: Set<String>
    ): JobCorrelationResult {
        val identities = runCatching {
            jobFinder()
                .filter { it.isDirectory && it.absolutePath !in baselineJobPaths }
                .mapNotNull { directory ->
                    val job = runCatching {
                        JSONObject(NoFollowFileSystem.readTextVerified(File(directory, JOB_JSON_FILE_NAME)))
                    }.getOrNull() ?: return@mapNotNull null
                    val createdAt = job.optLong("createdAt", 0L)
                    val fresh = if (createdAt > 0L) {
                        createdAt >= report.runStartWallClockTimestamp
                    } else {
                        directory.lastModified() >= report.runStartWallClockTimestamp
                    }
                    if (!fresh) return@mapNotNull null
                    val metadataComplete = job.optString("jobType").isNotBlank() ||
                        job.optString("captureMode").isNotBlank()
                    JobIdentity(
                        directory = directory,
                        job = job,
                        createdAt = createdAt,
                        fresh = true,
                        metadataMatches = jobMatchesScenario(job, report.scenario),
                        metadataComplete = metadataComplete
                    )
                }
        }.getOrElse { error ->
            return JobCorrelationResult(
                HardwareE2EJobCorrelation.NONE,
                null,
                "job inspection failed: ${error.javaClass.simpleName}: ${error.message}",
                0
            )
        }
        val matching = identities.filter { it.metadataMatches }
        return when {
            matching.size == 1 -> JobCorrelationResult(
                HardwareE2EJobCorrelation.EXACT,
                readJobSummary(matching.single().directory),
                "one new job matched the expected pipeline/capture metadata",
                identities.size
            )
            matching.size > 1 -> JobCorrelationResult(
                HardwareE2EJobCorrelation.AMBIGUOUS,
                null,
                "${matching.size} new jobs matched the expected pipeline/capture metadata",
                identities.size
            )
            identities.size == 1 && !identities.single().metadataComplete -> JobCorrelationResult(
                HardwareE2EJobCorrelation.PROBABLE,
                readJobSummary(identities.single().directory),
                "one new job was found but identifying metadata was incomplete",
                identities.size
            )
            identities.size > 1 -> JobCorrelationResult(
                HardwareE2EJobCorrelation.AMBIGUOUS,
                null,
                "${identities.size} new jobs were found without one trustworthy match",
                identities.size
            )
            else -> JobCorrelationResult(
                HardwareE2EJobCorrelation.NONE,
                null,
                "no new attributable job was found; pre-existing jobs were excluded",
                0
            )
        }
    }

    private fun jobMatchesScenario(job: JSONObject, scenario: HardwareE2ERunScenario): Boolean {
        val expectedType = if (scenario.selectedPipelineMode == PipelineMode.RAW_NIGHT_FUSION.name) {
            "RAW_NIGHT_FUSION"
        } else if (scenario.captureMode == CaptureMode.SINGLE_FRAME.name) {
            "YUV_SINGLE_FRAME"
        } else {
            "YUV_NIGHT_FUSION"
        }
        val actualType = job.optString("jobType")
        if (actualType.isNotBlank() && actualType != expectedType) return false
        val actualCaptureMode = job.optString("captureMode")
        if (actualCaptureMode.isNotBlank() && actualCaptureMode != scenario.captureMode) return false
        val resolution = job.optString("requestedResolutionMode", job.optString("resolutionMode"))
        if (resolution.isNotBlank()) {
            val expectedDigits = scenario.requestedResolution.filter(Char::isDigit)
            if (expectedDigits.isNotBlank() && !resolution.filter(Char::isDigit).contains(expectedDigits)) return false
        }
        return true
    }

    private fun classify(
        report: HardwareE2ERunReport,
        correlation: JobCorrelationResult
    ): ClassificationDecision {
        val terminal = report.eventHistory.lastOrNull { it.terminalKind != null }
        val terminalKind = terminal?.terminalKind
        if (terminalKind == null) {
            return ClassificationDecision(HardwareE2EClassification.INCOMPLETE, HardwareE2EClassificationReason.INCOMPLETE_REPORT, "terminal event is missing")
        }
        if (terminalKind == CameraPipelineEvent.Terminal.Kind.FAILED.name ||
            terminalKind == CameraPipelineEvent.Terminal.Kind.CANCELLED.name
        ) {
            return ClassificationDecision(HardwareE2EClassification.FAIL, HardwareE2EClassificationReason.FAIL_PIPELINE_TERMINAL, terminal.message)
        }
        if (terminalKind == CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL.name &&
            !report.scenario.allowPartialCompletion
        ) {
            return ClassificationDecision(HardwareE2EClassification.FAIL, HardwareE2EClassificationReason.FAIL_PARTIAL_NOT_ALLOWED, "partial completion is not valid for this smoke scenario")
        }
        if (correlation.kind != HardwareE2EJobCorrelation.EXACT &&
            correlation.kind != HardwareE2EJobCorrelation.PROBABLE
        ) {
            return ClassificationDecision(HardwareE2EClassification.INCOMPLETE, HardwareE2EClassificationReason.INCOMPLETE_JOB_CORRELATION, correlation.detail)
        }
        val job = correlation.summary
            ?: return ClassificationDecision(HardwareE2EClassification.INCOMPLETE, HardwareE2EClassificationReason.INCOMPLETE_JOB_CORRELATION, "correlated job summary is missing")
        if (!job.readable) {
            return ClassificationDecision(HardwareE2EClassification.INCOMPLETE, HardwareE2EClassificationReason.INCOMPLETE_REPORT, job.error)
        }
        if (terminal.requiredOutputCommitted != true || !job.requiredOutputFilePresent) {
            return ClassificationDecision(HardwareE2EClassification.FAIL, HardwareE2EClassificationReason.FAIL_OUTPUT_NOT_COMMITTED, "required output was not durably proven")
        }
        if (!terminal.captureResourcesSettled || job.liveOperationRegistered) {
            return ClassificationDecision(HardwareE2EClassification.FAIL, HardwareE2EClassificationReason.FAIL_LIVE_OPERATION_REMAINS, "capture resources or operation lease remain live")
        }
        if (job.requestedFrames != report.scenario.effectiveRequestedFrames ||
            job.savedFrames > job.requestedFrames ||
            (job.attemptedFrames != null && job.attemptedFrames < job.savedFrames) ||
            (job.receivedImages != null && job.completedResults != null && job.receivedImages < job.completedResults)
        ) {
            return ClassificationDecision(HardwareE2EClassification.FAIL, HardwareE2EClassificationReason.FAIL_FRAME_ACCOUNTING, "job frame counts are contradictory")
        }
        val state = listOf(job.status, job.processStatus, job.exportStatus).joinToString(" ").uppercase()
        if (state.contains("FAILED") || state.contains("CANCELLED") || state.contains("ERROR")) {
            return ClassificationDecision(HardwareE2EClassification.FAIL, HardwareE2EClassificationReason.FAIL_STATE_CONTRADICTION, "job state contradicts terminal success: $state")
        }
        if (report.scenario.requiresExport && job.exportStatus.uppercase() in setOf("FAILED", "CANCELLED", "ERROR")) {
            return ClassificationDecision(HardwareE2EClassification.FAIL, HardwareE2EClassificationReason.FAIL_EXPORT_STATE, "export state is ${job.exportStatus}")
        }
        return ClassificationDecision(HardwareE2EClassification.PASS, HardwareE2EClassificationReason.PASS_SUCCESS)
    }

    private fun enqueuePersist(report: HardwareE2ERunReport) {
        writer.execute {
            persistNow(report)
        }
    }

    private fun persistNow(report: HardwareE2ERunReport) {
        runCatching {
            reportDirectory.mkdirs()
            val reportFile = File(reportDirectory, "${report.runId}.json")
            val temp = File(reportDirectory, "${report.runId}.json.tmp")
            temp.writeText(HardwareE2EReportCodec.encode(report))
            if (!temp.renameTo(reportFile)) {
                reportFile.delete()
                check(temp.renameTo(reportFile)) { "could not publish hardware report" }
            }
            // Only update latest.json when this report is still the latest-started run.
            // Older terminal must not overwrite latest.json (monotonic sequence/start timestamp guard).
            val shouldUpdateLatest = synchronized(lock) {
                val seq = sequenceByRunId[report.runId]
                if (seq != null) {
                    seq == latestRunSequence
                } else {
                    // Fallback to timestamp for reports without sequence (e.g., after restart)
                    val latestId = latestRunId
                    val latestReport = latestId?.let { runsByRunId[it] }
                    if (latestReport != null) {
                        report.runStartWallClockTimestamp >= latestReport.runStartWallClockTimestamp
                    } else {
                        // Check filesystem latest.json timestamp as last resort
                        val latestFile = File(reportDirectory, "latest.json")
                        if (!latestFile.isFile) true else {
                            val latestDecoded = runCatching { HardwareE2EReportCodec.decode(latestFile.readText()) }.getOrNull()
                            if (latestDecoded != null) report.runStartWallClockTimestamp >= latestDecoded.runStartWallClockTimestamp else true
                        }
                    }
                }
            }
            if (shouldUpdateLatest) {
                val latest = File(reportDirectory, "latest.json")
                val latestTemp = File(reportDirectory, "latest.json.tmp")
                latestTemp.writeText(HardwareE2EReportCodec.encode(report))
                if (!latestTemp.renameTo(latest)) {
                    latest.delete()
                    check(latestTemp.renameTo(latest)) { "could not publish latest hardware report" }
                }
            }
            reportDirectory.listFiles()
                ?.filter { it.extension == "json" && it.name != "latest.json" }
                ?.sortedByDescending { file ->
                    if (file.name == reportFile.name) Long.MAX_VALUE else file.lastModified()
                }
                ?.drop(HARDWARE_E2E_MAX_REPORTS)
                ?.forEach { it.delete() }
        }.onFailure { error ->
            runCatching {
                Log.w(HARDWARE_E2E_TAG, "diagnostic report write ignored", error)
            }
        }
    }

    private fun elapsedMillis(targetRunId: String): Long {
        val started = synchronized(lock) { startedAtNanosByRunId[targetRunId] } ?: return 0L
        return ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
    }

    @Suppress("unused")
    private fun elapsedMillis(): Long = 0L

    private fun readJobSummary(jobDir: File): HardwareE2EJobSummary {
        val jobFile = File(jobDir, JOB_JSON_FILE_NAME)
        val job = runCatching { JSONObject(NoFollowFileSystem.readTextVerified(jobFile)) }
            .getOrElse { error ->
                return HardwareE2EJobSummary(
                    jobDirectory = jobDir.absolutePath,
                    readable = false,
                    jobType = "",
                    captureMode = "",
                    createdAt = 0L,
                    status = "",
                    processStatus = "",
                    exportStatus = "",
                    exportVerified = false,
                    requiredOutputFilePresent = false,
                    requestedFrames = 0,
                    attemptedFrames = null,
                    savedFrames = 0,
                    receivedImages = null,
                    completedResults = null,
                    failedCaptures = 0,
                    partialCapture = false,
                    cleanupType = "",
                    cameraId = "",
                    physicalCameraId = "",
                    requestedPhysicalCameraId = "",
                    dngSidecarSaved = null,
                    dngSidecarSkipReason = "",
                    dngSidecarStatuses = emptyList(),
                    frameManifestCount = 0,
                    rawMetadata = emptyMap(),
                    selectedRoute = "",
                    actualRoute = "",
                    processingTiming = emptyMap(),
                    memoryFields = emptyMap(),
                    activeOperationId = "",
                    activeOperationKind = "",
                    activeRuntimeSessionId = "",
                    terminalOperationId = "",
                    liveOperationRegistered = false,
                    fileNames = emptyList(),
                    error = "${error.javaClass.simpleName}: ${error.message}"
                )
            }
        val timingKeys = listOf(
            "captureDurationMs", "processingDurationMs", "exportDurationMs", "totalDurationMs",
            "alignmentDurationMs", "mergeDurationMs", "demosaicDurationMs"
        )
        val memoryKeys = listOf(
            "memoryRiskLevel", "estimatedRawFusionMemoryMb", "estimatedMemoryMb",
            "nativeWorkingSetMb", "memoryTrimmed", "lowMemoryFallback"
        )
        val fileNames = runCatching {
            NoFollowFileSystem.requireDirectChildren(jobDir)
                .filter { NoFollowFileSystem.isRealFile(it.toPath()) }
                .map(File::getName)
                .sorted()
        }.getOrDefault(emptyList())
        val outputFilePresent = listOf(
            "finalFile", "finalNightFusionFile", "outputFile", "nativePostprocessRgbaFile",
            "averageColorFile", "galleryDisplayFile"
        ).any { key ->
            val value = job.optString(key)
            value.isNotBlank() && value != JSONObject.NULL.toString() &&
                File(if (File(value).isAbsolute) value else File(jobDir, value).path).isFile
        } || job.optBoolean("requiredOutputCommitted", false)
        val savedFrames = job.optInt("savedFrames", 0)
        val requestedFrames = job.optInt("requestedFrames", savedFrames)
        val attemptedFrames = job.optNullableInt("attemptedFrames")
        val receivedImages = job.optNullableInt("receivedImages")
        val completedResults = job.optNullableInt("completedResults")
        val journalScan = runCatching { ProcessingArtifactJournal.scan(jobDir) }.getOrNull()
        val processingArtifactJournalStates = journalScan?.validJournals?.map { it.second.state.name } ?: emptyList()
        val processingArtifactJournalFinalNames = journalScan?.validJournals?.map { it.second.finalName } ?: emptyList()
        return HardwareE2EJobSummary(
            jobDirectory = jobDir.absolutePath,
            readable = true,
            jobType = job.optString("jobType"),
            captureMode = job.optString("captureMode"),
            createdAt = job.optLong("createdAt", 0L),
            status = job.optString("status"),
            processStatus = job.optString("processStatus"),
            exportStatus = job.optString("exportStatus"),
            exportVerified = job.optBoolean("exportVerified", false),
            requiredOutputFilePresent = outputFilePresent,
            requestedFrames = requestedFrames,
            attemptedFrames = attemptedFrames,
            savedFrames = savedFrames,
            receivedImages = receivedImages,
            completedResults = completedResults,
            failedCaptures = job.optInt("failedCaptures", 0),
            partialCapture = job.optBoolean("partialCapture", false),
            cleanupType = job.optString("cleanupType"),
            cameraId = job.optString("cameraId"),
            physicalCameraId = job.optString("physicalCameraId"),
            requestedPhysicalCameraId = job.optString("requestedPhysicalCameraId"),
            dngSidecarSaved = if (job.has("dngSidecarSaved")) job.optBoolean("dngSidecarSaved") else null,
            dngSidecarSkipReason = job.optString("dngSidecarSkipReason"),
            dngSidecarStatuses = buildList {
                val frames = job.optJSONArray("frames")
                repeat(frames?.length() ?: 0) { index ->
                    frames?.optJSONObject(index)?.optString("dngSidecarStatus")
                        ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
                }
            },
            frameManifestCount = job.optJSONArray("frames")?.length() ?: 0,
            rawMetadata = listOf(
                "rawWidth", "rawHeight", "rowStride", "pixelStride", "rawSizeSource",
                "rawMinFrameDurationNs", "rawStallDurationNs",
                "rawPersistenceWriteStrategy"
            ).associateWith { job.optString(it) }.filterValues { it.isNotBlank() },
            selectedRoute = job.optString("selectedRoute", job.optString("zoomRoute")),
            actualRoute = job.optString("actualRoute", job.optString("finalRoute")),
            processingTiming = timingKeys.associateWith { job.optLong(it, -1L) }.filterValues { it >= 0L },
            memoryFields = memoryKeys.associateWith { job.optString(it) }.filterValues { it.isNotBlank() },
            activeOperationId = job.optString(ACTIVE_OPERATION_ID),
            activeOperationKind = job.optString(ACTIVE_OPERATION_KIND),
            activeRuntimeSessionId = job.optString(ACTIVE_RUNTIME_SESSION_ID),
            terminalOperationId = job.optString(TERMINAL_OPERATION_ID),
            liveOperationRegistered = runCatching { KeplerJobMetadata.findOperationLease(jobDir) != null }.getOrDefault(false),
            fileNames = fileNames,
            error = null,
            yuvReceivedFrames = job.optNullableInt("yuvReceivedFrames"),
            yuvPersistedFrames = job.optNullableInt("yuvPersistedFrames"),
            yuvFailedFrames = job.optNullableInt("yuvFailedFrames"),
            yuvDroppedFrames = job.optNullableInt("yuvDroppedFrames"),
            yuvCompletedResults = job.optNullableInt("yuvCompletedResults"),
            yuvFirstWorkerFailureClass = job.optNonBlankString("yuvFirstWorkerFailureClass"),
            yuvFirstWorkerFailureMessage = job.optNonBlankString("yuvFirstWorkerFailureMessage"),
            yuvFirstWorkerFailureFrameIndex = job.optNullableInt("yuvFirstWorkerFailureFrameIndex"),
            yuvFirstWorkerFailureRootCauseClass = job.optNonBlankString("yuvFirstWorkerFailureRootCauseClass"),
            yuvFirstWorkerFailureRootCauseMessage = job.optNonBlankString("yuvFirstWorkerFailureRootCauseMessage"),
            yuvFirstWorkerFailureStage = job.optNonBlankString("yuvFirstWorkerFailureStage"),
            yuvQueuedWork = job.optNullableInt("yuvQueuedWork"),
            yuvInFlightWork = job.optNullableInt("yuvInFlightWork"),
            yuvBufferedFrames = job.optNullableInt("yuvBufferedFrames"),
            yuvReservedAdoptionCount = job.optNullableInt("yuvReservedAdoptionCount"),
            rawPublicExportAttemptStatus = job.optNonBlankString("rawPublicExportAttemptStatus"),
            rawPublicExportAttemptError = job.optNonBlankString("rawPublicExportAttemptError"),
            rawPublicExportAttemptAt = job.optNullableLong("rawPublicExportAttemptAt"),
            exportAttemptedFormats = job.optJSONArray("exportAttemptedFormats").toStringList(),
            exportCandidateFailureReasons = job.optJSONArray("exportCandidateFailureReasons").toStringList(),
            processingArtifactJournalCount = journalScan?.validJournals?.size ?: 0,
            processingArtifactJournalStates = processingArtifactJournalStates,
            processingArtifactJournalFinalNames = processingArtifactJournalFinalNames,
            processingArtifactInvalidJournalCount = journalScan?.invalidFiles?.size ?: 0,
            captureTiming = runCatching {
                job.optJSONObject("captureTiming")?.let(HardwareE2ECaptureTiming::fromCaptureTimingJson)
            }.getOrNull(),
            backgroundStageTimings = job.optJSONObject("backgroundStageTimings").toLongMap()
        )
    }

    companion object {
        internal fun forTest(
            reportDirectory: File,
            environment: HardwareE2EEnvironment,
            jobFinder: () -> List<File> = { emptyList() }
        ): HardwareE2ERunRecorder = HardwareE2ERunRecorder(
            reportDirectory = reportDirectory,
            environment = environment,
            jobFinder = jobFinder
        )

        fun forContext(context: Context): HardwareE2ERunRecorder =
            HardwareE2ERunRecorder(
                reportDirectory = HardwareE2EReportStore.directory(context),
                environment = HardwareE2EEnvironment.fromContext(context),
                jobFinder = { findKeplerJobDirectories(context) }
            )
    }
}

internal data class HardwareE2EEnvironment(
    val runtimeSessionId: String,
    val processStartTimestamp: Long,
    val appPackage: String,
    val appVersion: String,
    val debugBuild: Boolean,
    val androidSdk: Int,
    val manufacturer: String,
    val deviceModel: String,
    val buildFingerprint: String
) {
    companion object {
        fun fromContext(context: Context): HardwareE2EEnvironment = HardwareE2EEnvironment(
            runtimeSessionId = KeplerRuntimeSession.id,
            processStartTimestamp = KeplerRuntimeSession.startedAt,
            appPackage = context.packageName,
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }.getOrDefault("unknown"),
            debugBuild = BuildConfig.DEBUG,
            androidSdk = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            buildFingerprint = Build.FINGERPRINT
        )
    }
}
