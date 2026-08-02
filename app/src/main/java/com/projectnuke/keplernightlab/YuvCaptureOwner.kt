package com.projectnuke.keplernightlab

import android.media.Image
import android.os.Handler
import android.util.Log
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable worker completion result.  The worker never mutates owner state; it only
 * encodes a temporary candidate and returns this result for owner-side adoption.
 */
internal sealed interface YuvWorkerCompletion {
    val frameIndex: Int
    val timestampNs: Long

    data class Success(
        override val frameIndex: Int,
        override val timestampNs: Long,
        val candidate: File,
        val fileName: String,
        val encodeDurationMs: Long
    ) : YuvWorkerCompletion

    data class Failed(
        override val frameIndex: Int,
        override val timestampNs: Long,
        val candidate: File?,
        val cause: Throwable
    ) : YuvWorkerCompletion
}

/**
 * A disposable task for buffered YUV encoding that settles the lifecycle item in its
 * finally block and posts the completion back to the owner.
 */
internal class DisposableBufferedYuvTask(
    val item: YuvPngWorkItem,
    private val accounting: YuvCaptureAccounting,
    private val lifecycle: YuvBufferedLifecycle,
    private val encode: () -> Unit
) : DisposableCaptureTask {
    @Volatile private var settled = false

    override fun run() {
        try {
            encode()
        } catch (t: Throwable) {
            Log.e("KeplerYuvOwner", "Buffered encode threw", t)
        } finally {
            if (!settled) {
                settled = true
                lifecycle.settleEncoding(item, accounting)
            }
        }
    }

    override fun dispose() {
        if (!settled) {
            settled = true
            lifecycle.settleEncoding(item, accounting)
        }
        settled = true
    }
}

/**
 * YuvCaptureOwner is the single authoritative owner for the YUV capture pipeline state.
 *
 * Camera callbacks, the timeout scheduler, cancellation, and worker completions may only
 * post immutable events.  All mutable state transitions happen on the owner's serialized
 * dispatcher (the capture handler thread).  This ensures there is exactly one point of
 * truth for: terminal state, frame identity allocation, received/buffered/persisted/failed/
 * dropped counters, the persisted manifest, retained-byte registration, completed capture-
 * result count, and success/error callback decision.
 *
 * Terminal states (only the owner may transition out of ACTIVE):
 *  ACTIVE -> SUCCESS | PARTIAL_SUCCESS | FAILED | TIMED_OUT | CANCELLED
 */
internal class YuvCaptureOwner(
    private val captureHandler: Handler,
    private val frameCount: Int,
    private val outputDir: File,
    private val rotationDegrees: Int,
    private val workProcessor: YuvPngWorkProcessor,
    private val reservations: YuvBufferReservations,
    private val accounting: YuvCaptureAccounting,
    private val lifecycle: YuvBufferedLifecycle,
    private val identityOwner: CaptureFrameIdentityOwner,
    private val terminalState: CaptureTerminalState,
    private val captureStateOwner: CaptureStateOwner,
    private val boundedWorker: BoundedCaptureWorker,
    private val postStatus: (String) -> Unit,
    private val postMainOrRun: (Runnable) -> Unit,
    private val writeJobJson: (status: String, savedFrames: Int, manifest: List<YuvFrameManifestEntry>) -> Unit,
    private val saveMotionOnce: (File) -> Pair<String?, String?>,
    private val motionLogger: MotionLogger?,
    private val finished: AtomicBoolean,
    private val onCaptureComplete: (File) -> Unit,
    private val onCaptureError: (String) -> Unit
) {

    private var completedResults = 0
    private val callbackFired = AtomicBoolean(false)

    // ------------------------------------------------------------------
    // Camera2 callback entry points
    // ------------------------------------------------------------------

    /**
     * Accepts one frame from the ImageReader.  The caller passes an immutable event
     * containing the acquired image access and useMemoryBuffer flag.  The owner processes
     * it on its handler thread.  [emergencyDispose] is called only if the owner cannot
     * accept the event (e.g. closed), and must release the image exactly once.
     */
    fun acceptImage(access: YuvImageAccess, useMemoryBuffer: Boolean, emergencyDispose: () -> Unit) {
        val accepted = captureStateOwner.post {
            onImageEnqueued(access, useMemoryBuffer, emergencyDispose)
        }
        if (!accepted) {
            emergencyDispose()
        }
    }

    private fun onImageEnqueued(access: YuvImageAccess, useMemoryBuffer: Boolean, emergencyDispose: () -> Unit) {
        if (terminalState.status() != CaptureTerminalStatus.ACTIVE) {
            access.release()
            return
        }
        val frameIndex = identityOwner.nextIdentity()
        if (frameIndex == null) {
            accounting.droppedFrame()
            access.release()
            return
        }
        accounting.receivedFrame()
        if (useMemoryBuffer) {
            processBufferedImage(access, frameIndex, emergencyDispose)
        } else {
            processDirectImage(access as DirectYuvImageAccess, frameIndex)
        }
    }

    fun onCaptureCompletedResult() {
        completedResults++
    }

    fun onCaptureFailed(cause: Throwable, detail: String) {
        val posted = captureStateOwner.post {
            finishError("Color Burst 캡처 실패: $detail", cause = cause)
        }
        if (!posted) {
            Log.e("KeplerYuvOwner", "Owner handler rejected capture failure")
        }
    }

    // ------------------------------------------------------------------
    // Image processing (runs on owner handler thread)
    // ------------------------------------------------------------------

    private fun processBufferedImage(
        access: YuvImageAccess,
        frameIndex: Int,
        emergencyDispose: () -> Unit
    ) {
        when (val creation = createBufferedYuvWork(
            frameIndex = frameIndex,
            access = access,
            reservations = reservations,
            accounting = accounting
        )) {
            is BufferedYuvWorkCreation.Accepted -> {
                val item = creation.item
                val registered = lifecycle.tryRegister(item)
                if (!registered) {
                    item.dispose(accounting)
                    emergencyDispose()
                    return
                }
                val bufferedCount = accounting.snapshot().bufferedFrames
                postStatus("YUV buffered frame $bufferedCount/$frameCount")
                scheduleBufferedEncoding()
            }
            BufferedYuvWorkCreation.Rejected -> {
                postStatus("YUV memory buffer dropped frame ${frameIndex + 1}/$frameCount: retained=${reservations.currentBytes()} bytes")
                emergencyDispose()
            }
            is BufferedYuvWorkCreation.Failed -> {
                if (creation.cause is Error) throw creation.cause
                finishError("YUV memory buffer copy failed", cause = creation.cause)
            }
        }
    }

    private fun processDirectImage(access: DirectYuvImageAccess, frameIndex: Int) {
        when (val creation = createDirectYuvWork(
            frameIndex = frameIndex,
            access = access,
            account = accounting
        )) {
            is DirectYuvWorkCreation.Accepted -> {
                val task = DisposableYuvTask(
                    creation.item,
                    accounting,
                    {
                        try {
                            processDirectCompletion(creation.item)
                        } finally {
                            creation.item.dispose(accounting)
                        }
                    }
                )
                if (!boundedWorker.submit(task)) {
                    accounting.droppedFrame()
                    postStatus("YUV capture backpressure: frame ${creation.item.frameIndex + 1} dropped")
                }
            }
            is DirectYuvWorkCreation.Failed -> {
                if (creation.cause is Error) throw creation.cause
                finishError("YUV direct image creation failed", cause = creation.cause)
            }
        }
    }

    private fun processDirectCompletion(item: YuvPngWorkItem) {
        val startTime = System.nanoTime()
        val fileName = "frame_${item.frameIndex.toString().padStart(2, '0')}_color.png"
        val candidate = File(outputDir, ".${fileName}.${System.nanoTime()}.tmp")
        val completion = try {
            workProcessor.encode(item, candidate, rotationDegrees)
            YuvWorkerCompletion.Success(
                frameIndex = item.frameIndex,
                timestampNs = item.timestampNs,
                candidate = candidate,
                fileName = fileName,
                encodeDurationMs = (System.nanoTime() - startTime) / 1_000_000
            )
        } catch (t: Throwable) {
            YuvWorkerCompletion.Failed(item.frameIndex, item.timestampNs, candidate, t)
        }
        postCompletion(completion)
    }

    // ------------------------------------------------------------------
    // Buffered encoding scheduling
    // One-at-a-time design: when enough frames are buffered, schedule the next
    // retained frame after the previous completion returns.
    // ------------------------------------------------------------------

    private fun scheduleBufferedEncoding() {
        val flushItems = lifecycle.snapshotRetainedByFrameIndex()
        if (flushItems.isEmpty()) return

        val frame = flushItems.first()
        if (!lifecycle.beginEncoding(frame)) return

        val fileName = "frame_${frame.frameIndex.toString().padStart(2, '0')}_color.png"
        val candidate = File(outputDir, ".${fileName}.${System.nanoTime()}.tmp")
        val task = DisposableBufferedYuvTask(
            item = frame,
            accounting = accounting,
            lifecycle = lifecycle,
            encode = {
                val completion = try {
                    workProcessor.encode(frame, candidate, rotationDegrees)
                    YuvWorkerCompletion.Success(
                        frameIndex = frame.frameIndex,
                        timestampNs = frame.timestampNs,
                        candidate = candidate,
                        fileName = fileName,
                        encodeDurationMs = 0L
                    )
                } catch (t: Throwable) {
                    YuvWorkerCompletion.Failed(frame.frameIndex, frame.timestampNs, candidate, t)
                }
                postCompletion(completion)
                scheduleBufferedEncoding()
            }
        )
        if (!boundedWorker.submit(task)) {
            lifecycle.settleEncoding(frame, accounting)
            postStatus("YUV backpressure: buffered frame ${frame.frameIndex + 1} dropped")
        }
    }

    // ------------------------------------------------------------------
    // Owner-side atomic adoption
    // Posted back to the owner thread so the ACTIVE check, candidate commit,
    // manifest append, and persisted accounting are all one serialized decision.
    // ------------------------------------------------------------------

    private fun postCompletion(completion: YuvWorkerCompletion) {
        val posted = captureStateOwner.post {
            adoptCompletion(completion)
        }
        if (!posted) {
            when (completion) {
                is YuvWorkerCompletion.Success -> runCarring { completion.candidate.delete() }
                is YuvWorkerCompletion.Failed -> {}
            }
        }
    }

    private fun adoptCompletion(completion: YuvWorkerCompletion) {
        when (completion) {
            is YuvWorkerCompletion.Success -> adoptSuccess(completion)
            is YuvWorkerCompletion.Failed -> {
                Log.e("KeplerYuvOwner", "YUV worker failed for frame ${completion.frameIndex}", completion.cause)
                accounting.failedFrame()
            }
        }
        checkTerminal()
    }

    private fun adoptSuccess(completion: YuvWorkerCompletion.Success) {
        if (terminalState.status() != CaptureTerminalStatus.ACTIVE) {
            runCarring { completion.candidate.delete() }
            return
        }
        val finalFile = File(outputDir, completion.fileName)
        workProcessor.commit(completion.candidate, finalFile)
        val entry = YuvFrameManifestEntry(
            frameIndex = completion.frameIndex,
            filename = completion.fileName,
            timestampNs = completion.timestampNs,
            persisted = true
        )
        if (!accounting.persistedFrame(entry)) {
            Log.w("KeplerYuvOwner", "Duplicate YUV persisted identity or filename: ${completion.fileName}")
        }
        val persistedFrames = accounting.snapshot().persistedFrames
        postStatus("YUV capture: saved $persistedFrames/$frameCount")
        val snap = accounting.snapshot()
        val motionFiles = saveMotionOnce(outputDir)
        writeJobJson("CAPTURING", persistedFrames, snap.manifest)
    }

    // ------------------------------------------------------------------
    // Terminal state and deadline settlement
    // ------------------------------------------------------------------

    private fun checkTerminal() {
        val snap = accounting.snapshot()
        if (snap.persistedFrames >= frameCount) {
            finishSuccess()
        }
    }

    fun onDeadlineReached() {
        val posted = captureStateOwner.post {
            val snap = accounting.snapshot()
            if (snap.persistedFrames >= frameCount) {
                finishSuccess(terminalAlreadyClaimed = true)
            } else {
                finishError(
                    "YUV capture timeout: saved=${snap.persistedFrames}/$frameCount",
                    source = "YuvCaptureOwner.timeout",
                    failureType = "CaptureTimeout"
                )
            }
        }
        if (!posted) {
            Log.e("KeplerYuvOwner", "Owner handler rejected deadline settlement")
            finished.set(true)
            cleanup()
        }
    }

    fun onCancellationRequested() {
        val posted = captureStateOwner.post {
            if (terminalState.claim(CaptureTerminalStatus.CANCELLED)) {
                finishCancel()
            }
        }
        if (!posted) {
            Log.e("KeplerYuvOwner", "Owner handler rejected cancellation")
            finished.set(true)
            cleanup()
        }
    }

    // ------------------------------------------------------------------
    // Terminal settlement — only the owner writes metadata and decides callbacks
    // ------------------------------------------------------------------

    private fun finishSuccess(terminalAlreadyClaimed: Boolean = false) {
        if (!terminalAlreadyClaimed && !terminalState.claim(CaptureTerminalStatus.SUCCESS)) return
        if (!callbackFired.compareAndSet(false, true)) return
        finished.set(true)
        val snap = accounting.snapshot()
        val motionFiles = saveMotionOnce(outputDir)
        writeJobJson("CAPTURE_COMPLETE", snap.persistedFrames, snap.manifest)
        postStatus("CAPTURE_COMPLETE: 캡처가 완료되었습니다.")
        cleanup()
        postMainOrRun { onCaptureComplete(outputDir) }
    }

    private fun finishError(
        message: String,
        source: String = "YuvCaptureOwner",
        cause: Throwable? = null,
        failureType: String? = null,
        terminalAlreadyClaimed: Boolean = false
    ) {
        if (!terminalAlreadyClaimed && !terminalState.claim(CaptureTerminalStatus.FAILED)) return
        if (!callbackFired.compareAndSet(false, true)) return
        finished.set(true)
        Log.e("KeplerYuvOwner", "$source: $message", cause)
        val snap = accounting.snapshot()
        writeJobJson("CAPTURE_FAILED", snap.persistedFrames, snap.manifest)
        postStatus(message)
        cleanup()
        postMainOrRun { onCaptureError(message) }
    }

    private fun finishCancel() {
        if (!callbackFired.compareAndSet(false, true)) return
        finished.set(true)
        val snap = accounting.snapshot()
        writeJobJson("CAPTURE_CANCELLED", snap.persistedFrames, snap.manifest)
        postStatus("CAPTURE_CANCELLED: YUV capture cancelled")
        cleanup()
        postMainOrRun { onCaptureComplete(outputDir) }
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    private fun cleanup() {
        captureStateOwner.close()
        val drained = lifecycle.closeAndDrainRetained()
        drained.forEach { it.dispose(accounting) }
        boundedWorker.shutdownNow()
        try { captureHandler.looper.quitSafely() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------

    internal fun terminalSnapshot(): TerminalSnapshot {
        val snap = accounting.snapshot()
        return TerminalSnapshot(
            receivedFrames = snap.receivedFrames,
            bufferedFrames = snap.bufferedFrames,
            persistedFrames = snap.persistedFrames,
            failedFrames = snap.failedFrames,
            droppedFrames = snap.droppedFrames,
            manifest = snap.manifest,
            completedResults = completedResults,
            queuedWork = 0,
            inFlightWork = 0,
            terminalStatus = terminalState.status(),
            terminalReason = null,
            discardedLateCompletions = emptyList<Int>()
        )
    }

    internal data class TerminalSnapshot(
        val receivedFrames: Int,
        val bufferedFrames: Int,
        val persistedFrames: Int,
        val failedFrames: Int,
        val droppedFrames: Int,
        val manifest: List<YuvFrameManifestEntry>,
        val completedResults: Int,
        val queuedWork: Int,
        val inFlightWork: Int,
        val terminalStatus: CaptureTerminalStatus,
        val terminalReason: String?,
        val discardedLateCompletions: List<Int>
    )
}

private fun <T> runCarring(block: () -> T) { try { block() } catch (_: Exception) {} }
