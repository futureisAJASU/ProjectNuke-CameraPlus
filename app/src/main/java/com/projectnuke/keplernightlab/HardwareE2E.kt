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
    val requestedRoute: String? = null
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
    val status: String,
    val processStatus: String,
    val exportStatus: String,
    val exportVerified: Boolean,
    val requestedFrames: Int,
    val attemptedFrames: Int,
    val savedFrames: Int,
    val receivedImages: Int,
    val completedResults: Int,
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
    val error: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("jobDirectory", jobDirectory)
        put("readable", readable)
        put("jobType", jobType)
        put("status", status)
        put("processStatus", processStatus)
        put("exportStatus", exportStatus)
        put("exportVerified", exportVerified)
        put("requestedFrames", requestedFrames)
        put("attemptedFrames", attemptedFrames)
        put("savedFrames", savedFrames)
        put("receivedImages", receivedImages)
        put("completedResults", completedResults)
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
        error?.let { put("error", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): HardwareE2EJobSummary = HardwareE2EJobSummary(
            jobDirectory = json.optString("jobDirectory"),
            readable = json.optBoolean("readable"),
            jobType = json.optString("jobType"),
            status = json.optString("status"),
            processStatus = json.optString("processStatus"),
            exportStatus = json.optString("exportStatus"),
            exportVerified = json.optBoolean("exportVerified"),
            requestedFrames = json.optInt("requestedFrames"),
            attemptedFrames = json.optInt("attemptedFrames"),
            savedFrames = json.optInt("savedFrames"),
            receivedImages = json.optInt("receivedImages"),
            completedResults = json.optInt("completedResults"),
            failedCaptures = json.optInt("failedCaptures"),
            partialCapture = json.optBoolean("partialCapture"),
            cleanupType = json.optString("cleanupType"),
            cameraId = json.optString("cameraId"),
            physicalCameraId = json.optString("physicalCameraId"),
            requestedPhysicalCameraId = json.optString("requestedPhysicalCameraId"),
            dngSidecarSaved = if (json.has("dngSidecarSaved")) json.optBoolean("dngSidecarSaved") else null,
            dngSidecarSkipReason = json.optString("dngSidecarSkipReason"),
            dngSidecarStatuses = json.optJSONArray("dngSidecarStatuses").toStringList(),
            frameManifestCount = json.optInt("frameManifestCount"),
            rawMetadata = json.optJSONObject("rawMetadata").toStringMap(),
            selectedRoute = json.optString("selectedRoute"),
            actualRoute = json.optString("actualRoute"),
            processingTiming = json.optJSONObject("processingTiming").toLongMap(),
            memoryFields = json.optJSONObject("memoryFields").toStringMap(),
            activeOperationId = json.optString("activeOperationId"),
            activeOperationKind = json.optString("activeOperationKind"),
            activeRuntimeSessionId = json.optString("activeRuntimeSessionId"),
            terminalOperationId = json.optString("terminalOperationId"),
            liveOperationRegistered = json.optBoolean("liveOperationRegistered"),
            fileNames = json.optJSONArray("fileNames").toStringList(),
            error = json.optString("error").takeIf { it.isNotBlank() }
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
    val failure: String? = null
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
                    requestedRoute = scenarioJson.optString("requestedRoute").takeIf { it.isNotBlank() }
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
                failure = json.optString("failure").takeIf { it.isNotBlank() }
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

    fun readReports(context: Context): List<HardwareE2ERunReport> =
        directory(context).listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" && it.name != "latest.json" }
            .sortedByDescending(File::lastModified)
            .mapNotNull { file -> runCatching { HardwareE2EReportCodec.decode(file.readText()) }.getOrNull() }

    fun readLatest(context: Context): HardwareE2ERunReport? =
        runCatching {
            latestFile(context).takeIf { it.isFile }?.readText()?.let(HardwareE2EReportCodec::decode)
        }.getOrNull()
}

internal class HardwareE2ERunRecorder private constructor(
    private val reportDirectory: File,
    private val environment: HardwareE2EEnvironment,
    private val jobFinder: () -> List<File>
) {
    private val lock = Any()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KeplerHardwareE2ERecorder").apply { isDaemon = true }
    }
    private var current: HardwareE2ERunReport? = null
    private var startedAtNanos: Long = 0L

    fun start(scenario: HardwareE2ERunScenario): String {
        val now = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
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
            status = HardwareE2EClassification.INCOMPLETE
        )
        synchronized(lock) {
            current = report
            startedAtNanos = System.nanoTime()
        }
        recordCheckpoint("APP_STARTED", null, null)
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
        mutate { it.copy(status = HardwareE2EClassification.SKIPPED_UNSUPPORTED, failure = reason) }
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
        val snapshot = synchronized(lock) {
            current = current?.let(transform)
            current
        } ?: return
        enqueuePersist(snapshot)
    }

    private fun finalizeAfterTerminal(runId: String?) {
        if (runId == null) return
        writer.execute {
            val job = runCatching {
                jobFinder()
                    .filter { it.isDirectory }
                    .maxByOrNull { it.lastModified() }
                    ?.let(::readJobSummary)
            }.getOrElse { error ->
                Log.w(HARDWARE_E2E_TAG, "job enrichment failed", error)
                null
            }
            synchronized(lock) {
                val report = current?.takeIf { it.runId == runId } ?: return@execute
                val classification = when {
                    report.terminalEvent == CameraPipelineEvent.Terminal.Kind.COMPLETE.name && job?.readable == true -> HardwareE2EClassification.PASS
                    report.terminalEvent == CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL.name && job?.readable == true -> HardwareE2EClassification.PASS
                    report.terminalEvent != null -> HardwareE2EClassification.FAIL
                    else -> HardwareE2EClassification.INCOMPLETE
                }
                current = report.copy(
                    latestJobDirectory = job?.jobDirectory ?: report.latestJobDirectory,
                    finalJob = job,
                    status = classification,
                    failure = job?.error ?: report.failure
                )
                persistNow(current!!)
            }
        }
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
                ?.sortedByDescending { it.lastModified() }
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
                    status = "",
                    processStatus = "",
                    exportStatus = "",
                    exportVerified = false,
                    requestedFrames = 0,
                    attemptedFrames = 0,
                    savedFrames = 0,
                    receivedImages = 0,
                    completedResults = 0,
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
        return HardwareE2EJobSummary(
            jobDirectory = jobDir.absolutePath,
            readable = true,
            jobType = job.optString("jobType"),
            status = job.optString("status"),
            processStatus = job.optString("processStatus"),
            exportStatus = job.optString("exportStatus"),
            exportVerified = job.optBoolean("exportVerified", false),
            requestedFrames = job.optInt("requestedFrames", 0),
            attemptedFrames = job.optInt("attemptedFrames", 0),
            savedFrames = job.optInt("savedFrames", 0),
            receivedImages = job.optInt("receivedImages", 0),
            completedResults = job.optInt("completedResults", 0),
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
            liveOperationRegistered = KeplerJobMetadata.findOperationLease(jobDir) != null,
            fileNames = NoFollowFileSystem.requireDirectChildren(jobDir)
                .filter { NoFollowFileSystem.isRealFile(it.toPath()) }
                .map(File::getName)
                .sorted(),
            error = null
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
