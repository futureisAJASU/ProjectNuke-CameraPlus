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

// ── Candidate ownership: file handle + exactly-once settlement ─────
internal enum class CandidateOwnership {
    UNSETTLED, ADOPTED, DISCARDED, QUARANTINED
}

/**
 * Result of a candidate file operation through the injectable [YuvCandidateFilesystem]
 * seam.  Every outcome is explicit so owner-side cleanup never depends on
 * runCatching/boolean delete conventions and deterministic JVM tests can simulate
 * every failure mode.
 */
internal enum class CandidateFileOperationResult {
    FILE_ABSENT, DELETED, DELETE_RETURNED_FALSE, DELETE_THREW, QUARANTINED, QUARANTINE_FAILED
}

/**
 * Injectable filesystem operator for candidate cleanup.  Implementations must never
 * throw; delete/quarantine outcomes are returned as explicit results.
 */
internal interface YuvCandidateFilesystem {
    fun delete(candidate: File): CandidateFileOperationResult
    fun quarantine(candidate: File): CandidateFileOperationResult
}

internal object RealYuvCandidateFilesystem : YuvCandidateFilesystem {
    override fun delete(candidate: File): CandidateFileOperationResult {
        if (!candidate.exists()) return CandidateFileOperationResult.FILE_ABSENT
        return try {
            if (candidate.delete()) CandidateFileOperationResult.DELETED
            else CandidateFileOperationResult.DELETE_RETURNED_FALSE
        } catch (t: Throwable) {
            CandidateFileOperationResult.DELETE_THREW
        }
    }

    override fun quarantine(candidate: File): CandidateFileOperationResult {
        if (!candidate.exists()) return CandidateFileOperationResult.FILE_ABSENT
        return try {
            if (candidate.renameTo(File(candidate.path + ".quarantined"))) {
                CandidateFileOperationResult.QUARANTINED
            } else {
                CandidateFileOperationResult.QUARANTINE_FAILED
            }
        } catch (t: Throwable) {
            CandidateFileOperationResult.QUARANTINE_FAILED
        }
    }
}

/**
 * Injectable fail-closed candidate validation seam (owner side, before reservation).
 * The default production verifier requires an existing, regular, readable file.
 */
internal fun interface YuvCandidateVerifier {
    fun verify(candidate: File, frameIndex: Int): Boolean
}

internal object RealYuvCandidateVerifier : YuvCandidateVerifier {
    override fun verify(candidate: File, frameIndex: Int): Boolean =
        candidate.exists() && candidate.isFile && candidate.canRead()
}

/**
 * Injectable fail-closed final-file verifier used AFTER a successful commit.  The
 * default production verifier requires a readable regular file carrying the PNG
 * signature; [frameIndex] allows deterministic per-frame test failure injection.
 */
internal fun interface YuvFinalFileVerifier {
    fun verify(finalFile: File, frameIndex: Int): Boolean
}

internal object RealYuvFinalFileVerifier : YuvFinalFileVerifier {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    override fun verify(finalFile: File, frameIndex: Int): Boolean {
        if (!finalFile.exists() || !finalFile.isFile || !finalFile.canRead()) return false
        return try {
            finalFile.inputStream().use { input ->
                val header = ByteArray(8)
                input.read(header) == 8 && header.contentEquals(PNG_SIGNATURE)
            }
        } catch (t: Throwable) {
            false
        }
    }
}

/**
 * Owner-side result of a candidate settlement.  [cleanupFailed] is true exactly when
 * the candidate could neither be deleted nor quarantined: the file remains and the
 * cleanup debt stays observable ([CandidateDisposalOutcome.failureDescription]).
 */
internal class CandidateDisposalOutcome(
    val finalState: CandidateOwnership,
    val deleteResult: CandidateFileOperationResult? = null,
    val quarantineResult: CandidateFileOperationResult? = null,
    val alreadySettled: Boolean = false
) {
    val cleanupFailed: Boolean
        get() = !alreadySettled && finalState == CandidateOwnership.QUARANTINED &&
            quarantineResult == CandidateFileOperationResult.QUARANTINE_FAILED

    fun failureDescription(frameIndex: Int, file: File): String? = when {
        alreadySettled -> null
        quarantineResult == CandidateFileOperationResult.QUARANTINE_FAILED ->
            "candidate cleanup debt frame=$frameIndex file=$file delete=$deleteResult quarantine=QUARANTINE_FAILED"
        else -> null
    }
}

/**
 * Exactly-once candidate ownership handle.  A candidate file is owned from creation
 * until it settles exactly one way: ADOPTED (owner committed it to a final PNG),
 * DISCARDED (cleanup removed it, or it was already absent), or QUARANTINED (cleanup
 * could not remove it; the debt stays observable).  Repeated settlement attempts are
 * idempotent and never perform a second file operation.
 */
internal class YuvCandidateHandle(
    val frameIndex: Int,
    val file: File
) {
    private val state = AtomicReference(CandidateOwnership.UNSETTLED)
    private val settling = AtomicBoolean(false)

    fun state(): CandidateOwnership = state.get()

    /** Exactly-once UNSETTLED -> ADOPTED (caller has committed the candidate). */
    fun adopt(): Boolean = state.compareAndSet(CandidateOwnership.UNSETTLED, CandidateOwnership.ADOPTED)

    /**
     * Exactly-once UNSETTLED -> DISCARDED/QUARANTINED settlement: the first caller
     * performs the file operation; concurrent/repeated callers observe the settled
     * state and never touch the file again.
     */
    fun discardOrQuarantine(filesystem: YuvCandidateFilesystem): CandidateDisposalOutcome {
        if (state.get() != CandidateOwnership.UNSETTLED) {
            return CandidateDisposalOutcome(state.get(), alreadySettled = true)
        }
        if (!settling.compareAndSet(false, true)) {
            // A concurrent settlement is in flight: observe it without touching the file.
            return CandidateDisposalOutcome(
                state.get(),
                alreadySettled = state.get() != CandidateOwnership.UNSETTLED
            )
        }
        try {
            if (state.get() != CandidateOwnership.UNSETTLED) {
                return CandidateDisposalOutcome(state.get(), alreadySettled = true)
            }
            val deleteResult = filesystem.delete(file)
            val finalState: CandidateOwnership
            val quarantineResult: CandidateFileOperationResult?
            when (deleteResult) {
                CandidateFileOperationResult.FILE_ABSENT,
                CandidateFileOperationResult.DELETED -> {
                    finalState = CandidateOwnership.DISCARDED
                    quarantineResult = null
                }
                else -> {
                    quarantineResult = filesystem.quarantine(file)
                    finalState = CandidateOwnership.QUARANTINED
                }
            }
            state.set(finalState)
            return CandidateDisposalOutcome(finalState, deleteResult, quarantineResult)
        } finally {
            settling.set(false)
        }
    }
}

// ── Adoption token: stateful exactly-once reservation → commit/rollback ──
internal enum class AdoptionTokenState { RESERVED, COMMITTED, ROLLED_BACK }

/**
 * Stateful adoption token created by [YuvCaptureAccounting.tryReserveAdoption].
 * Reservation alone never touches the manifest or persistedFrames; the frame index
 * and final filename become committed (manifest entry + persistedFrames++) via
 * exactly-one [commit], or are released via exactly-one [rollback].  Commit/rollback
 * CAS the internal state and are gated again by the accounting reservation sets, so
 * double settlement is rejected from any caller.
 */
internal class AdoptionToken internal constructor(
    val reservedEntry: YuvFrameManifestEntry,
    private val accounting: YuvCaptureAccounting
) {
    private val state = AtomicReference(AdoptionTokenState.RESERVED)

    fun state(): AdoptionTokenState = state.get()

    /** Exactly-once RESERVED -> COMMITTED.  False when the token was already settled. */
    fun commit(): Boolean {
        if (!state.compareAndSet(AdoptionTokenState.RESERVED, AdoptionTokenState.COMMITTED)) return false
        if (!accounting.commitAdoption(this)) {
            state.set(AdoptionTokenState.ROLLED_BACK)
            return false
        }
        return true
    }

    /** Exactly-once RESERVED -> ROLLED_BACK.  False when the token was already settled. */
    fun rollback(): Boolean {
        if (!state.compareAndSet(AdoptionTokenState.RESERVED, AdoptionTokenState.ROLLED_BACK)) return false
        accounting.rollbackAdoption(this)
        return true
    }
}

// ── Owned direct YUV source abstraction ────────────────────────────
internal interface OwnedDirectYuvSource {
    val timestampNs: Long
    fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int)
    fun release()
}

internal class AndroidOwnedDirectYuvSource(
    val image: Image,
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
        AdoptionToken(entry, this)
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

/**
 * Direct work creation with exactly-once ownership transfer:
 * [DirectYuvImageAccess] -> [OwnedDirectYuvSource] -> [YuvPngWorkItem] -> worker.
 *
 * Every failure path (timestamp access, takeImage throwing, takeImage returning null,
 * source-adapter construction, work-item construction) releases the Image exactly
 * once.  The adapter/item factories are injectable for deterministic JVM tests; the
 * production defaults wrap the real Camera2 Image in [AndroidOwnedDirectYuvSource].
 */
internal fun createDirectYuvWork(
    frameIndex: Int,
    access: DirectYuvImageAccess,
    account: YuvCaptureAccounting,
    onRelease: (() -> Unit)? = null,
    sourceFactory: (Image, Long) -> OwnedDirectYuvSource = { image, ts ->
        AndroidOwnedDirectYuvSource(image, ts)
    },
    itemFactory: (Int, Long, OwnedDirectYuvSource, (() -> Unit)?) -> YuvPngWorkItem = { i, ts, src, release ->
        YuvPngWorkItem.directOwned(i, ts, src, release)
    }
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
    if (image == null) {
        // takeImage() returning null is a failure: there is never a valid-null direct item.
        access.release()
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(
            NullPointerException("DirectYuvImageAccess.takeImage() returned null for frame $frameIndex")
        )
    }
    val source = try {
        sourceFactory(image, timestampNs)
    } catch (t: Throwable) {
        runCatching { image.close() }
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(t)
    }
    val item = try {
        itemFactory(frameIndex, timestampNs, source, onRelease)
    } catch (t: Throwable) {
        runCatching { source.release() }
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(t)
    }
    return DirectYuvWorkCreation.Accepted(item)
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

/**
 * Sealed owned-source model: exactly one source per work item.  Direct items own an
 * [OwnedDirectYuvSource] (production: [AndroidOwnedDirectYuvSource] wrapping the real
 * Camera2 Image); buffered items own the copied [BufferedYuvFrame].  Mixed or absent
 * sources are unrepresentable.
 */
internal sealed interface YuvOwnedSource {
    data class Direct(val source: OwnedDirectYuvSource) : YuvOwnedSource
    data class Buffered(val frame: BufferedYuvFrame) : YuvOwnedSource
}

/**
 * Explicit result of one [YuvPngWorkItem.dispose] attempt.  Every sub-settlement is
 * independent: a failure in one never skips the others, and repeated dispose is
 * idempotent (later calls return [disposalAttempted]=false and perform nothing).
 */
internal class YuvWorkDisposalOutcome(
    val disposalAttempted: Boolean,
    val sourceReleaseAttempted: Boolean,
    val sourceReleased: Boolean,
    val sourceReleaseFailure: Throwable? = null,
    val reservationReleaseAttempted: Boolean,
    val reservationReleased: Boolean,
    val reservationReleaseFailure: Throwable? = null,
    val bufferedAccountingReleased: Boolean,
    val bufferedAccountingFailure: Throwable? = null,
    val releaseObserverAttempted: Boolean,
    val releaseObserverCompleted: Boolean,
    val releaseObserverFailure: Throwable? = null
) {
    val failed: Boolean
        get() = sourceReleaseFailure != null || reservationReleaseFailure != null ||
            bufferedAccountingFailure != null || releaseObserverFailure != null

    fun failures(): List<Throwable> = listOfNotNull(
        sourceReleaseFailure, reservationReleaseFailure, bufferedAccountingFailure, releaseObserverFailure
    )

    /** True when every sub-settlement that was required for this item succeeded. */
    val isClean: Boolean
        get() = !failed &&
            (!sourceReleaseAttempted || sourceReleased) &&
            (!reservationReleaseAttempted || reservationReleased) &&
            (!releaseObserverAttempted || releaseObserverCompleted)

    companion object {
        fun notAttempted(): YuvWorkDisposalOutcome = YuvWorkDisposalOutcome(
            disposalAttempted = false,
            sourceReleaseAttempted = false,
            sourceReleased = false,
            reservationReleaseAttempted = false,
            reservationReleased = false,
            bufferedAccountingReleased = false,
            releaseObserverAttempted = false,
            releaseObserverCompleted = false
        )
    }
}

internal class YuvPngWorkItem private constructor(
    val frameIndex: Int,
    val timestampNs: Long,
    private val source: YuvOwnedSource,
    private val retainedBytes: Long,
    private val reservations: YuvBufferReservations?,
    private val onRelease: (() -> Unit)?
) {
    private val released = AtomicBoolean(false)
    private val bufferedReleased = AtomicBoolean(false)

    fun sourceForEncoding(): YuvOwnedSource = source

    /**
     * ColorFusion-compatible view: the underlying Image for a production direct item,
     * or null for buffered items and test-owned direct sources.  Never the mutable
     * ownership field it used to be — the sealed source is the single owner.
     */
    fun imageForEncoding(): Image? = (source as? YuvOwnedSource.Direct)
        ?.source
        ?.let { it as? AndroidOwnedDirectYuvSource }
        ?.image

    fun bufferedForEncoding(): BufferedYuvFrame? = (source as? YuvOwnedSource.Buffered)?.frame

    fun settleBufferedAccounting(accounting: YuvCaptureAccounting) {
        if (retainedBytes > 0L && bufferedReleased.compareAndSet(false, true)) {
            reservations?.release(retainedBytes)
            accounting.releasedBufferedFrame()
        }
    }

    /**
     * Exactly-once disposal with an explicit [YuvWorkDisposalOutcome]: the owned source
     * (direct release / buffered reservation + accounting) and the release observer are
     * each settled independently so a failure never skips other cleanup.  Repeated
     * calls return an outcome with [YuvWorkDisposalOutcome.disposalAttempted]=false.
     */
    fun dispose(accounting: YuvCaptureAccounting? = null): YuvWorkDisposalOutcome {
        if (!released.compareAndSet(false, true)) return YuvWorkDisposalOutcome.notAttempted()

        var sourceReleaseAttempted = false
        var sourceReleased = false
        var sourceReleaseFailure: Throwable? = null
        if (source is YuvOwnedSource.Direct) {
            sourceReleaseAttempted = true
            try {
                source.source.release()
                sourceReleased = true
            } catch (t: Throwable) {
                sourceReleaseFailure = t
            }
        }

        var reservationReleaseAttempted = false
        var reservationReleased = false
        var reservationReleaseFailure: Throwable? = null
        var bufferedAccountingReleased = false
        var bufferedAccountingFailure: Throwable? = null
        // P1-compatible: buffered accounting settlement requires the accounting handle;
        // dispose(null) is a pure release-observer path for direct items.
        if (accounting != null && source is YuvOwnedSource.Buffered &&
            retainedBytes > 0L && bufferedReleased.compareAndSet(false, true)) {
            reservationReleaseAttempted = true
            try {
                reservations?.release(retainedBytes)
                reservationReleased = true
            } catch (t: Throwable) {
                reservationReleaseFailure = t
            }
            try {
                accounting.releasedBufferedFrame()
                bufferedAccountingReleased = true
            } catch (t: Throwable) {
                bufferedAccountingFailure = t
            }
        }

        var releaseObserverAttempted = false
        var releaseObserverCompleted = false
        var releaseObserverFailure: Throwable? = null
        val observer = onRelease
        if (observer != null) {
            releaseObserverAttempted = true
            try {
                observer.invoke()
                releaseObserverCompleted = true
            } catch (t: Throwable) {
                releaseObserverFailure = t
            }
        }

        return YuvWorkDisposalOutcome(
            disposalAttempted = true,
            sourceReleaseAttempted = sourceReleaseAttempted,
            sourceReleased = sourceReleased,
            sourceReleaseFailure = sourceReleaseFailure,
            reservationReleaseAttempted = reservationReleaseAttempted,
            reservationReleased = reservationReleased,
            reservationReleaseFailure = reservationReleaseFailure,
            bufferedAccountingReleased = bufferedAccountingReleased,
            bufferedAccountingFailure = bufferedAccountingFailure,
            releaseObserverAttempted = releaseObserverAttempted,
            releaseObserverCompleted = releaseObserverCompleted,
            releaseObserverFailure = releaseObserverFailure
        )
    }

    companion object {
        /**
         * ColorFusion-compatible direct factory: wraps the (non-null) Camera2 Image in
         * an [AndroidOwnedDirectYuvSource] that is released exactly once by dispose().
         */
        fun direct(
            frameIndex: Int,
            timestampNs: Long,
            image: Image,
            onRelease: (() -> Unit)? = null
        ): YuvPngWorkItem = directOwned(
            frameIndex, timestampNs, AndroidOwnedDirectYuvSource(image, timestampNs), onRelease
        )

        fun directOwned(
            frameIndex: Int,
            timestampNs: Long,
            source: OwnedDirectYuvSource,
            onRelease: (() -> Unit)? = null
        ): YuvPngWorkItem =
            YuvPngWorkItem(frameIndex, timestampNs, YuvOwnedSource.Direct(source), 0L, null, onRelease)

        fun buffered(
            frameIndex: Int, timestampNs: Long, frame: BufferedYuvFrame,
            retainedBytes: Long, reservations: YuvBufferReservations,
            accounting: YuvCaptureAccounting, onRelease: (() -> Unit)? = null
        ): YuvPngWorkItem {
            val item = YuvPngWorkItem(
                frameIndex, timestampNs, YuvOwnedSource.Buffered(frame), retainedBytes, reservations, onRelease
            )
            accounting.bufferedFrame()
            return item
        }

        internal fun ownedForTest(onRelease: () -> Unit): YuvPngWorkItem =
            directOwned(-1, 0L, NoOpOwnedDirectYuvSource, onRelease)

        internal fun bufferedForTest(
            frameIndex: Int, timestampNs: Long, retainedBytes: Long,
            reservations: YuvBufferReservations, accounting: YuvCaptureAccounting,
            onRelease: (() -> Unit)? = null
        ): YuvPngWorkItem {
            val f = BufferedYuvFrame(
                frameIndex, timestampNs, 1, 1,
                ByteArray(0), ByteArray(0), ByteArray(0), 1, 1, 1, 1, 1, 1
            )
            val item = YuvPngWorkItem(
                frameIndex, timestampNs, YuvOwnedSource.Buffered(f), retainedBytes, reservations, onRelease
            )
            accounting.bufferedFrame()
            return item
        }
    }

    private object NoOpOwnedDirectYuvSource : OwnedDirectYuvSource {
        override val timestampNs: Long = 0L
        override fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int) =
            error("NoOpOwnedDirectYuvSource cannot encode")
        override fun release() = Unit
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
        // Typed dispatch over the sealed owned source: the direct path enters the
        // encoder through the OwnedDirectYuvSource, never through nullable probing.
        when (val source = item.sourceForEncoding()) {
            is YuvOwnedSource.Direct -> source.source.encodeTo(encoder, candidate, rotationDegrees)
            is YuvOwnedSource.Buffered -> encoder.encodeBuffered(source.frame, candidate, rotationDegrees)
        }
    }

    fun commit(candidate: File, finalFile: File) = committer.commit(candidate, finalFile)
}



internal class DisposableYuvTask(
    val item: YuvPngWorkItem,
    private val accounting: YuvCaptureAccounting,
    private val body: () -> Unit
) : DisposableCaptureTask {
    override fun run() = body()
    override fun dispose() { item.dispose(accounting) }
}

// ═══ Cleanup coordinator ═══════════════════════════════════════════════

internal enum class CleanupPhase { NOT_STARTED, IN_PROGRESS, COMPLETED }

internal data class YuvCleanupResult(
    val phase: CleanupPhase,
    val cleanupStarted: Boolean,
    val cleanupInitiationCount: Int,
    val ownerCloseRequested: Boolean,
    val workerShutdownRequested: Boolean,
    val totalDrainClaims: Int,
    val totalDrainDisposalAttempts: Int,
    val totalDrainDisposalsSucceeded: Int,
    val totalDrainSettlementsSucceeded: Int,
    val totalDrainSettlementsFailed: Int,
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
        val drainClaims: Int = 0,
        val drainDisposalAttempts: Int = 0,
        val drainDisposalsSucceeded: Int = 0,
        val drainSettlementsSucceeded: Int = 0,
        val drainSettlementsFailed: Int = 0,
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
            totalDrainClaims = s.drainClaims,
            totalDrainDisposalAttempts = s.drainDisposalAttempts,
            totalDrainDisposalsSucceeded = s.drainDisposalsSucceeded,
            totalDrainSettlementsSucceeded = s.drainSettlementsSucceeded,
            totalDrainSettlementsFailed = s.drainSettlementsFailed,
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
     * 2. claim retained lifecycle items for coordinated drain (claimRetainedForDrain)
     * 3. dispose each claim's item outside the lifecycle lock, then finish each claim
     *    independently (one failure never skips later claims)
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

        // Step 2: claim retained lifecycle items for coordinated drain (independent
        // failure boundary).  A failure here must NOT skip worker shutdown or final
        // state publication.
        val claims = try {
            lifecycle.claimRetainedForDrain()
        } catch (t: Throwable) {
            mutableFailures.add("claimRetainedForDrain: ${t.message}")
            emptyList()
        }

        // Step 3: settle each claim with the disposal-aware disposeAndFinish, OUTSIDE
        // the lifecycle lock.  Each claim settles independently: one failure never skips
        // later claims; disposal failures and lifecycle-release failures are recorded
        // separately with frame identity, and every counter stays truthful.
        for (claim in claims) {
            val outcome = try {
                claim.disposeAndFinish(accounting)
            } catch (t: Throwable) {
                mutableFailures.add("drainDisposeAndFinish[${claim.frameIndex}]: ${t.message}")
                continue
            }
            stateRef.getAndUpdate { current ->
                current.copy(
                    drainDisposalAttempts = current.drainDisposalAttempts + 1,
                    drainDisposalsSucceeded = current.drainDisposalsSucceeded +
                        if (outcome.disposal.isClean) 1 else 0,
                    drainSettlementsSucceeded = current.drainSettlementsSucceeded +
                        if (outcome.status == DrainSettlementStatus.SETTLED) 1 else 0,
                    drainSettlementsFailed = current.drainSettlementsFailed +
                        if (outcome.status == DrainSettlementStatus.FAILED) 1 else 0
                )
            }
            if (outcome.status == DrainSettlementStatus.FAILED) {
                mutableFailures.add(
                    "drainSettle[${claim.frameIndex}]: lifecycle release failed; item remains DRAINING"
                )
            }
            outcome.disposal.failures().forEach {
                mutableFailures.add("drainDispose[${claim.frameIndex}]: ${it.message}")
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
                drainClaims = claims.size,
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
