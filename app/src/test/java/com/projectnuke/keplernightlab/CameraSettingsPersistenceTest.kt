package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSettingsPersistenceTest {
    @Test fun rapidUpdatesFlushOnlyLatestOnce() {
        val writes = mutableListOf<Int>()
        val store = CameraSettingsPersistenceDebouncer<Int>(writes::add)
        repeat(20) { store.update(it) }
        store.flush()
        store.flush()
        assertEquals(listOf(19), writes)
    }
}
