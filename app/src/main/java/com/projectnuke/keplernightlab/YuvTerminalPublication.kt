package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
internal class YuvTerminalRequestHandoff {
    private val publishedRef = AtomicReference<YuvTerminalRequest?>(null)
    private val closed = AtomicBoolean(false)
    private val unblockLatch = CountDownLatch(1)

    /**
     * Publishes the single authoritative terminal request.  Returns true only when
     * this call won the publication (first and the handoff is not closed).  Every
     * later publication is rejected and returns false; the caller records it as a
     * late/duplicate diagnostic.
     */
    fun publish(request: YuvTerminalRequest): Boolean {
        if (closed.get()) return false
        if (!publishedRef.compareAndSet(null, request)) return false
        unblockLatch.countDown()
        return true
    }

    /**
     * Session close: deterministically unblocks every waiter.  Returns true only
     * for the first close; later closes are no-ops.
     */
    fun close(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        unblockLatch.countDown()
        return true
    }

    fun isClosed(): Boolean = closed.get()

    /** The published request, or null when nothing was published yet. */
    fun request(): YuvTerminalRequest? = publishedRef.get()

    /**
     * Blocks deterministically (no polling/sleeping) until either a request is
     * published or the handoff is closed.  Returns the published request, or null
     * when the handoff was closed without a publication (cancellation/closure).
     */
    fun awaitPublishedOrClosed(): YuvTerminalRequest? {
        unblockLatch.await()
        return publishedRef.get()
    }

    /**
     * Defensive bounded variant for watchdog diagnostics: unblocks after
     * [timeoutMillis] even when neither publication nor closure happened.  The
     * return value alone must NEVER choose the user-visible terminal result; the
     * caller only logs an invariant failure.
     */
    fun awaitPublishedOrClosed(timeoutMillis: Long): YuvTerminalRequest? {
        unblockLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return publishedRef.get()
    }
}

/** Terminal metadata write request routed through [YuvTerminalMetadataWriter]. */
internal data class YuvTerminalMetadataRequest(
    val jobStatus: String,
    val savedFrames: Int,
    val manifest: List<YuvFrameManifestEntry>
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

    fun requestTerminalMetadataWrite(request: YuvTerminalMetadataRequest)

    fun verifyTerminalFinalFile(file: File, frameIndex: Int): Boolean

    fun readVerifiedTerminalFile(file: File): ByteArray

    fun observeTerminal(request: YuvTerminalRequest)
}
