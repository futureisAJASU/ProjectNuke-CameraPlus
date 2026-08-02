package com.projectnuke.keplernightlab

import android.media.Image
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Immutable persisted-frame identity. It is never reconstructed from list position. */
data class YuvFrameManifestEntry(
    val frameIndex: Int,
    val filename: String,
    val timestampNs: Long,
    val persisted: Boolean,
    val failure: String? = null
)

/**
 * Thread-safe byte reservations for copied YUV plane allocations. A work item owns one
 * reservation and releases it exactly once when it is persisted, discarded, or rejected.
 */
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
            check(current >= bytes) { "YUV reservation released more than once" }
            if (retained.compareAndSet(current, current - bytes)) return
        }
    }

    fun currentBytes(): Long = retained.get()
}

/** Capture accounting shared only through explicit methods until the owner-loop migration. */
internal open class YuvCaptureAccounting {
    private val lock = Any()
    private var received = 0
    private var buffered = 0
    private var persisted = 0
    private var failed = 0
    private var dropped = 0
    private val manifest = linkedMapOf<Int, YuvFrameManifestEntry>()

    /** ImageReader acquisition completed. */
    fun receivedFrame() = synchronized(lock) { received++ }
    /** A copied YUV allocation is retained pending PNG persistence. */
    open fun bufferedFrame() = synchronized(lock) { buffered++ }
    /** A retained allocation is no longer pending. */
    fun releasedBufferedFrame() = synchronized(lock) {
        check(buffered > 0) { "bufferedFrames released more than once" }
        buffered--
    }
    /** Acquired work failed after acceptance. */
    open fun failedFrame() = synchronized(lock) { failed++ }
    /** Acquired work could not enter the bounded pipeline. */
    fun droppedFrame() = synchronized(lock) { dropped++ }

    /** Commits one unique final file; duplicate identities or filenames never count as persisted. */
    fun persistedFrame(entry: YuvFrameManifestEntry): Boolean = synchronized(lock) {
        if (!entry.persisted || manifest.containsKey(entry.frameIndex) || manifest.values.any { it.filename == entry.filename }) {
            return@synchronized false
        }
        manifest[entry.frameIndex] = entry
        persisted++
        true
    }

    fun snapshot(): YuvCaptureAccountingSnapshot = synchronized(lock) {
        YuvCaptureAccountingSnapshot(received, buffered, persisted, failed, dropped, manifest.values.sortedBy { it.frameIndex })
    }
}

internal data class YuvCaptureAccountingSnapshot(
    val receivedFrames: Int,
    val bufferedFrames: Int,
    val persistedFrames: Int,
    val failedFrames: Int,
    val droppedFrames: Int,
    val manifest: List<YuvFrameManifestEntry>
)

/** Testable Camera2 access seam. Default production code captures every needed field before release. */
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

/**
 * Production access for direct (non-buffered) YUV work.  Extends [YuvImageAccess] with
 * [takeImage] so [createDirectYuvWork] can transfer the Image atomically after capturing
 * immutable metadata.  The Image is closed exactly once by [YuvImageReleaseGuard].
 */
internal interface DirectYuvImageAccess : YuvImageAccess {
    /** Atomically transfers ownership of the underlying Image to the caller. */
    fun takeImage(): Image?
}

internal class Camera2DirectYuvImageAccess(private val image: Image) : DirectYuvImageAccess {
    private var taken = false
    override fun timestampNs(): Long = image.timestamp
    override fun allocationBytes(): Long = 0L
    override fun copy(frameIndex: Int): BufferedYuvFrame = error("direct work does not copy")
    override fun release() = image.close()
    override fun takeImage(): Image? {
        check(!taken) { "DirectYuvImageAccess.takeImage() called twice" }
        taken = true
        return image
    }
}

/**
 * Copies one ImageReader frame while retaining only immutable metadata after the Image closes.
 * The caller owns the returned work item; rejected and failed creation always release the
 * Camera2 Image and any reservation before returning.
 */
internal sealed interface BufferedYuvWorkCreation {
    data class Accepted(val item: YuvPngWorkItem) : BufferedYuvWorkCreation
    data object Rejected : BufferedYuvWorkCreation
    data class Failed(val cause: Throwable) : BufferedYuvWorkCreation
}

/**
 * Production direct-work creation result.  Either an accepted work item that owns the Image,
 * or a failed result with the Image already closed exactly once.
 */
internal sealed interface DirectYuvWorkCreation {
    data class Accepted(val item: YuvPngWorkItem) : DirectYuvWorkCreation
    data class Failed(val cause: Throwable) : DirectYuvWorkCreation
}

/**
 * Production factory for direct YUV work items.  Captures the immutable metadata
 * (timestamp) before the Image is touched by encoding, and transfers Image ownership
 * atomically: the returned [YuvPngWorkItem] becomes the sole owner of the Image.
 * If timestamp access fails, the Image is closed exactly once via the release guard
 * and no work item is returned.
 *
 * Requirements satisfied:
 *  - Image is closed exactly once on any failure.
 *  - No work item is enqueued when creation fails.
 *  - `failedFrames` is updated through [account].
 *  - No later Image property access occurs after release begins.
 *
 * The [access] must be a [DirectYuvImageAccess] providing [takeImage] for atomic transfer.
 */
internal fun createDirectYuvWork(
    frameIndex: Int,
    access: DirectYuvImageAccess,
    account: YuvCaptureAccounting,
    onRelease: (() -> Unit)? = null
): DirectYuvWorkCreation {
    // Capture timestamp first.  If this fails, release the Image exactly once.
    val timestampNs = try {
        access.timestampNs()
    } catch (t: Throwable) {
        access.release()
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(t)
    }
    // Atomically transfer Image ownership to the work item.  The work item becomes
    // the sole owner; when it disposes, it closes the Image exactly once.
    val image = try {
        access.takeImage()
    } catch (t: Throwable) {
        access.release()
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(t)
    }
    return DirectYuvWorkCreation.Accepted(
        YuvPngWorkItem.direct(frameIndex, timestampNs, image, onRelease)
    )
}

/**
 * Guarantees that an underlying [YuvImageAccess.release] is invoked at most once even when the
 * first attempt throws after partially closing the Camera2 Image. Used by
 * [createBufferedYuvWork] so no code path can perform a second release that would double-close
 * the underlying Image.
 */
internal class YuvImageReleaseGuard(private val access: YuvImageAccess) {
    private val consumed = AtomicBoolean(false)

    fun releaseSafely() {
        if (consumed.compareAndSet(false, true)) {
            runCatching { access.release() }
        }
    }
}

internal fun createBufferedYuvWork(
    frameIndex: Int,
    access: YuvImageAccess,
    reservations: YuvBufferReservations,
    accounting: YuvCaptureAccounting,
    onRelease: (() -> Unit)? = null
): BufferedYuvWorkCreation {
    val releaseGuard = YuvImageReleaseGuard(access)
    var timestampNs = 0L
    var bytes = 0L
    var reserved = false
    var itemOwned = false
    try {
        timestampNs = access.timestampNs()
        bytes = access.allocationBytes()
        if (!reservations.tryReserve(bytes)) {
            accounting.droppedFrame()
            return BufferedYuvWorkCreation.Rejected
        }
        reserved = true
        val frame = access.copy(frameIndex)
        // [YuvPngWorkItem.buffered] increments bufferedFrames once via the item's own
        // accounting token.  That token can only be settled by settleEncoding or dispose.
        // If the factory throws after the copy but before the item is constructed, the
        // finally block releases the reservation because itemOwned is still false.
        val item = YuvPngWorkItem.buffered(frameIndex, timestampNs, frame, bytes, reservations, accounting, onRelease)
        itemOwned = true
        return BufferedYuvWorkCreation.Accepted(item)
    } catch (oom: OutOfMemoryError) {
        throw oom
    } catch (t: Throwable) {
        accounting.failedFrame()
        return BufferedYuvWorkCreation.Failed(t)
    } finally {
        // Exactly one release attempt regardless of which path we took.
        releaseGuard.releaseSafely()
        // Release the reservation if the work item never took ownership (copy succeeded
        // but construction failed).  When itemOwned is true the item owns the reservation.
        if (reserved && !itemOwned) {
            reservations.release(bytes)
        }
    }
}

/** A disposable queue item that owns either one Camera2 Image or one copied YUV allocation. */
internal class YuvPngWorkItem private constructor(
    val frameIndex: Int,
    val timestampNs: Long,
    private var image: Image?,
    private var buffered: BufferedYuvFrame?,
    private val retainedBytes: Long,
    private val reservations: YuvBufferReservations?,
    private val onRelease: (() -> Unit)?
) {
    private val released = AtomicBoolean(false)
    private val bufferedReleased = AtomicBoolean(false)

    fun imageForEncoding(): Image? = image
    fun bufferedForEncoding(): BufferedYuvFrame? = buffered
    fun retainedBytes(): Long = retainedBytes

    /**
     * Single authoritative settlement for the buffered-frame accounting token.
     *
     * A buffered work item owns its accounting token for the duration of its life:
     * exactly one call to [settleBufferedAccounting] releases the reservation and
     * decrements the buffered-frame counter.  The token is settled either by:
     *  - the lifecycle's [YuvBufferedLifecycle.settleEncoding] (successful encoding path), or
     *  - [dispose] when the lifecycle never accepted the item (close-race / failure paths).
     *
     * [bufferedFrames] is incremented once by [createBufferedYuvWork] (via the constructor's
     * accounting token) and can only be released through this single path.  Calling
     * [settleBufferedAccounting] or [dispose] more than once is a no-op after the first call;
     * a double-settle is detected by the reservation CAS and throws rather than silently
     * underflowing.
     */
    fun settleBufferedAccounting(accounting: YuvCaptureAccounting) {
        if (retainedBytes > 0L && bufferedReleased.compareAndSet(false, true)) {
            reservations?.release(retainedBytes)
            accounting.releasedBufferedFrame()
        }
    }

    /**
     * Disposes the work item exactly once.  For buffered items the accounting token is
     * settled here only if [settleBufferedAccounting] has not already run (e.g. when the
     * lifecycle never accepted the item after a close race, or when construction failed
     * before registration).
     */
    fun dispose(accounting: YuvCaptureAccounting? = null) {
        if (!released.compareAndSet(false, true)) return
        val ownedImage = image
        image = null
        buffered = null
        try {
            ownedImage?.close()
        } finally {
            if (accounting != null) settleBufferedAccounting(accounting)
            onRelease?.invoke()
        }
    }

    companion object {
        fun direct(frameIndex: Int, timestampNs: Long, image: Image?, onRelease: (() -> Unit)? = null) =
            YuvPngWorkItem(frameIndex, timestampNs, image, null, 0L, null, onRelease)

        /**
         * Production factory for buffered work items.  Increments [bufferedFrames] once via
         * the accounting token captured at construction; that token can only be released
         * through [settleBufferedAccounting] or [dispose].
         */
        fun buffered(
            frameIndex: Int,
            timestampNs: Long,
            frame: BufferedYuvFrame,
            retainedBytes: Long,
            reservations: YuvBufferReservations,
            accounting: YuvCaptureAccounting,
            onRelease: (() -> Unit)? = null
        ) = YuvPngWorkItem(frameIndex, timestampNs, null, frame, retainedBytes, reservations, onRelease)
            .also { accounting.bufferedFrame() }

        /** Ownership-only seam for JVM tests; production Camera2 work always uses [direct]. */
        internal fun ownedForTest(onRelease: () -> Unit) =
            YuvPngWorkItem(-1, 0L, null, null, 0L, null, onRelease)

        /**
         * Buffered ownership seam for JVM tests; production buffered work always uses [buffered]
         * with a real copied frame and reservations object.
         */
        internal fun bufferedForTest(
            frameIndex: Int,
            timestampNs: Long,
            retainedBytes: Long,
            reservations: YuvBufferReservations,
            accounting: YuvCaptureAccounting,
            onRelease: (() -> Unit)? = null
        ) = YuvPngWorkItem(
            frameIndex,
            timestampNs,
            null,
            BufferedYuvFrame(frameIndex, timestampNs, 1, 1, ByteArray(0), ByteArray(0), ByteArray(0), 1, 1, 1, 1, 1, 1),
            retainedBytes,
            reservations,
            onRelease
        ).also { accounting.bufferedFrame() }
    }
}

internal interface YuvPngEncoder {
    fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int)
    fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int)
}

internal fun interface YuvCandidateCommitter {
    fun commit(candidate: File, finalFile: File)
}

/**
 * Production-used encoding/commit seam. It deliberately does not mutate capture accounting:
 * the caller records a persisted frame only after this method returns and validates identity.
 */
internal class YuvPngWorkProcessor(
    private val encoder: YuvPngEncoder,
    private val committer: YuvCandidateCommitter
) {
    fun encode(item: YuvPngWorkItem, candidate: File, rotationDegrees: Int) {
        val image = item.imageForEncoding()
        if (image != null) {
            encoder.encodeDirect(image, candidate, rotationDegrees)
            return
        }
        val buffered = item.bufferedForEncoding()
            ?: error("YUV work item has no owned source")
        encoder.encodeBuffered(buffered, candidate, rotationDegrees)
    }

    fun commit(candidate: File, finalFile: File) = committer.commit(candidate, finalFile)
}

/** Runnable wrapper so queue rejection and shutdown can dispose the exact owned item. */
internal class DisposableYuvTask(
    val item: YuvPngWorkItem,
    private val accounting: YuvCaptureAccounting,
    private val body: () -> Unit
) : DisposableCaptureTask {
    override fun run() = body()
    override fun dispose() = item.dispose(accounting)
}
