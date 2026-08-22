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
    val captureResourcesSettled: Boolean = true
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
    val processingArtifactInvalidJournalCount: Int = 0
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
            yuvFirstWorkerFailureClass = json.optNullableString("yuvFirstWorkerFailureClass"),
            yuvFirstWorkerFailureMessage = json.optNullableString("yuvFirstWorkerFailureMessage"),
            yuvFirstWorkerFailureFrameIndex = json.optNullableInt("yuvFirstWorkerFailureFrameIndex"),
            yuvFirstWorkerFailureRootCauseClass = json.optNullableString("yuvFirstWorkerFailureRootCauseClass"),
            yuvFirstWorkerFailureRootCauseMessage = json.optNullableString("yuvFirstWorkerFailureRootCauseMessage"),
            yuvFirstWorkerFailureStage = json.optNullableString("yuvFirstWorkerFailureStage"),
            yuvQueuedWork = json.optNullableInt("yuvQueuedWork"),
            yuvInFlightWork = json.optNullableInt("yuvInFlightWork"),
            yuvBufferedFrames = json.optNullableInt("yuvBufferedFrames"),
            yuvReservedAdoptionCount = json.optNullableInt("yuvReservedAdoptionCount"),
            rawPublicExportAttemptStatus = json.optNullableString("rawPublicExportAttemptStatus"),
            rawPublicExportAttemptError = json.optNullableString("rawPublicExportAttemptError"),
            rawPublicExportAttemptAt = json.optNullableLong("rawPublicExportAttemptAt"),
            exportAttemptedFormats = json.optJSONArray("exportAttemptedFormats").toStringList(),
            exportCandidateFailureReasons = json.optJSONArray("exportCandidateFailureReasons").toStringList(),
            processingArtifactJournalCount = json.optInt("processingArtifactJournalCount", 0),
            processingArtifactJournalStates = json.optJSONArray("processingArtifactJournalStates").toStringList(),
            processingArtifactJournalFinalNames = json.optJSONArray("processingArtifactJournalFinalNames").toStringList(),
            processingArtifactInvalidJournalCount = json.optInt("processingArtifactInvalidJournalCount", 0)
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
    val finalJob: HardwareE2EJobSummary?,
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
        finalJob?.let { put("finalJob", it.toJson()) }
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
                                    captureResourcesSettled = event.optBoolean("captureResourcesSettled", true)
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
                finalJob = json.optJSONObject("finalJob")?.let(HardwareE2EJobSummary::fromJson),
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

private fun JSONObject.optNullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optNullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

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
    private var current: HardwareE2ERunReport? = null
    private var startedAtNanos: Long = 0L
    private var baselineJobPaths: Set<String> = emptySet()
    private var baselineSnapshotFailure: String? = null

    fun start(scenario: HardwareE2ERunScenario): String? {
        if (!enabled) return null
        synchronized(lock) {
            current?.takeIf {
                (it.terminalEvent == null && it.status == HardwareE2EClassification.INCOMPLETE) ||
                    (it.terminalEvent != null && it.finalJob == null && it.failure == null)
            }?.let { return null }
        }
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
            current = report
            startedAtNanos = System.nanoTime()
            baselineJobPaths = baseline.getOrDefault(emptySet())
            baselineSnapshotFailure = baseline.exceptionOrNull()?.message
        }
        recordCheckpoint("RUN_STARTED", null, null)
        return runId
    }

    fun recordEvent(event: CameraPipelineEvent) {
        val checkpoint = when (event) {
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
        val record = HardwareE2EEventRecord(
            checkpoint = checkpoint,
            eventType = event.javaClass.simpleName,
            elapsedMs = elapsedMillis(),
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
            captureResourcesSettled = (event as? CameraPipelineEvent.Terminal)?.captureResourcesSettled ?: true
        )
        mutate { report ->
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
                recordCheckpoint("PUBLIC_OUTPUT_COMMITTED", null, null)
            }
            if (event.captureResourcesSettled) {
                recordCheckpoint("OWNER_SETTLED", null, null)
            }
            finalizeAfterTerminal(currentRunId())
        }
    }

    fun recordCheckpoint(checkpoint: String, jobDirectory: File?, message: String?) {
        mutate { report ->
            val record = HardwareE2EEventRecord(
                checkpoint = checkpoint,
                eventType = "checkpoint",
                elapsedMs = elapsedMillis(),
                wallClockTimestamp = System.currentTimeMillis(),
                generation = 0L,
                requestedFrames = 0,
                savedFrames = 0,
                receivedImages = 0,
                completedResults = 0,
                message = message
            )
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

    fun awaitIdle(timeoutMillis: Long = 5_000L): Boolean {
        val marker = writer.submit { Unit }
        return runCatching { marker.get(timeoutMillis, TimeUnit.MILLISECONDS); true }.getOrDefault(false)
    }

    fun close() {
        writer.shutdown()
    }

    private fun mutate(transform: (HardwareE2ERunReport) -> HardwareE2ERunReport) {
        if (!enabled) return
        val snapshot = synchronized(lock) {
            current = current?.let(transform)
            current
        } ?: return
        enqueuePersist(snapshot)
    }

    private fun finalizeAfterTerminal(runId: String?) {
        if (runId == null) return
        writer.execute {
            val reportBefore = synchronized(lock) { current?.takeIf { it.runId == runId } } ?: return@execute
            val correlation = correlateJob(reportBefore)
            synchronized(lock) {
                val report = current?.takeIf { it.runId == runId } ?: return@execute
                val decision = classify(report, correlation)
                current = report.copy(
                    latestJobDirectory = correlation.summary?.jobDirectory ?: report.latestJobDirectory,
                    finalJob = correlation.summary,
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
                persistNow(current!!)
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

    private fun correlateJob(report: HardwareE2ERunReport): JobCorrelationResult {
        baselineSnapshotFailure?.let {
            return JobCorrelationResult(
                HardwareE2EJobCorrelation.NONE,
                null,
                "baseline job snapshot failed: $it",
                0
            )
        }
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
            val latest = File(reportDirectory, "latest.json")
            val latestTemp = File(reportDirectory, "latest.json.tmp")
            latestTemp.writeText(HardwareE2EReportCodec.encode(report))
            if (!latestTemp.renameTo(latest)) {
                latest.delete()
                check(latestTemp.renameTo(latest)) { "could not publish latest hardware report" }
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

    private fun elapsedMillis(): Long =
        if (startedAtNanos == 0L) 0L else ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

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
                "rawWidth", "rawHeight", "rowStride", "pixelStride", "rawSizeSource"
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
            yuvFirstWorkerFailureClass = job.optNullableString("yuvFirstWorkerFailureClass"),
            yuvFirstWorkerFailureMessage = job.optNullableString("yuvFirstWorkerFailureMessage"),
            yuvFirstWorkerFailureFrameIndex = job.optNullableInt("yuvFirstWorkerFailureFrameIndex"),
            yuvFirstWorkerFailureRootCauseClass = job.optNullableString("yuvFirstWorkerFailureRootCauseClass"),
            yuvFirstWorkerFailureRootCauseMessage = job.optNullableString("yuvFirstWorkerFailureRootCauseMessage"),
            yuvFirstWorkerFailureStage = job.optNullableString("yuvFirstWorkerFailureStage"),
            yuvQueuedWork = job.optNullableInt("yuvQueuedWork"),
            yuvInFlightWork = job.optNullableInt("yuvInFlightWork"),
            yuvBufferedFrames = job.optNullableInt("yuvBufferedFrames"),
            yuvReservedAdoptionCount = job.optNullableInt("yuvReservedAdoptionCount"),
            rawPublicExportAttemptStatus = job.optNullableString("rawPublicExportAttemptStatus"),
            rawPublicExportAttemptError = job.optNullableString("rawPublicExportAttemptError"),
            rawPublicExportAttemptAt = job.optNullableLong("rawPublicExportAttemptAt"),
            exportAttemptedFormats = job.optJSONArray("exportAttemptedFormats").toStringList(),
            exportCandidateFailureReasons = job.optJSONArray("exportCandidateFailureReasons").toStringList(),
            processingArtifactJournalCount = journalScan?.validJournals?.size ?: 0,
            processingArtifactJournalStates = processingArtifactJournalStates,
            processingArtifactJournalFinalNames = processingArtifactJournalFinalNames,
            processingArtifactInvalidJournalCount = journalScan?.invalidFiles?.size ?: 0
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
