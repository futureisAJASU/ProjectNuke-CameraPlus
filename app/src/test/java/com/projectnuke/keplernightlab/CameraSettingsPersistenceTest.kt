package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraSettingsPersistenceTest {
    @Test fun rapidUpdatesFlushOnlyLatestOnce() {
        val writes = mutableListOf<Int>()
        val store = CameraSettingsPersistenceDebouncer<Int>(writes::add)
        repeat(20) { store.update(it) }
        store.flush()
        store.flush()
        assertEquals(listOf(19), writes)
    }

    @Test fun debounceWritesLatestValueWithoutDisposal() {
        val writes = mutableListOf<Int>()
        val latch = CountDownLatch(1)
        val store = CameraSettingsPersistenceDebouncer<Int>(
            write = { value -> writes += value; latch.countDown() },
            delayMs = 10L
        )
        try {
            repeat(20) { store.update(it) }
            assertEquals(true, latch.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(19), writes)
        } finally {
            store.close()
        }
    }
}
