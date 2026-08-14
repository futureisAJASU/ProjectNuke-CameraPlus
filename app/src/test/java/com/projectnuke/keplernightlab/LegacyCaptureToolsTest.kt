package com.projectnuke.keplernightlab

import android.os.Environment
import android.content.ContextWrapper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

class LegacyCaptureToolsTest {
    @Test
    fun cacheClearPreservesGalleryManagedColorBurstJobs() {
        val context = RuntimeEnvironment.getApplication()
        val pictures = kotlin.io.path.createTempDirectory("kepler-cache-clear-").toFile()
        val testContext = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File = pictures
        }
        val root = File(pictures, "KeplerColorBurst")
        val job = File(root, "KPL_COLOR_BURST_mutation-barrier").apply { mkdirs() }
        try {
            File(job, "job.json").writeText("{}")
            File(job, ".processing_tx_preserved.json").writeText("evidence")
            File(job, "frame_00.yuv").writeBytes(byteArrayOf(1, 2, 3))

            deleteKeplerCache(testContext)

            assertTrue("gallery-managed ColorBurst job must not be bulk deleted", job.exists())
            assertTrue(File(job, ".processing_tx_preserved.json").exists())
        } finally {
            root.deleteRecursively()
            pictures.deleteRecursively()
        }
    }
}
