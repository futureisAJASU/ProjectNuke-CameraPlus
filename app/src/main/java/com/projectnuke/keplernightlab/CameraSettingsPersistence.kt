package com.projectnuke.keplernightlab

internal class CameraSettingsPersistenceDebouncer<T>(
    private val write: (T) -> Unit
) {
    private var pending: T? = null
    private var flushed = false

    fun update(value: T) {
        pending = value
        flushed = false
    }

    fun flush() {
        if (flushed) return
        val value = pending ?: return
        write(value)
        flushed = true
    }
}
