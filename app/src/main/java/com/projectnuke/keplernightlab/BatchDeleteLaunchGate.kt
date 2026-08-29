package com.projectnuke.keplernightlab

import java.util.concurrent.atomic.AtomicBoolean

internal class BatchDeleteLaunchGate {
    private val busy = AtomicBoolean(false)

    fun tryStart(): Boolean = busy.compareAndSet(false, true)

    fun finish() { busy.set(false) }

    val isBusy: Boolean get() = busy.get()
}
