package com.projectnuke.keplernightlab

internal class YuvBufferedLifecycle {

    enum class State { RETAINED, ENCODING, SETTLING, RELEASED }

    enum class SettlementResult { STARTED, ALREADY_SETTLING, ALREADY_RELEASED, INVALID_STATE, UNKNOWN }

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

    fun settlingCount(): Int = synchronized(lock) {
        items.count { it.value.state == State.SETTLING }
    }

    fun activeEncodingOwnershipCount(): Int = synchronized(lock) {
        items.count { it.value.state == State.ENCODING || it.value.state == State.SETTLING }
    }

    fun trackedCount(): Int = synchronized(lock) {
        items.count { it.value.state != State.RELEASED }
    }

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

    fun startSettling(item: YuvPngWorkItem): SettlementResult = synchronized(lock) {
        val entry = items[item]
        when (entry?.state) {
            State.ENCODING -> {
                entry.state = State.SETTLING
                SettlementResult.STARTED
            }
            State.SETTLING -> SettlementResult.ALREADY_SETTLING
            State.RELEASED -> SettlementResult.ALREADY_RELEASED
            State.RETAINED -> SettlementResult.INVALID_STATE
            null -> SettlementResult.UNKNOWN
        }
    }

    fun finishSettling(item: YuvPngWorkItem): Boolean = synchronized(lock) {
        val entry = items[item] ?: return@synchronized false
        check(entry.state == State.SETTLING) { "finishSettling from ${entry.state}" }
        entry.state = State.RELEASED
        true
    }

    @Deprecated("Use startSettling + finishSettling pair", ReplaceWith("startSettling/finishSettling"))
    fun settleEncoding(item: YuvPngWorkItem, accounting: YuvCaptureAccounting) {
        val result = startSettling(item)
        if (result != SettlementResult.STARTED) return
        try {
            item.settleBufferedAccounting(accounting)
            item.dispose(accounting)
        } finally {
            finishSettling(item)
        }
    }

    fun closeAndDrainRetained(): List<YuvPngWorkItem> = synchronized(lock) {
        closed = true
        val drained = mutableListOf<YuvPngWorkItem>()
        val iter = items.iterator()
        while (iter.hasNext()) {
            val (item, entry) = iter.next()
            if (entry.state == State.RETAINED) {
                entry.state = State.RELEASED
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