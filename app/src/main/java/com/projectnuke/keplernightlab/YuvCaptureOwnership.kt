package com.projectnuke.keplernightlab

import android.media.Image
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// ── Serializable manifest entry ────────────────────────────────────
data class YuvFrameManifestEntry(
    val frameIndex: Int,
    val filename: String,
    val timestampNs: Long,
    val persisted: Boolean,
    val failure: String? = null
)

// ── Candidate ownership state ──────────────────────────────────────
internal enum class CandidateOwnership {
    UNSETTLED, ADOPTED, DISCARDED, QUARANTINED
}

internal data class CandidateOwnershipToken(
    val frameIndex: Int,
    val fileName: String,
    val timestampNs: Long,
    val candidate: File
) {
    @Volatile var ownership: CandidateOwnership = CandidateOwnership.UNSETTLED
}

// ── Adoption token for reservation → commit/rollback ───────────────
internal data class AdoptionToken(
    val reservedEntry: YuvFrameManifestEntry
)

// ── Owned direct YUV source abstraction ────────────────────────────
internal interface OwnedDirectYuvSource {
    val timestampNs: Long
    fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int)
    fun release()
}

internal class AndroidOwnedDirectYuvSource(
    private val image: Image,
    override val timestampNs: Long
) : OwnedDirectYuvSource {
    private val released = AtomicBoolean(false)

    override fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int) {
        encoder.encodeDirect(image, candidate, rotationDegrees)
    }

    override fun release() {
        if (released.compareAndSet(false, true)) {
            image.close()
        }
    }
}

internal class FakeOwnedDirectYuvSource : OwnedDirectYuvSource {
    @Volatile var released = false
    val encodeCount = java.util.concurrent.atomic.AtomicInteger(0)
    override val timestampNs: Long = 4321L

    override fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int) {
        encodeCount.incrementAndGet()
        candidate.writeBytes(PNG_1X1_BYTES)
    }

    override fun release() {
        released = true
    }

    companion object {
        val PNG_1X1_BYTES: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }
}

// ── YuvCaptureAccounting ───────────────────────────────────────────
internal open class YuvCaptureAccounting {
    private val lock = Any()
    private var received = 0
    private var buffered = 0
    private var persisted = 0
    private var failed = 0
    private var dropped = 0
    private val manifest = linkedMapOf<Int, YuvFrameManifestEntry>()
    private val reservedIndices = mutableSetOf<Int>()
    private val reservedFilenames = mutableSetOf<String>()

    fun receivedFrame() = synchronized(lock) { received++ }
    open fun bufferedFrame() = synchronized(lock) { buffered++ }
    fun releasedBufferedFrame() = synchronized(lock) {
        check(buffered > 0) { "bufferedFrames released more than once" }
        buffered--
    }
    open fun failedFrame() = synchronized(lock) { failed++ }
    fun droppedFrame() = synchronized(lock) { dropped++ }

    // Legacy / ColorFusion compat
    fun persistedFrame(entry: YuvFrameManifestEntry): Boolean = synchronized(lock) {
        if (!entry.persisted || manifest.containsKey(entry.frameIndex)
            || manifest.values.any { it.filename == entry.filename }) {
            return@synchronized false
        }
        manifest[entry.frameIndex] = entry
        persisted++
        true
    }

    fun tryReserveAdoption(entry: YuvFrameManifestEntry): AdoptionToken? = synchronized(lock) {
        if (manifest.containsKey(entry.frameIndex) ||
            manifest.values.any { it.filename == entry.filename } ||
            reservedIndices.contains(entry.frameIndex) ||
            reservedFilenames.contains(entry.filename)) {
            return@synchronized null
        }
        reservedIndices.add(entry.frameIndex)
        reservedFilenames.add(entry.filename)
        AdoptionToken(entry)
    }

    fun commitAdoption(token: AdoptionToken): Boolean = synchronized(lock) {
        val entry = token.reservedEntry
        if (!reservedIndices.remove(entry.frameIndex) || !reservedFilenames.remove(entry.filename)) {
            return@synchronized false
        }
        manifest[entry.frameIndex] = entry
        persisted++
        check(persisted == manifest.size) { "persisted=$persisted manifest=${manifest.size}" }
        true
    }

    fun rollbackAdoption(token: AdoptionToken) = synchronized(lock) {
        val entry = token.reservedEntry
        reservedIndices.remove(entry.frameIndex)
        reservedFilenames.remove(entry.filename)
    }

    fun snapshot(): YuvCaptureAccountingSnapshot = synchronized(lock) {
        check(persisted == manifest.size) { "persisted=$persisted manifest=${manifest.size}" }
        YuvCaptureAccountingSnapshot(
            receivedFrames = received,
            bufferedFrames = buffered,
            persistedFrames = persisted,
            failedFrames = failed,
            droppedFrames = dropped,
            manifest = manifest.values.sortedBy { it.frameIndex },
            reservedCount = reservedIndices.size
        )
    }
}

data class YuvCaptureAccountingSnapshot(
    val receivedFrames: Int,
    val bufferedFrames: Int,
    val persistedFrames: Int,
    val failedFrames: Int,
    val droppedFrames: Int,
    val manifest: List<YuvFrameManifestEntry>,
    val reservedCount: Int = 0
)

internal class YuvBufferReservations(private val limitBytes: Long) {
    private val retained = AtomicLong(0L)

    fun tryReserve(bytes: Long): Boolean {
        if (bytes <= 0L || bytes > limitBytes) return false
        while (true) {
            val current = retained.get()
            if (current > limitBytes - bytes) return false
            if (retained.compareAndSet(current, current + bytes)) return true
        }
    }

    fun release(bytes: Long) {
        if (bytes <= 0L) return
        while (true) {
            val current = retained.get()
            check(current >= bytes) { "reservation released more than once" }
            if (retained.compareAndSet(current, current - bytes)) return
        }
    }

    fun currentBytes(): Long = retained.get()
}

internal interface YuvImageAccess {
    fun timestampNs(): Long
    fun allocationBytes(): Long
    fun copy(frameIndex: Int): BufferedYuvFrame
    fun release()
}

internal class Camera2YuvImageAccess(private val image: Image) : YuvImageAccess {
    override fun timestampNs(): Long = image.timestamp
    override fun allocationBytes(): Long = actualYuvPlaneBytes(image)
    override fun copy(frameIndex: Int): BufferedYuvFrame = copyYuvFrameToMemory(image, frameIndex)
    override fun release() = image.close()
}

internal interface DirectYuvImageAccess : YuvImageAccess {
    fun takeImage(): Image?
}

internal class Camera2DirectYuvImageAccess(private val image: Image) : DirectYuvImageAccess {
    private var taken = false
    override fun timestampNs(): Long = image.timestamp
    override fun allocationBytes(): Long = 0L
    override fun copy(frameIndex: Int): BufferedYuvFrame = error("direct work does not copy")
    override fun release() { if (!taken) image.close() }
    override fun takeImage(): Image? {
        check(!taken) { "DirectYuvImageAccess.takeImage() called twice" }
        taken = true
        return image
    }
}

internal sealed interface DirectYuvWorkCreation {
    data class Accepted(val item: YuvPngWorkItem) : DirectYuvWorkCreation
    data class Failed(val cause: Throwable) : DirectYuvWorkCreation
}

internal fun createDirectYuvWork(
    frameIndex: Int,
    access: DirectYuvImageAccess,
    account: YuvCaptureAccounting,
    onRelease: (() -> Unit)? = null
): DirectYuvWorkCreation {
    val timestampNs = try {
        access.timestampNs()
    } catch (t: Throwable) {
        access.release()
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(t)
    }
    val image = try {
        access.takeImage()
    } catch (t: Throwable) {
        access.release()
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(t)
    }
    runCatching { access.release() }
    return DirectYuvWorkCreation.Accepted(
        YuvPngWorkItem.direct(frameIndex, timestampNs, image, onRelease)
    )
}

internal sealed interface BufferedYuvWorkCreation {
    data class Accepted(val item: YuvPngWorkItem) : BufferedYuvWorkCreation
    data object Rejected : BufferedYuvWorkCreation
    data class Failed(val cause: Throwable) : BufferedYuvWorkCreation
}

internal class YuvImageReleaseGuard(private val access: YuvImageAccess) {
    private val consumed = AtomicBoolean(false)
    fun releaseSafely() {
        if (consumed.compareAndSet(false, true)) runCatching { access.release() }
    }
}

internal fun createBufferedYuvWork(
    frameIndex: Int,
    access: YuvImageAccess,
    reservations: YuvBufferReservations,
    accounting: YuvCaptureAccounting,
    onRelease: (() -> Unit)? = null
): BufferedYuvWorkCreation {
    val guard = YuvImageReleaseGuard(access)
    var timestampNs = 0L
    var bytes = 0L
    var reserved = false
    var itemOwner = false
    try {
        timestampNs = access.timestampNs()
        bytes = access.allocationBytes()
        if (!reservations.tryReserve(bytes)) {
            accounting.droppedFrame()
            return BufferedYuvWorkCreation.Rejected
        }
        reserved = true
        val frame = access.copy(frameIndex)
        val item = YuvPngWorkItem.buffered(frameIndex, timestampNs, frame, bytes, reservations, accounting, onRelease)
        itemOwner = true
        return BufferedYuvWorkCreation.Accepted(item)
    } catch (oom: OutOfMemoryError) {
        throw oom
    } catch (t: Throwable) {
        accounting.failedFrame()
        return BufferedYuvWorkCreation.Failed(t)
    } finally {
        guard.releaseSafely()
        if (reserved && !itemOwner) {
            reservations.release(bytes)
        }
    }
}

internal class YuvPngWorkItem private constructor(
    val frameIndex: Int,
    val timestampNs: Long,
    private var image: Image?,
    private var directSource: OwnedDirectYuvSource?,
    private var buffered: BufferedYuvFrame?,
    private val retainedBytes: Long,
    private val reservations: YuvBufferReservations?,
    private val onRelease: (() -> Unit)?
) {
    private val released = AtomicBoolean(false)
    private val bufferedReleased = AtomicBoolean(false)

    fun imageForEncoding(): Image? = image
    fun directSourceForEncoding(): OwnedDirectYuvSource? = directSource
    fun bufferedForEncoding(): BufferedYuvFrame? = buffered

    fun settleBufferedAccounting(accounting: YuvCaptureAccounting) {
        if (retainedBytes > 0L && bufferedReleased.compareAndSet(false, true)) {
            reservations?.release(retainedBytes)
            accounting.releasedBufferedFrame()
        }
    }

    fun dispose(accounting: YuvCaptureAccounting? = null) {
        if (!released.compareAndSet(false, true)) return
        val img = image; image = null
        val src = directSource; directSource = null
        val buf = buffered; buffered = null
        try {
            img?.close()
            src?.release()
        } finally {
            if (accounting != null) settleBufferedAccounting(accounting)
            onRelease?.invoke()
        }
    }

    companion object {
        fun direct(frameIndex: Int, timestampNs: Long, image: Image?, onRelease: (() -> Unit)? = null): YuvPngWorkItem {
            return YuvPngWorkItem(frameIndex, timestampNs, image, null, null, 0L, null, onRelease)
        }
        fun buffered(
            frameIndex: Int, timestampNs: Long, frame: BufferedYuvFrame,
            retainedBytes: Long, reservations: YuvBufferReservations,
            accounting: YuvCaptureAccounting, onRelease: (() -> Unit)? = null
        ): YuvPngWorkItem {
            val item = YuvPngWorkItem(frameIndex, timestampNs, null, null, frame, retainedBytes, reservations, onRelease)
            accounting.bufferedFrame()
            return item
        }
        internal fun ownedForTest(onRelease: () -> Unit): YuvPngWorkItem {
            return YuvPngWorkItem(-1, 0L, null, null, null, 0L, null, onRelease)
        }
        internal fun bufferedForTest(
            frameIndex: Int, timestampNs: Long, retainedBytes: Long,
            reservations: YuvBufferReservations, accounting: YuvCaptureAccounting,
            onRelease: (() -> Unit)? = null
        ): YuvPngWorkItem {
            val f = BufferedYuvFrame(frameIndex, timestampNs, 1, 1, ByteArray(0), ByteArray(0), ByteArray(0), 1, 1, 1, 1, 1, 1)
            val item = YuvPngWorkItem(frameIndex, timestampNs, null, null, f, retainedBytes, reservations, onRelease)
            accounting.bufferedFrame()
            return item
        }
    }
}

internal interface YuvPngEncoder {
    fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int)
    fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int)
}

internal fun interface YuvCandidateCommitter {
    fun commit(candidate: File, finalFile: File)
}

internal class YuvPngWorkProcessor(
    private val encoder: YuvPngEncoder,
    private val committer: YuvCandidateCommitter
) {
    fun encode(item: YuvPngWorkItem, candidate: File, rotationDegrees: Int) {
        val source = item.directSourceForEncoding()
        if (source != null) { source.encodeTo(encoder, candidate, rotationDegrees); return }
        val image = item.imageForEncoding()
        if (image != null) { encoder.encodeDirect(image, candidate, rotationDegrees); return }
        val buffered = item.bufferedForEncoding() ?: error("YUV work item has no owned source")
        encoder.encodeBuffered(buffered, candidate, rotationDegrees)
    }

    fun commit(candidate: File, finalFile: File) = committer.commit(candidate, finalFile)
}



internal class DisposableYuvTask(
    val item: YuvPngWorkItem,
    private val accounting: YuvCaptureAccounting,
    private val body: () -> Unit
) : DisposableCaptureTask {
    override fun run() = body()
    override fun dispose() = item.dispose(accounting)
}

// ═══ Cleanup coordinator ═══════════════════════════════════════════════

internal enum class CleanupPhase { NOT_STARTED, IN_PROGRESS, COMPLETED }

internal data class YuvCleanupResult(
    val phase: CleanupPhase,
    val cleanupStarted: Boolean,
    val cleanupInitiationCount: Int,
    val ownerCloseRequested: Boolean,
    val workerShutdownRequested: Boolean,
    val totalDrainedRetainedItems: Int,
    val totalQueuedTasksRemoved: Int,
    val totalQueuedDisposableDisposalAttempts: Int,
    val totalQueuedDisposableDisposalsSucceeded: Int,
    val totalQueuedNonDisposableTasksRemoved: Int,
    val activeWorkersAtCleanupStart: Int,
    val currentRetainedItems: Int,
    val currentEncodingItems: Int,
    val currentSettlingItems: Int,
    val currentDrainingItems: Int,
    val currentBufferedFrames: Int,
    val currentReservedBytes: Long,
    val cleanupFailures: List<String>,
    val workerTaskDisposalFailures: List<String>,
    val workerRejectionNotificationFailures: List<String>
)

internal class YuvCleanupCoordinator(
    private val captureStateOwner: CaptureStateOwner,
    private val lifecycle: YuvBufferedLifecycle,
    private val accounting: YuvCaptureAccounting,
    private val reservations: YuvBufferReservations,
    private val boundedWorker: BoundedCaptureWorker
) {
    private data class CleanupState(
        val phase: CleanupPhase = CleanupPhase.NOT_STARTED,
        val ownerCloseRequested: Boolean = false,
        val workerShutdownRequested: Boolean = false,
        val drainedRetainedItems: Int = 0,
        val queuedTasksRemoved: Int = 0,
        val queuedDisposableDisposalAttempted: Int = 0,
        val queuedDisposableDisposalsSucceeded: Int = 0,
        val queuedNonDisposableTasksRemoved: Int = 0,
        val activeWorkersAtStart: Int = 0,
        val workerTaskDisposalFailures: List<String> = emptyList(),
        val workerRejectionNotificationFailures: List<String> = emptyList(),
        val failures: List<String> = emptyList()
    )

    private val stateRef = AtomicReference(CleanupState())

    private fun buildSnapshot(): YuvCleanupResult {
        val s = stateRef.get()
        val snap = accounting.snapshot()
        return YuvCleanupResult(
            phase = s.phase,
            cleanupStarted = s.phase != CleanupPhase.NOT_STARTED,
            cleanupInitiationCount = if (s.phase != CleanupPhase.NOT_STARTED) 1 else 0,
            ownerCloseRequested = s.ownerCloseRequested,
            workerShutdownRequested = s.workerShutdownRequested,
            totalDrainedRetainedItems = s.drainedRetainedItems,
            totalQueuedTasksRemoved = s.queuedTasksRemoved,
            totalQueuedDisposableDisposalAttempts = s.queuedDisposableDisposalAttempted,
            totalQueuedDisposableDisposalsSucceeded = s.queuedDisposableDisposalsSucceeded,
            totalQueuedNonDisposableTasksRemoved = s.queuedNonDisposableTasksRemoved,
            activeWorkersAtCleanupStart = s.activeWorkersAtStart,
            currentRetainedItems = lifecycle.retainedCount(),
            currentEncodingItems = lifecycle.encodingCount(),
            currentSettlingItems = lifecycle.settlingCount(),
            currentDrainingItems = lifecycle.drainingCount(),
            currentBufferedFrames = snap.bufferedFrames,
            currentReservedBytes = reservations.currentBytes(),
            // Copy failure collections: the published snapshot is immutable.
            cleanupFailures = s.failures.toList(),
            workerTaskDisposalFailures = s.workerTaskDisposalFailures.toList(),
            workerRejectionNotificationFailures = s.workerRejectionNotificationFailures.toList()
        )
    }

    /**
     * Runs the cleanup sequence exactly once.  Every safety stage has its own failure
     * boundary so one stage's failure never skips a later stage:
     *
     * 1. close CaptureStateOwner
     * 2. close/claim retained lifecycle items (closeAndDrainRetained)
     * 3. dispose each drained item independently, then finish each drain item
     *    independently (one failure never skips later items)
     * 4. request BoundedCaptureWorker shutdown
     * 5. merge worker cleanup failures
     * 6. publish COMPLETED state
     *
     * Every failure is recorded with its stage and item/frame identity where available.
     * A concurrent call observes IN_PROGRESS (cleanup not completed); a repeated call
     * after completion returns the same historical totals.
     */
    fun perform(): YuvCleanupResult {
        val mutableFailures = mutableListOf<String>()

        val prev = stateRef.getAndUpdate { current ->
            if (current.phase == CleanupPhase.NOT_STARTED) {
                current.copy(phase = CleanupPhase.IN_PROGRESS)
            } else {
                current
            }
        }
        if (prev.phase != CleanupPhase.NOT_STARTED) {
            return buildSnapshot()
        }

        val activeBeforeDrain = boundedWorker.activeCount()
        stateRef.getAndUpdate { it.copy(activeWorkersAtStart = activeBeforeDrain) }

        // Step 1: close owner (independent failure boundary)
        try {
            captureStateOwner.close()
            stateRef.getAndUpdate { it.copy(ownerCloseRequested = true) }
        } catch (t: Throwable) {
            mutableFailures.add("ownerClose: ${t.message}")
        }

        // Step 2: claim retained lifecycle items (independent failure boundary).
        // A failure here must NOT skip worker shutdown or final state publication.
        val drained = try {
            lifecycle.closeAndDrainRetained()
        } catch (t: Throwable) {
            mutableFailures.add("closeAndDrainRetained: ${t.message}")
            emptyList()
        }

        // Step 3: dispose each drained item independently, then finish each drain item
        // independently.  One disposal or finishDrain failure never skips later items.
        for (item in drained) {
            try {
                item.dispose(accounting)
            } catch (t: Throwable) {
                mutableFailures.add("drainDispose[${item.frameIndex}]: ${t.message}")
            }
            try {
                if (!lifecycle.finishDrain(item)) {
                    mutableFailures.add("drainFinish[${item.frameIndex}]: item not in DRAINING state")
                }
            } catch (t: Throwable) {
                mutableFailures.add("drainFinish[${item.frameIndex}]: ${t.message}")
            }
        }

        // Step 4: request worker shutdown (independent failure boundary)
        var workerReport: BoundedCaptureWorker.CleanupReport? = null
        try {
            workerReport = boundedWorker.shutdownNow()
        } catch (t: Throwable) {
            mutableFailures.add("workerShutdown: ${t.message}")
        }

        // Step 5: merge worker cleanup failures
        val report = workerReport
        if (report != null) {
            mutableFailures.addAll(report.taskDisposalFailures)
            mutableFailures.addAll(report.rejectionNotificationFailures)
        }

        // Step 6: publish COMPLETED state in one immutable snapshot
        stateRef.getAndUpdate { current ->
            current.copy(
                workerShutdownRequested = true,
                drainedRetainedItems = drained.size,
                queuedTasksRemoved = report?.queuedTasksRemoved ?: 0,
                queuedDisposableDisposalAttempted = report?.queuedDisposableTasksDisposalAttempted ?: 0,
                queuedDisposableDisposalsSucceeded = report?.queuedDisposableTasksDisposedSuccessfully ?: 0,
                queuedNonDisposableTasksRemoved = report?.queuedNonDisposableTasksRemoved ?: 0,
                workerTaskDisposalFailures = report?.taskDisposalFailures ?: emptyList(),
                workerRejectionNotificationFailures = report?.rejectionNotificationFailures ?: emptyList(),
                failures = mutableFailures.toList()
            )
        }
        stateRef.getAndUpdate { it.copy(phase = CleanupPhase.COMPLETED) }
        return buildSnapshot()
    }

    fun snapshot(): YuvCleanupResult = buildSnapshot()
}
