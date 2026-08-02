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
internal class YuvCaptureAccounting {
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
    fun bufferedFrame() = synchronized(lock) { buffered++ }
    /** A retained allocation is no longer pending. */
    fun releasedBufferedFrame() = synchronized(lock) { if (buffered > 0) buffered-- }
    /** Acquired work failed after acceptance. */
    fun failedFrame() = synchronized(lock) { failed++ }
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
    var copied = false
    try {
        timestampNs = access.timestampNs()
        bytes = access.allocationBytes()
        if (!reservations.tryReserve(bytes)) {
            accounting.droppedFrame()
            return BufferedYuvWorkCreation.Rejected
        }
        reserved = true
        val frame = access.copy(frameIndex)
        copied = true
        // bufferedFrame() accounting is bumped by [YuvBufferedLifecycle.tryRegister] once the
        // item becomes a lifecycle-tracked RETAINED unit; we do not double-count here.
        return BufferedYuvWorkCreation.Accepted(
            YuvPngWorkItem.buffered(frameIndex, timestampNs, frame, bytes, reservations, onRelease)
        )
    } catch (oom: OutOfMemoryError) {
        throw oom
    } catch (t: Throwable) {
        accounting.failedFrame()
        return BufferedYuvWorkCreation.Failed(t)
    } finally {
        // Exactly one release attempt regardless of which path we took.
        releaseGuard.releaseSafely()
        if (reserved && !copied) {
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

    fun releaseBufferedReservation(accounting: YuvCaptureAccounting? = null) {
        if (retainedBytes > 0L && bufferedReleased.compareAndSet(false, true)) {
            reservations?.release(retainedBytes)
            accounting?.releasedBufferedFrame()
        }
    }

    fun dispose(accounting: YuvCaptureAccounting? = null) {
        if (!released.compareAndSet(false, true)) return
        val ownedImage = image
        image = null
        buffered = null
        try {
            ownedImage?.close()
        } finally {
            releaseBufferedReservation(accounting)
            onRelease?.invoke()
        }
    }

    companion object {
        fun direct(frameIndex: Int, timestampNs: Long, image: Image, onRelease: (() -> Unit)? = null) =
            YuvPngWorkItem(frameIndex, timestampNs, image, null, 0L, null, onRelease)

        fun buffered(
            frameIndex: Int,
            timestampNs: Long,
            frame: BufferedYuvFrame,
            retainedBytes: Long,
            reservations: YuvBufferReservations,
            onRelease: (() -> Unit)? = null
        ) = YuvPngWorkItem(frameIndex, timestampNs, null, frame, retainedBytes, reservations, onRelease)

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
            onRelease: (() -> Unit)? = null
        ) = YuvPngWorkItem(
            frameIndex,
            timestampNs,
            null,
            BufferedYuvFrame(frameIndex, timestampNs, 1, 1, ByteArray(0), ByteArray(0), ByteArray(0), 1, 1, 1, 1, 1, 1),
            retainedBytes,
            reservations,
            onRelease
        )
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
