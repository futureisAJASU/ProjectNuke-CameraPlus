package com.projectnuke.keplernightlab

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RawProcessingOperationTest {
    @Test
    fun borrowedRawWrapperScopeDoesNotReleaseOuterOperation() {
        val dir = Files.createTempDirectory("raw-processing-operation").toFile()
        val outer = KeplerJobMetadata.acquireOperation(dir)
        assertNotNull(outer)
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "RAW_REPROCESS"))
            val nested = acquireRawProcessingOperation(dir, outer)
            assertNotNull(nested)
            nested!!.release()

            assertTrue(KeplerJobMetadata.isOperationOwner(dir, outer!!))
            val competing = KeplerJobMetadata.acquireOperation(dir)
            assertFalse("outer RAW reprocess operation was released too early", competing != null)
            competing?.release()
        } finally {
            outer!!.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun ownedRawWrapperScopeReleasesExactlyOnce() {
        val dir = Files.createTempDirectory("raw-processing-operation-owned").toFile()
        try {
            val scope = acquireRawProcessingOperation(dir)
            assertNotNull(scope)
            scope!!.release()
            scope.release()
            assertNotNull(KeplerJobMetadata.acquireOperation(dir)?.also { it.release() })
        } finally {
            dir.deleteRecursively()
        }
    }
}
