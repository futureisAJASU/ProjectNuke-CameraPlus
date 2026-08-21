package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Sole terminal-request publication handoff owned by [YuvCaptureSession].
 *
 * The serialized owner publishes exactly one [YuvTerminalRequest] through this
 * handoff ([publish]); ColorFusion's terminal gate observes it deterministically
 * ([awaitPublishedOrClosed]).  Duplicate/later publications are rejected.
 *
 * - `publish` wins exactly once (compare-and-set); later requests are rejected.
 * - [close] (session close) deterministically unblocks every waiter as closure;
 *   a waiter then observes `null` and never synthesizes a terminal result.
 * - No polling and no sleeps: waiting is a blocking latch await.
 * - The handoff never mutates capture state; the serialized owner remains
 *   responsible for normal terminal mutation.
 * - A defensive bounded wait ([awaitPublishedOrClosed] with timeout) exists for
 *   watchdog diagnostics only; it NEVER decides the user-visible terminal result.
 */
internal sealed interface YuvTerminalHandoffResult {
    data class Published(val request: YuvTerminalRequest) : YuvTerminalHandoffResult
    data object Closed : YuvTerminalHandoffResult
    data class SettlementFailed(val failure: Throwable) : YuvTerminalHandoffResult
    /** Diagnostic-only bounded wait result.  It must never choose a capture result. */
    data object WatchdogTimeout : YuvTerminalHandoffResult
}

/**
 * A one-way terminal handoff.  The terminal state is deliberately one atomic value:
 * publication, closure, and an internal settlement failure compete for OPEN with a
 * single CAS.  The latch is only a wake-up primitive and is never an authority.
 */
internal class YuvTerminalRequestHandoff {
    private sealed interface State {
        data object Open : State
        data class Published(val request: YuvTerminalRequest) : State
        data object Closed : State
        data class SettlementFailed(val failure: Throwable) : State
    }

    private val state = AtomicReference<State>(State.Open)
    private val unblockLatch = CountDownLatch(1)

    /**
     * Publishes the single authoritative terminal request.  Returns true only when
     * this call won the publication (first and the handoff is not closed).  Every
     * later publication is rejected and returns false; the caller records it as a
     * late/duplicate diagnostic.
     */
    fun publish(request: YuvTerminalRequest): Boolean {
        if (!state.compareAndSet(State.Open, State.Published(request))) return false
        unblockLatch.countDown() // wake-up only; state CAS above is authoritative
        return true
    }

    /**
     * Publishes the exceptional terminal-mechanism failure.  This is intentionally
     * not a capture FAILED/TIMED_OUT/CANCELLED result: it means a valid terminal
     * request itself could not be settled or published.
     */
    fun failSettlement(failure: Throwable): Boolean {
        if (!state.compareAndSet(State.Open, State.SettlementFailed(failure))) return false
        unblockLatch.countDown()
        return true
    }

    /**
     * Session close: deterministically unblocks every waiter.  Returns true only
     * for the first close; later closes are no-ops.
     */
    fun close(): Boolean {
        if (!state.compareAndSet(State.Open, State.Closed)) return false
        unblockLatch.countDown()
        return true
    }

    fun isClosed(): Boolean = state.get() is State.Closed

    /** The published request, or null when nothing was published yet. */
    fun request(): YuvTerminalRequest? = (state.get() as? State.Published)?.request

    fun result(): YuvTerminalHandoffResult? = state.get().toResultOrNull()

    /**
     * Blocks deterministically (no polling/sleeping) until either a request is
     * published or the handoff is closed.  Returns the published request, or null
     * when the handoff was closed without a publication (cancellation/closure).
     */
    fun awaitPublishedOrClosed(): YuvTerminalRequest? {
        unblockLatch.await()
        return request()
    }

    /**
     * Defensive bounded variant for watchdog diagnostics: unblocks after
     * [timeoutMillis] even when neither publication nor closure happened.  The
     * return value alone must NEVER choose the user-visible terminal result; the
     * caller only logs an invariant failure.
     */
    fun awaitPublishedOrClosed(timeoutMillis: Long): YuvTerminalRequest? {
        unblockLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return request()
    }

    /** Typed terminal observation used by the production terminal consumer. */
    fun awaitResult(): YuvTerminalHandoffResult {
        unblockLatch.await()
        return state.get().toResultOrNull()
            ?: error("terminal handoff unblocked while still OPEN")
    }

    /** Bounded diagnostic observation.  [YuvTerminalHandoffResult.WatchdogTimeout]
     * is never a capture terminal decision. */
    fun awaitResult(timeoutMillis: Long): YuvTerminalHandoffResult {
        if (!unblockLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return YuvTerminalHandoffResult.WatchdogTimeout
        }
        return state.get().toResultOrNull()
            ?: error("terminal handoff unblocked while still OPEN")
    }

    private fun State.toResultOrNull(): YuvTerminalHandoffResult? = when (this) {
        State.Open -> null
        is State.Published -> YuvTerminalHandoffResult.Published(request)
        State.Closed -> YuvTerminalHandoffResult.Closed
        is State.SettlementFailed -> YuvTerminalHandoffResult.SettlementFailed(failure)
    }
}

/** Terminal metadata write request routed through [YuvTerminalMetadataWriter]. */
internal data class YuvTerminalMetadataRequest(
    val jobStatus: String,
    val savedFrames: Int,
    val manifest: List<YuvFrameManifestEntry>,
    val receivedFrames: Int,
    val persistedFrames: Int,
    val failedFrames: Int,
    val droppedFrames: Int,
    val completedResults: Int,
    val firstWorkerFailureClass: String?,
    val firstWorkerFailureMessage: String?,
    val firstWorkerFailureFrameIndex: Int?
)

/**
 * Session-owned terminal metadata writer.  YuvCaptureOwner requests session
 * terminal operations; YuvCaptureSession owns this adapter and ColorFusion
 * supplies the real production implementation (wrapping the existing rich
 * `writeColorJobJson(...)` metadata surface).
 */
internal fun interface YuvTerminalMetadataWriter {
    fun write(request: YuvTerminalMetadataRequest)
}

/**
 * Session-owned verified file reader.  The production adapter reuses
 * [NoFollowFileSystem.readBytesVerified]; there is no separate raw `readBytes`
 * implementation behind it.
 */
internal fun interface YuvVerifiedFileReader {
    fun read(file: File): ByteArray
}

/**
 * Session-owned terminal final-file verifier.  The production adapter reuses the
 * existing [YuvFinalFileVerifier] / [RealYuvFinalFileVerifier] contract.
 */
internal fun interface YuvTerminalFinalVerifier {
    fun verify(file: File, frameIndex: Int): Boolean
}

/**
 * The one session-terminal gateway used by [YuvCaptureOwner]:
 *
 *  - [publishTerminal]: sole terminal-request publication (never mutates capture state)
 *  - [requestTerminalMetadataWrite]: session-owned metadata writer
 *  - [verifyTerminalFinalFile] / [readVerifiedTerminalFile]: session-owned verification
 *  - [observeTerminal]: session terminal observer dispatch (ColorFusion consumes the
 *    published request and dispatches exactly one onComplete/onError)
 *
 * The session implements this gateway over its internal adapters; the owner never
 * holds the production implementations directly.
 */
internal interface YuvSessionTerminalOperations {
    fun publishTerminal(request: YuvTerminalRequest): Boolean

    fun publishSettlementFailure(failure: Throwable): Boolean

    fun requestTerminalMetadataWrite(request: YuvTerminalMetadataRequest)

    fun verifyTerminalFinalFile(file: File, frameIndex: Int): Boolean

    fun readVerifiedTerminalFile(file: File): ByteArray

    fun observeTerminal(request: YuvTerminalRequest)

    fun observeSettlementFailure(failure: Throwable)
}
