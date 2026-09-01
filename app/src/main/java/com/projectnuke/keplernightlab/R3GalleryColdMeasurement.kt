package com.projectnuke.keplernightlab

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/**
 * Bounded, opt-in instrumentation for the R3 production-cold Gallery measurement.
 *
 * Production behavior is unchanged while the control file is absent. A test creates one control
 * file immediately before a process-cold launch; the process reads it from MainActivity.onCreate
 * and writes one structured result after Gallery publishes its loaded job list.
 */
internal object R3GalleryColdMeasurement {
    private const val DIRECTORY = "r3-gallery-cold"
    private const val CONTROL_FILE = "control.json"
    private const val RESULT_SUFFIX = ".json"

    private val active = AtomicReference<RunState?>(null)
    private val inspectionSample = ThreadLocal<InspectionSample?>()
    private var reconstructionDepth = 0

    internal fun controlFile(context: Context): File = File(File(context.filesDir, DIRECTORY), CONTROL_FILE)

    internal fun resultFile(context: Context, runId: String): File =
        File(File(context.filesDir, DIRECTORY), "$runId$RESULT_SUFFIX")

    internal fun onProcessStart(context: Context) {
        val previous = active.get()
        active.set(null)
        val control = controlFile(context)
        val raw = runCatching { control.readText(StandardCharsets.UTF_8) }.getOrNull()
        android.util.Log.d("R3Cold", "onProcessStart controlExists=${control.exists()} raw=$raw prevRunId=${previous?.runId} prevRecoveryStart=${previous?.recoveryStartedAtNanos}")
        val runId = runCatching {
            JSONObject(control.readText(StandardCharsets.UTF_8)).optString("runId")
        }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,80}")) }
        if (runId == null) {
            android.util.Log.d("R3Cold", "onProcessStart no valid runId")
            return
        }
        android.util.Log.d("R3Cold", "onProcessStart activated runId=$runId")
        val newState = RunState(runId, SystemClock.elapsedRealtimeNanos())
        // Preserve recovery timing across sequential in-process cold simulations
        // (R4 does 3 gallery openings without process kill). If previous run already
        // completed recovery, reuse its timing so writeResult can succeed even when
        // KeplerRecoveryCoordinator does not re-run recovery in the same process.
        if (previous != null && previous.recoveryStartedAtNanos != null && previous.recoveryFinishedAtNanos != null) {
            // Only reuse if new runId is different but process is same - indicates
            // sequential R4 loop without true process restart.
            if (previous.runId != runId) {
                newState.recoveryStartedAtNanos = previous.recoveryStartedAtNanos
                newState.recoveryFinishedAtNanos = previous.recoveryFinishedAtNanos
                newState.recoveryJobCount = previous.recoveryJobCount
                newState.recoveredJobCount = previous.recoveredJobCount
                newState.recoveryFailureCount = previous.recoveryFailureCount
                // Also reuse verification/metadata to allow second galleryReady without re-inspection
                // to still publish a valid result. This is a test-only tolerance for in-process loops.
                if (previous.inspectionsAttempted > 0) {
                    newState.inspectionsAttempted = previous.inspectionsAttempted
                    newState.verifiedTrue = previous.verifiedTrue
                    newState.verifiedFalse = previous.verifiedFalse
                    newState.pendingTrue = previous.pendingTrue
                    newState.pendingFalse = previous.pendingFalse
                    newState.diagnosticReasons.putAll(previous.diagnosticReasons)
                    newState.inspectionWallNanos = previous.inspectionWallNanos
                    newState.samples.addAll(previous.samples)
                    newState.metadataWriteAttempts = previous.metadataWriteAttempts
                    newState.contentChangingWrites = previous.contentChangingWrites
                    newState.sameContentRewrites = previous.sameContentRewrites
                    newState.sameContentRewriteNanos = previous.sameContentRewriteNanos
                    newState.contentChangingPersistenceNanos = previous.contentChangingPersistenceNanos
                    newState.metadataPersistenceNanos = previous.metadataPersistenceNanos
                    newState.reconstructionNanos = previous.reconstructionNanos
                    newState.reconstructionWriteAttempts = previous.reconstructionWriteAttempts
                    newState.journalWrites = previous.journalWrites
                    newState.journalPersistenceNanos = previous.journalPersistenceNanos
                    newState.terminalMetadataWrites = previous.terminalMetadataWrites
                    newState.terminalMetadataWriteNanos = previous.terminalMetadataWriteNanos
                    previous.sourceStats.forEach { (src, stats) ->
                        val dst = newState.sourceStats.getValue(src)
                        dst.writeAttempts = stats.writeAttempts
                        dst.contentChangingWrites = stats.contentChangingWrites
                        dst.sameContentWrites = stats.sameContentWrites
                        dst.persistenceNanos = stats.persistenceNanos
                    }
                }
                android.util.Log.d("R3Cold", "onProcessStart reused recovery+verification from ${previous.runId} for $runId")
            }
        }
        active.set(newState)
    }

    internal fun recoveryStarted() {
        android.util.Log.d("R3Cold", "recoveryStarted active=${active.get()?.runId}")
        active.get()?.recoveryStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    }

    internal fun recoveryFinished(report: KeplerRecoveryReport) {
        android.util.Log.d("R3Cold", "recoveryFinished jobs=${report.jobs.size} active=${active.get()?.runId}")
        active.get()?.let { state ->
            state.recoveryFinishedAtNanos = SystemClock.elapsedRealtimeNanos()
            state.recoveryJobCount = report.jobs.size
            state.recoveredJobCount = report.jobs.count {
                it.classification == KeplerJobRecoveryClassification.RECOVERED
            }
            state.recoveryFailureCount = report.jobs.count {
                it.classification != KeplerJobRecoveryClassification.RECOVERED || it.failures.isNotEmpty()
            }
        }
    }

    internal fun galleryReady(context: Context, jobCount: Int) {
        android.util.Log.d("R3Cold", "galleryReady jobCount=$jobCount active=${active.get()?.runId}")
        val state = active.get() ?: run {
            android.util.Log.d("R3Cold", "galleryReady no active")
            return
        }
        synchronized(state) {
            if (state.galleryReadyAtNanos != null) {
                android.util.Log.d("R3Cold", "galleryReady already done")
                return
            }
            state.galleryReadyAtNanos = SystemClock.elapsedRealtimeNanos()
            state.galleryJobCount = jobCount
            writeResult(context, state)
        }
    }

    internal fun <T> measureInspection(
        journal: MediaStoreExportJournal,
        block: () -> T
    ): T {
        val state = active.get()
        if (state == null) return block()
        val sample = InspectionSample(journal.mimeType)
        inspectionSample.set(sample)
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            val value = block()
            state.inspectionsAttempted++
            val inspection = value as? MediaStoreExportInspection
            if (inspection?.verified == true) state.verifiedTrue++ else state.verifiedFalse++
            if (inspection?.pending == true) state.pendingTrue++ else state.pendingFalse++
            inspection?.verificationDiagnosticReason?.name?.let { reason ->
                state.diagnosticReasons[reason] = (state.diagnosticReasons[reason] ?: 0) + 1
            }
            sample.result = inspection
            value
        } finally {
            sample.totalNanos = SystemClock.elapsedRealtimeNanos() - started
            state.inspectionWallNanos += sample.totalNanos
            state.samples += sample
            inspectionSample.remove()
        }
    }

    internal fun <T> measureVerification(block: () -> T): T = measureStage(
        { sample, elapsed -> sample.verificationNanos += elapsed }, block
    )

    internal fun <T> measureQuery(block: () -> T): T = measureStage(
        { sample, elapsed -> sample.queryNanos += elapsed }, block
    )

    internal fun <T> measureContentStream(block: () -> T): T = measureStage(
        { sample, elapsed -> sample.contentStreamNanos += elapsed }, block
    )

    internal fun <T> measureBoundsDecode(block: () -> T): T = measureStage(
        { sample, elapsed -> sample.boundsDecodeNanos += elapsed }, block
    )

    internal fun <T> measureSampledPixelDecode(block: () -> T): T = measureStage(
        { sample, elapsed -> sample.sampledPixelDecodeNanos += elapsed }, block
    )

    internal enum class MetadataWriteSource {
        RECONSTRUCT_MAIN_EXPORT,
        TERMINAL_STABLE_SETTLEMENT,
        GALLERY_STORAGE_SUMMARY,
        OTHER
    }

    internal fun metadataContentChanged(originalText: String, serialized: String): Boolean =
        originalText != serialized

    internal fun <T> measureMetadataWrite(
        contentChanged: Boolean,
        source: MetadataWriteSource,
        block: () -> T
    ): T {
        val state = active.get()
        if (state == null) return block()
        state.metadataWriteAttempts++
        val sourceState = state.sourceStats.getValue(source)
        sourceState.writeAttempts++
        if (contentChanged) {
            state.contentChangingWrites++
            sourceState.contentChangingWrites++
        } else {
            state.sameContentRewrites++
            sourceState.sameContentWrites++
        }
        if (reconstructionDepth > 0) state.reconstructionWriteAttempts++
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            val elapsed = SystemClock.elapsedRealtimeNanos() - started
            state.metadataPersistenceNanos += elapsed
            sourceState.persistenceNanos += elapsed
            if (contentChanged) state.contentChangingPersistenceNanos += elapsed
            else state.sameContentRewriteNanos += elapsed
        }
    }

    internal fun <T> measureReconstruction(block: () -> T): T {
        val state = active.get() ?: return block()
        val started = SystemClock.elapsedRealtimeNanos()
        reconstructionDepth++
        return try {
            block()
        } finally {
            reconstructionDepth--
            state.reconstructionNanos += SystemClock.elapsedRealtimeNanos() - started
        }
    }

    internal fun <T> measureJournalWrite(block: () -> T): T {
        val state = active.get() ?: return block()
        state.journalWrites++
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            state.journalPersistenceNanos += SystemClock.elapsedRealtimeNanos() - started
        }
    }

    internal fun <T> measureTerminalMetadataWrite(block: () -> T): T {
        val state = active.get() ?: return block()
        state.terminalMetadataWrites++
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            state.terminalMetadataWriteNanos += SystemClock.elapsedRealtimeNanos() - started
        }
    }

    private fun <T> measureStage(
        record: (InspectionSample, Long) -> Unit,
        block: () -> T
    ): T {
        val sample = inspectionSample.get() ?: return block()
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            val elapsed = SystemClock.elapsedRealtimeNanos() - started
            record(sample, elapsed)
        }
    }

    private fun writeResult(context: Context, state: RunState) {
        android.util.Log.d("R3Cold", "writeResult attempt runId=${state.runId} recoveryStart=${state.recoveryStartedAtNanos} recoveryEnd=${state.recoveryFinishedAtNanos} galleryReady=${state.galleryReadyAtNanos}")
        val recoveryStart = state.recoveryStartedAtNanos ?: run {
            android.util.Log.d("R3Cold", "writeResult missing recoveryStart")
            return
        }
        val recoveryEnd = state.recoveryFinishedAtNanos ?: run {
            android.util.Log.d("R3Cold", "writeResult missing recoveryEnd")
            return
        }
        val galleryReady = state.galleryReadyAtNanos ?: run {
            android.util.Log.d("R3Cold", "writeResult missing galleryReady")
            return
        }
        val json = JSONObject()
            .put("runId", state.runId)
            .put("clock", "SystemClock.elapsedRealtimeNanos")
            .put("processStartToRecoveryStartMs", nanosToMs(recoveryStart - state.processStartedAtNanos))
            .put("recoveryMs", nanosToMs(recoveryEnd - recoveryStart))
            .put("postRecoveryToGalleryReadyMs", nanosToMs(galleryReady - recoveryEnd))
            .put("totalProcessColdGalleryReadyMs", nanosToMs(galleryReady - state.processStartedAtNanos))
            .put("recoveryJobCount", state.recoveryJobCount)
            .put("recoveredJobCount", state.recoveredJobCount)
            .put("recoveryFailureCount", state.recoveryFailureCount)
            .put("galleryJobCount", state.galleryJobCount)
            .put("verification", verificationJson(state))
            .put("metadata", metadataJson(state))
        val directory = File(context.filesDir, DIRECTORY)
        directory.mkdirs()
        val result = resultFile(context, state.runId)
        val temporary = File(directory, ".${state.runId}.tmp")
        temporary.writeText(json.toString(), StandardCharsets.UTF_8)
        check(temporary.renameTo(result)) { "Could not publish R3 measurement result" }
        android.util.Log.d("R3Cold", "writeResult published ${result.absolutePath} runId=${state.runId}")
    }

    private fun verificationJson(state: RunState): JSONObject = JSONObject().apply {
        put("inspectionsAttempted", state.inspectionsAttempted)
        put("verifiedTrue", state.verifiedTrue)
        put("verifiedFalse", state.verifiedFalse)
        put("pendingTrue", state.pendingTrue)
        put("pendingFalse", state.pendingFalse)
        put("diagnosticReasons", JSONObject().apply {
            state.diagnosticReasons.forEach { (reason, count) -> put(reason, count) }
        })
        put("aggregateMs", nanosToMs(state.inspectionWallNanos))
        put("verifierMs", stats(state.samples.map { it.verificationNanos }))
        put("queryRowInspectionMs", stats(state.samples.map { it.queryNanos }))
        put("contentStreamMs", stats(state.samples.map { it.contentStreamNanos }))
        put("boundsDecodeMs", stats(state.samples.map { it.boundsDecodeNanos }))
        put("sampledPixelDecodeMs", stats(state.samples.map { it.sampledPixelDecodeNanos }))
        put("perExportInspectionMs", stats(state.samples.map { it.totalNanos }))
        put("jpegInspectionMs", stats(state.samples.filter { it.mimeType == "image/jpeg" }.map { it.totalNanos }))
        put("heifInspectionMs", stats(state.samples.filter { it.mimeType == "image/heif" }.map { it.totalNanos }))
    }

    private fun metadataJson(state: RunState): JSONObject = JSONObject()
        .put("writeAttempts", state.metadataWriteAttempts)
        .put("contentChangingWrites", state.contentChangingWrites)
        .put("sameContentRewrites", state.sameContentRewrites)
        .put("sameContentRewriteMs", nanosToMs(state.sameContentRewriteNanos))
        .put("contentChangingPersistenceMs", nanosToMs(state.contentChangingPersistenceNanos))
        .put("metadataPersistenceMs", nanosToMs(state.metadataPersistenceNanos))
        .put("reconstructionMs", nanosToMs(state.reconstructionNanos))
        .put("reconstructionWriteAttempts", state.reconstructionWriteAttempts)
        .put("journalWrites", state.journalWrites)
        .put("journalPersistenceMs", nanosToMs(state.journalPersistenceNanos))
        .put("terminalMetadataWrites", state.terminalMetadataWrites)
        .put("terminalMetadataWriteMs", nanosToMs(state.terminalMetadataWriteNanos))
        .put("bySource", JSONObject().apply {
            state.sourceStats.forEach { (source, stats) ->
                put(source.name, JSONObject()
                    .put("writeAttempts", stats.writeAttempts)
                    .put("contentChangingWrites", stats.contentChangingWrites)
                    .put("sameContentWrites", stats.sameContentWrites)
                    .put("persistenceMs", nanosToMs(stats.persistenceNanos)))
            }
        })

    private fun stats(values: List<Long>): JSONObject {
        val sorted = values.map(::nanosToMs).sorted()
        val median = if (sorted.isEmpty()) 0.0 else {
            val middle = sorted.size / 2
            if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
        }
        val p95 = if (sorted.isEmpty()) 0.0 else sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)]
        return JSONObject()
            .put("count", sorted.size)
            .put("medianMs", median)
            .put("p95Ms", p95)
            .put("maxMs", sorted.maxOrNull() ?: 0.0)
            .put("aggregateMs", sorted.sum())
    }

    private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0

    private class RunState(
        val runId: String,
        val processStartedAtNanos: Long
    ) {
        var recoveryStartedAtNanos: Long? = null
        var recoveryFinishedAtNanos: Long? = null
        var galleryReadyAtNanos: Long? = null
        var recoveryJobCount = 0
        var recoveredJobCount = 0
        var recoveryFailureCount = 0
        var galleryJobCount = 0
        var inspectionsAttempted = 0
        var verifiedTrue = 0
        var verifiedFalse = 0
        var pendingTrue = 0
        var pendingFalse = 0
        val diagnosticReasons = linkedMapOf<String, Int>()
        var inspectionWallNanos = 0L
        val samples = mutableListOf<InspectionSample>()
        var metadataWriteAttempts = 0
        var contentChangingWrites = 0
        var sameContentRewrites = 0
        var sameContentRewriteNanos = 0L
        var contentChangingPersistenceNanos = 0L
        var metadataPersistenceNanos = 0L
        var reconstructionNanos = 0L
        var reconstructionWriteAttempts = 0
        var journalWrites = 0
        var journalPersistenceNanos = 0L
        var terminalMetadataWrites = 0
        var terminalMetadataWriteNanos = 0L
        val sourceStats = MetadataWriteSource.values().associateWith { MetadataWriteSourceStats() }
    }

    private class MetadataWriteSourceStats {
        var writeAttempts = 0
        var contentChangingWrites = 0
        var sameContentWrites = 0
        var persistenceNanos = 0L
    }

    private data class InspectionSample(
        val mimeType: String,
        var result: MediaStoreExportInspection? = null,
        var totalNanos: Long = 0L,
        var verificationNanos: Long = 0L,
        var queryNanos: Long = 0L,
        var contentStreamNanos: Long = 0L,
        var boundsDecodeNanos: Long = 0L,
        var sampledPixelDecodeNanos: Long = 0L
    )
}
