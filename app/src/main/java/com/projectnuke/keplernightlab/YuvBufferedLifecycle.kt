package com.projectnuke.keplernightlab

internal class YuvBufferedLifecycle {

    enum class State { RETAINED, ENCODING, RELEASED }

    private data class Entry(var state: State = State.RETAINED)

    private val lock = Any()
    private val items = linkedMapOf<YuvPngWorkItem, Entry>()
    private var closed = false

    fun isClosed(): Boolean = synchronized(lock) { closed }

    fun retainedCount(): Int = synchronized(lock) {
        items.count { it.value.state == State.RETAINED }
    }

    fun encodingCount(): Int = synchronized(lock) {
        items.count { it.value.state == State.ENCODING }
    }

    fun trackedCount(): Int = synchronized(lock) { items.size }

    fun tryRegister(item: YuvPngWorkItem): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (item in items) error("YUV work item already tracked by buffered lifecycle")
        items[item] = Entry()
        true
    }

    fun beginEncoding(item: YuvPngWorkItem): Boolean = synchronized(lock) {
        val entry = items[item] ?: return@synchronized false
        if (entry.state != State.RETAINED) return@synchronized false
        entry.state = State.ENCODING
        true
    }

    fun settleEncoding(item: YuvPngWorkItem, accounting: YuvCaptureAccounting) {
        val disposed = synchronized(lock) {
            val entry = items.remove(item) ?: return@synchronized true
            entry.state = State.RELEASED
            false
        }
        if (disposed) return
        item.settleBufferedAccounting(accounting)
        item.dispose(accounting)
    }

    fun closeAndDrainRetained(): List<YuvPngWorkItem> = synchronized(lock) {
        closed = true
        val drained = mutableListOf<YuvPngWorkItem>()
        val iter = items.iterator()
        while (iter.hasNext()) {
            val (item, entry) = iter.next()
            if (entry.state == State.RETAINED) {
                entry.state = State.RELEASED
                iter.remove()
                drained.add(item)
            }
        }
        drained
    }

    fun snapshotRetainedByFrameIndex(): List<YuvPngWorkItem> = synchronized(lock) {
        items.entries
            .filter { it.value.state == State.RETAINED }
            .map { it.key }
            .sortedBy { it.frameIndex }
    }
}