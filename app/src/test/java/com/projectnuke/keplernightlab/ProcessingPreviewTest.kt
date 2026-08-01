package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProcessingPreviewTest {
    @Test
    fun latestPendingRequestConflatesWhileOneRenderIsInFlight() {
        val started = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val adopted = CountDownLatch(1)
        val rendered = Collections.synchronizedList(mutableListOf<Int>())
        val recycledSources = Collections.synchronizedList(mutableListOf<Int>())
        val recycledResults = Collections.synchronizedList(mutableListOf<Int>())
        val adoptedResults = Collections.synchronizedList(mutableListOf<Int>())
        val worker = SerializedPreviewWorker<Int, Int>(
            render = { source ->
                rendered += source
                if (source == 1) {
                    started.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                source * 10
            },
            recycleSource = { recycledSources += it },
            recycleResult = { recycledResults += it },
            adopt = { result -> adoptedResults += result; adopted.countDown() }
        )
        try {
            worker.submit(1)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            worker.submit(2)
            worker.submit(3)
            releaseFirst.countDown()
            assertTrue(adopted.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), rendered)
            assertEquals(listOf(2), recycledSources.filter { it == 2 })
            assertEquals(listOf(30), adoptedResults)
            assertTrue(recycledResults.contains(10))
        } finally {
            worker.close()
            assertTrue(worker.awaitClosed(2_000))
        }
    }

    @Test
    fun closeRejectsAndRecyclesNewWork() {
        val recycled = mutableListOf<Int>()
        val worker = SerializedPreviewWorker<Int, Int>(
            render = { it },
            recycleSource = { recycled += it },
            recycleResult = {},
            adopt = {}
        )
        worker.close()
        worker.submit(7)
        assertEquals(listOf(7), recycled)
        assertTrue(worker.awaitClosed(2_000))
    }
}
