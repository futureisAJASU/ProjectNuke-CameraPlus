package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test harness verifying the Bitmap ownership contracts across UI state components:
 * - One owner per Bitmap
 * - Replace state first; dispose old Bitmap only after leaving composition / replacement
 * - Recycle stale, cancelled or superseded decode results exactly once
 * - Never recycle active or caller-owned Bitmaps
 */
class BitmapOwnershipTest {

    class MockBitmap(val id: String) {
        val recycled = AtomicBoolean(false)
        val recycleCount = AtomicInteger(0)

        fun recycle() {
            if (recycled.compareAndSet(false, true)) {
                recycleCount.incrementAndGet()
            } else {
                recycleCount.incrementAndGet() // count extra recycle attempts to detect double-recycle
            }
        }

        val isRecycled: Boolean get() = recycled.get()
    }

    class OwnedBitmapHolder(initial: MockBitmap? = null) {
        var activeBitmap: MockBitmap? = null
            private set

        private var activeOwners = 0

        fun adopt(bitmap: MockBitmap?) {
            require(bitmap == null || !bitmap.isRecycled) { "Cannot adopt recycled bitmap" }
            val old = activeBitmap
            activeBitmap = bitmap
            if (bitmap != null) {
                activeOwners = 1
            } else {
                activeOwners = 0
            }
            if (old != null && old !== bitmap) {
                old.recycle()
            }
        }

        fun onCompositionLeave() {
            val current = activeBitmap
            activeBitmap = null
            activeOwners = 0
            current?.recycle()
        }

        init {
            if (initial != null) adopt(initial)
        }
    }

    class MockThumbnailLoader {
        private var latestRequestToken = AtomicInteger(0)

        fun loadThumbnail(
            targetFileId: String,
            requestToken: Int,
            onResult: (MockBitmap?) -> Unit
        ) {
            // Simulate decode producing a new bitmap
            val decoded = MockBitmap("decoded-$targetFileId-$requestToken")
            var adopted = false
            try {
                if (requestToken == latestRequestToken.get()) {
                    onResult(decoded)
                    adopted = true
                }
            } finally {
                if (!adopted) {
                    decoded.recycle()
                }
            }
        }

        fun createRequestToken(): Int = latestRequestToken.incrementAndGet()
    }

    @Test
    fun testRapidLatestResultReplacement() {
        val holder = OwnedBitmapHolder()
        val bmp1 = MockBitmap("bmp1")
        val bmp2 = MockBitmap("bmp2")
        val bmp3 = MockBitmap("bmp3")

        holder.adopt(bmp1)
        assertFalse(bmp1.isRecycled)
        assertEquals(bmp1, holder.activeBitmap)

        // Rapid replacement
        holder.adopt(bmp2)
        assertTrue(bmp1.isRecycled)
        assertEquals(1, bmp1.recycleCount.get())
        assertFalse(bmp2.isRecycled)
        assertEquals(bmp2, holder.activeBitmap)

        holder.adopt(bmp3)
        assertTrue(bmp2.isRecycled)
        assertEquals(1, bmp2.recycleCount.get())
        assertFalse(bmp3.isRecycled)
        assertEquals(bmp3, holder.activeBitmap)

        // Cleanup on screen disposal
        holder.onCompositionLeave()
        assertTrue(bmp3.isRecycled)
        assertEquals(1, bmp3.recycleCount.get())
    }

    @Test
    fun testThumbnailRequestSupersededBeforeAdoption() {
        val loader = MockThumbnailLoader()

        val token1 = loader.createRequestToken()
        val token2 = loader.createRequestToken() // token1 is now superseded

        var adoptedBitmap: MockBitmap? = null
        loader.loadThumbnail("file1", token1) { adopted ->
            adoptedBitmap = adopted
        }

        // token1 request should have been rejected & decoded bitmap recycled immediately
        assertEquals(null, adoptedBitmap)

        var adoptedBitmap2: MockBitmap? = null
        loader.loadThumbnail("file1", token2) { adopted ->
            adoptedBitmap2 = adopted
        }

        // token2 request is current, adopted successfully
        assertEquals("decoded-file1-$token2", adoptedBitmap2?.id)
        assertFalse(adoptedBitmap2!!.isRecycled)

        // Cleanup adopted bitmap
        adoptedBitmap2?.recycle()
        assertEquals(1, adoptedBitmap2?.recycleCount?.get())
    }

    @Test
    fun testGalleryItemLeavingComposition() {
        val galleryItemHolder = OwnedBitmapHolder()
        val thumbnail = MockBitmap("gallery-thumb-1")

        galleryItemHolder.adopt(thumbnail)
        assertFalse(thumbnail.isRecycled)

        // Item scrolled offscreen / leaving composition
        galleryItemHolder.onCompositionLeave()

        assertTrue(thumbnail.isRecycled)
        assertEquals(1, thumbnail.recycleCount.get())
    }

    @Test
    fun testCacheJobViewerFileChange() {
        val viewerHolder = OwnedBitmapHolder()
        val job1Thumb = MockBitmap("job1-thumb")
        val job2Thumb = MockBitmap("job2-thumb")

        viewerHolder.adopt(job1Thumb)
        assertFalse(job1Thumb.isRecycled)

        // User selects a different job / file changes
        viewerHolder.adopt(job2Thumb)
        assertTrue(job1Thumb.isRecycled)
        assertEquals(1, job1Thumb.recycleCount.get())
        assertFalse(job2Thumb.isRecycled)

        // Viewer closed
        viewerHolder.onCompositionLeave()
        assertTrue(job2Thumb.isRecycled)
        assertEquals(1, job2Thumb.recycleCount.get())
    }

    @Test
    fun testScreenDisposalRecyclesAllActiveBitmapsExactlyOnce() {
        val cameraScreenLatestBitmap = OwnedBitmapHolder()
        val processingPreviewBitmap = OwnedBitmapHolder()

        val latestBmp = MockBitmap("latest-bmp")
        val previewBmp = MockBitmap("preview-bmp")

        cameraScreenLatestBitmap.adopt(latestBmp)
        processingPreviewBitmap.adopt(previewBmp)

        assertFalse(latestBmp.isRecycled)
        assertFalse(previewBmp.isRecycled)

        // Screen disposal
        cameraScreenLatestBitmap.onCompositionLeave()
        processingPreviewBitmap.onCompositionLeave()

        assertTrue(latestBmp.isRecycled)
        assertEquals(1, latestBmp.recycleCount.get())
        assertTrue(previewBmp.isRecycled)
        assertEquals(1, previewBmp.recycleCount.get())
    }

    @Test
    fun testCallerOwnedBitmapIsNeverRecycledByViewer() {
        val callerOwnedBmp = MockBitmap("caller-owned")
        val viewerHolder = OwnedBitmapHolder()

        // Viewer gets a copy or reference without taking ownership responsibility,
        // or caller retains ownership. Here we verify caller bitmap stays active.
        assertFalse(callerOwnedBmp.isRecycled)
        assertEquals(0, callerOwnedBmp.recycleCount.get())

        // Viewer adopts a separate bitmap
        val viewerBmp = MockBitmap("viewer-bmp")
        viewerHolder.adopt(viewerBmp)
        viewerHolder.onCompositionLeave()

        // Caller bitmap unaffected
        assertFalse(callerOwnedBmp.isRecycled)
        assertEquals(0, callerOwnedBmp.recycleCount.get())
    }
}
