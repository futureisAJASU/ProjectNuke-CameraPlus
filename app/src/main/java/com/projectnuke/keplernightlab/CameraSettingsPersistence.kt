package com.projectnuke.keplernightlab

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class CameraSettingsPersistenceDebouncer<T>(
    private val write: (T) -> Unit,
    private val delayMs: Long = 250L
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "KeplerSettingsPersistence").apply { isDaemon = true }
    }
    private var pending: T? = null
    private var flushed = false
    private var scheduled: ScheduledFuture<*>? = null

    @Synchronized
    fun update(value: T) {
        pending = value
        flushed = false
        scheduled?.cancel(false)
        scheduled = executor.schedule({ flush() }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun flush() {
        scheduled?.cancel(false)
        scheduled = null
        if (flushed) return
        val value = pending ?: return
        write(value)
        flushed = true
    }

    @Synchronized
    fun close() {
        flush()
        executor.shutdownNow()
    }
}
