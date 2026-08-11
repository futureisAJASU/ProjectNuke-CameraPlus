package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
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

    @Test fun renderFailureDoesNotPoisonLaterLatestRequest() {
        val adopted = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val errorReported = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val recycledSources = Collections.synchronizedList(mutableListOf<Int>())
        val recycledSourcesLatch = CountDownLatch(2)
        val worker = SerializedPreviewWorker<Int, Int>(
            render = { value -> if (value == 1) { firstStarted.countDown(); error("expected") } else value },
            recycleSource = { value ->
                recycledSources.add(value)
                recycledSourcesLatch.countDown()
            },
            recycleResult = {},
            adopt = { adopted.countDown() },
            onError = { error -> errors.add(error); errorReported.countDown() }
        )
        try {
            worker.submit(1)
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            worker.submit(2)
            assertTrue(adopted.await(2, TimeUnit.SECONDS))
            assertTrue(errorReported.await(2, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertTrue(recycledSourcesLatch.await(2, TimeUnit.SECONDS))
            assertTrue(recycledSources.containsAll(listOf(1, 2)))
        } finally {
            worker.close()
            assertTrue(worker.awaitClosed())
        }
    }

    @Test fun adoptionFailureRecyclesResultAndKeepsWorkerAlive() {
        val adopted = CountDownLatch(1)
        val firstAdoption = CountDownLatch(1)
        val errors = mutableListOf<Throwable>()
        val recycled = mutableListOf<Int>()
        val worker = SerializedPreviewWorker<Int, Int>(
            render = { it },
            recycleSource = {},
            recycleResult = recycled::add,
            adopt = { value -> if (value == 1) { firstAdoption.countDown(); error("adoption") } else adopted.countDown() },
            onError = errors::add
        )
        try {
            worker.submit(1)
            assertTrue(firstAdoption.await(2, TimeUnit.SECONDS))
            worker.submit(2)
            assertTrue(adopted.await(2, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertTrue(recycled.contains(1))
        } finally {
            worker.close()
            assertTrue(worker.awaitClosed())
        }
    }
}
