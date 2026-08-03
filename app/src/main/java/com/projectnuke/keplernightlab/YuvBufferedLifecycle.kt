package com.projectnuke.keplernightlab

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class YuvBufferedLifecycle {

    enum class State { RETAINED, ENCODING, RELEASED }

    private data class Entry(val state: AtomicReference<State>)

    private val items = ConcurrentHashMap<YuvPngWorkItem, Entry>()
    private val closed = AtomicBoolean(false)

    fun isClosed(): Boolean = closed.get()

    fun retainedCount(): Int = items.size

    fun tryRegister(item: YuvPngWorkItem): Boolean {
        if (closed.get()) return false
        val entry = Entry(AtomicReference(State.RETAINED))
        val prior = items.putIfAbsent(item, entry)
        if (prior != null) {
            error("YUV work item already tracked by buffered lifecycle")
        }
        if (closed.get()) {
            if (items.remove(item) != null) {
                return false
            }
            return false
        }
        return true
    }

    fun beginEncoding(item: YuvPngWorkItem): Boolean {
        val entry = items[item] ?: return false
        return entry.state.compareAndSet(State.RETAINED, State.ENCODING)
    }

    fun settleEncoding(item: YuvPngWorkItem, accounting: YuvCaptureAccounting) {
        val entry = items.remove(item) ?: return
        entry.state.set(State.RELEASED)
        item.settleBufferedAccounting(accounting)
        item.dispose(accounting)
    }

    fun closeAndDrainRetained(): List<YuvPngWorkItem> {
        closed.set(true)
        val drained = mutableListOf<YuvPngWorkItem>()
        val iter = items.entries.iterator()
        while (iter.hasNext()) {
            val (item, entry) = iter.next()
            if (entry.state.compareAndSet(State.RETAINED, State.RELEASED)) {
                iter.remove()
                drained.add(item)
            }
        }
        return drained
    }

    fun snapshotRetainedByFrameIndex(): List<YuvPngWorkItem> {
        if (items.isEmpty()) return emptyList()
        return items.entries
            .asSequence()
            .filter { it.value.state.get() == State.RETAINED }
            .map { it.key }
            .sortedBy { it.frameIndex }
            .toList()
    }
}