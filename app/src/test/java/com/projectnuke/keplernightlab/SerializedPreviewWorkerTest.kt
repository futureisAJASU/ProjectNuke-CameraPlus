package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedPreviewWorkerTest {
    @Test fun rendersAreSerializedAndOnlyLatestPendingRequestAdopted() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val concurrent = AtomicInteger()
        val maximum = AtomicInteger()
        val adopted = mutableListOf<Int>()
        val recycledSources = mutableListOf<Int>()
        val recycledResults = mutableListOf<Int>()
        val adoptedLatch = CountDownLatch(1)
        val worker = SerializedPreviewWorker<Int, Int>(
            render = {
                val now = concurrent.incrementAndGet()
                maximum.updateAndGet { maxOf(it, now) }
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                concurrent.decrementAndGet()
                it
            },
            recycleSource = recycledSources::add,
            recycleResult = recycledResults::add,
            adopt = { value -> adopted.add(value); adoptedLatch.countDown() }
        )
        try {
            worker.submit(1)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.submit(2)
            worker.submit(3)
            release.countDown()
            assertTrue(adoptedLatch.await(2, TimeUnit.SECONDS))
            worker.close()
            assertTrue(worker.awaitClosed())
            assertEquals(1, maximum.get())
            assertEquals(listOf(3), adopted)
            assertTrue(recycledSources.contains(2))
            assertTrue(recycledResults.contains(1))
        } finally {
            worker.close()
        }
    }
}
