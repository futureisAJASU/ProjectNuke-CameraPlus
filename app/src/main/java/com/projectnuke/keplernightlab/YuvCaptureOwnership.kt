package com.projectnuke.keplernightlab

import android.media.Image
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class YuvFrameManifestEntry(
    val frameIndex: Int,
    val filename: String,
    val timestampNs: Long,
    val persisted: Boolean,
    val failure: String? = null
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
            check(current >= bytes) { "YUV reservation released more than once" }
            if (retained.compareAndSet(current, current - bytes)) return
        }
    }

    fun currentBytes(): Long = retained.get()
}

internal open class YuvCaptureAccounting {
    private val lock = Any()
    private var received = 0
    private var buffered = 0
    private var persisted = 0
    private var failed = 0
    private var dropped = 0
    private val manifest = linkedMapOf<Int, YuvFrameManifestEntry>()

    fun receivedFrame() = synchronized(lock) { received++ }
    open fun bufferedFrame() = synchronized(lock) { buffered++ }
    fun releasedBufferedFrame() = synchronized(lock) {
        check(buffered > 0) { "bufferedFrames released more than once" }
        buffered--
    }
    open fun failedFrame() = synchronized(lock) { failed++ }
    fun droppedFrame() = synchronized(lock) { dropped++ }

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

/** Testable Camera2 access seam for buffered (copy-based) work. */
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
 * Production access for direct (non-buffered) YUV work.
 * The caller must use typed methods [createDirectYuvWork] to transfer the Image
 * atomically; no unsafe `access as DirectYuvImageAccess` cast occurs anywhere.
 */
internal interface DirectYuvImageAccess : YuvImageAccess {
    fun takeImage(): Image?
}

internal class Camera2DirectYuvImageAccess(private val image: Image) : DirectYuvImageAccess {
    private var taken = false
    override fun timestampNs(): Long = image.timestamp
    override fun allocationBytes(): Long = 0L
    override fun copy(frameIndex: Int): BufferedYuvFrame = error("direct work does not copy")
    override fun release() {
        // Ownership of the Image transfers to the work item on takeImage().
        // Once taken, release must not close the Image: the item owns it.
        if (!taken) image.close()
    }
    override fun takeImage(): Image? {
        check(!taken) { "DirectYuvImageAccess.takeImage() called twice" }
        taken = true
        return image
    }
}

internal sealed interface BufferedYuvWorkCreation {
    data class Accepted(val item: YuvPngWorkItem) : BufferedYuvWorkCreation
    data object Rejected : BufferedYuvWorkCreation
    data class Failed(val cause: Throwable) : BufferedYuvWorkCreation
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
    // On success, transfer of ownership is complete.  The access no longer
    // owns the image; it must release its wrapper state to avoid a leak in
    // the underlying ImageReader pool.
    runCatching { access.release() }
    return DirectYuvWorkCreation.Accepted(
        YuvPngWorkItem.direct(frameIndex, timestampNs, image, onRelease)
    )
}

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
        val item = YuvPngWorkItem.buffered(frameIndex, timestampNs, frame, bytes, reservations, accounting, onRelease)
        itemOwned = true
        return BufferedYuvWorkCreation.Accepted(item)
    } catch (oom: OutOfMemoryError) {
        throw oom
    } catch (t: Throwable) {
        accounting.failedFrame()
        return BufferedYuvWorkCreation.Failed(t)
    } finally {
        releaseGuard.releaseSafely()
        if (reserved && !itemOwned) {
            reservations.release(bytes)
        }
    }
}

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

    fun settleBufferedAccounting(accounting: YuvCaptureAccounting) {
        if (retainedBytes > 0L && bufferedReleased.compareAndSet(false, true)) {
            reservations?.release(retainedBytes)
            accounting.releasedBufferedFrame()
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
            if (accounting != null) settleBufferedAccounting(accounting)
            onRelease?.invoke()
        }
    }

    companion object {
        fun direct(frameIndex: Int, timestampNs: Long, image: Image?, onRelease: (() -> Unit)? = null) =
            YuvPngWorkItem(frameIndex, timestampNs, image, null, 0L, null, onRelease)

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

        internal fun ownedForTest(onRelease: () -> Unit) =
            YuvPngWorkItem(-1, 0L, null, null, 0L, null, onRelease)

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

internal class DisposableYuvTask(
    val item: YuvPngWorkItem,
    private val accounting: YuvCaptureAccounting,
    private val body: () -> Unit
) : DisposableCaptureTask {
    override fun run() = body()
    override fun dispose() = item.dispose(accounting)
}