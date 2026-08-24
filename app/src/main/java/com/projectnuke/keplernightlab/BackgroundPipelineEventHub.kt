package com.projectnuke.keplernightlab

import android.util.Log
import java.io.File
import java.util.ArrayDeque

/**
 * Immutable envelope binding a background pipeline event to its EXACT durable
 * job identities. After foreground generation ownership is released at the
 * capture handoff, exact job identity is the only correlation key: background
 * events must never be correlated through "latest job" or mutable foreground
 * generation state. [event] keeps [CameraPipelineEvent] unchanged; terminal
 * events additionally carry jobDirectoryPath/resultJobDirectoryPath inside the
 * record itself.
 *
 * Dual identity model:
 *  - [requestJobDirectory] is the ROUTING identity: the job directory the work
 *    was enqueued under (for Super Resolution this is the SOURCE capture job).
 *  - [resultJobDirectory] is the RESULT identity: the directory holding the
 *    final durable output (for YUV/RAW it equals the request identity; for SR
 *    it is the newly created Super Resolution output directory).
 * The two identities are never conflated: routing/diagnostics use the request
 * identity, result finalization and UI result refresh use the result identity.
 */
internal data class BackgroundPipelineEvent(
    val requestJobDirectory: File,
    val jobKind: KeplerActiveOperationKind,
    val event: CameraPipelineEvent,
    val resultJobDirectory: File = requestJobDirectory
) {
    /** True when the durable result identity differs from the routing identity. */
    val hasDistinctResultIdentity: Boolean
        get() = requestJobDirectory != resultJobDirectory
}

internal fun interface BackgroundPipelineEventSubscriber {
    fun onBackgroundEvent(event: BackgroundPipelineEvent)
}

/**
 * Explicit registration token; disposing removes the subscriber from the hub
 * so a disposed screen/recorder is never retained.
 */
internal class BackgroundPipelineEventSubscription private constructor(
    private val hub: BackgroundPipelineEventHub,
    private val subscriber: BackgroundPipelineEventSubscriber
) {
    @Volatile
    private var disposed = false

    fun dispose() {
        if (disposed) return
        disposed = true
        hub.remove(subscriber)
    }

    internal fun isDisposed(): Boolean = disposed

    internal companion object {
        internal fun create(
            hub: BackgroundPipelineEventHub,
            subscriber: BackgroundPipelineEventSubscriber
        ): BackgroundPipelineEventSubscription = BackgroundPipelineEventSubscription(hub, subscriber)
    }
}

/**
 * Process-scoped observational event surface for background pipeline jobs.
 *
 * Contract:
 *  - The hub NEVER owns production truth. Durable job metadata and
 *    JobOperationLease settlement remain authoritative; publishing must not
 *    depend on any subscriber being present.
 *  - Delivery is strictly observational for ORDINARY failures: a subscriber
 *    throwing an ordinary Exception (including CancellationException) can never
 *    alter production processing, terminal truth, or other subscribers — the
 *    failure is logged and delivery continues with the remaining subscribers.
 *  - FATAL-ISOLATION EXCEPTION: a JVM Error thrown by a subscriber is NEVER
 *    converted into an ordinary observational failure. It propagates to the
 *    producer so the producer lane's own fatal-settlement precedence applies
 *    (Error is rethrown there, never downgraded). Swallowing OOM/StackOverflow
 *    and similar here would corrupt the process far beyond one lost event, so
 *    this exact site documents why Error isolation does NOT apply.
 *  - Subscribers are bounded and removed exactly on dispose; the hub holds no
 *    Activity/Composable/callback closures beyond registered lambdas and
 *    retains nothing after disposal.
 */
internal object BackgroundPipelineEventHub {
    private const val TAG = "KeplerBackgroundEvents"
    private const val MAX_SUBSCRIBERS = 16

    private val lock = Any()
    private val subscribers = ArrayDeque<BackgroundPipelineEventSubscriber>()

    fun subscribe(subscriber: BackgroundPipelineEventSubscriber): BackgroundPipelineEventSubscription {
        synchronized(lock) {
            if (subscribers.size >= MAX_SUBSCRIBERS) {
                Log.w(TAG, "subscriber limit reached; rejecting subscription")
            } else {
                subscribers.addLast(subscriber)
            }
        }
        return BackgroundPipelineEventSubscription.create(this, subscriber)
    }

    internal fun remove(subscriber: BackgroundPipelineEventSubscriber) {
        synchronized(lock) { subscribers.remove(subscriber) }
    }

    /**
     * Publishes one exact-job background event to current subscribers. Ordinary
     * subscriber Exceptions are logged and ignored (strictly observational);
     * an Error propagates to the producer per the documented fatal-isolation
     * exception above. Safe with zero subscribers - production completion is
     * independent of this.
     */
    fun publish(event: BackgroundPipelineEvent) {
        val targets: List<BackgroundPipelineEventSubscriber> = synchronized(lock) {
            subscribers.toList()
        }
        targets.forEach { subscriber ->
            try {
                subscriber.onBackgroundEvent(event)
            } catch (fatal: Error) {
                throw fatal
            } catch (failure: Exception) {
                try {
                    Log.w(TAG, "background event observer failed for ${event.requestJobDirectory.name}", failure)
                } catch (_: Exception) {
                }
            }
        }
    }

    internal fun subscriberCountForTest(): Int = synchronized(lock) { subscribers.size }

    internal fun resetForTest() {
        synchronized(lock) { subscribers.clear() }
    }
}
