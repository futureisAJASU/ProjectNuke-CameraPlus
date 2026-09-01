package com.projectnuke.keplernightlab

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

@RunWith(AndroidJUnit4::class)
class R4DataPersistenceTest {
    @Test
    fun checkR3FileExists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "r3-gallery-cold")
        val file = File(dir, "cohort.json")
        println("DEBUG: filesDir=${context.filesDir.absolutePath}")
        println("DEBUG: dir.exists()=${dir.exists()}")
        println("DEBUG: file.exists()=${file.exists()}")
        if (file.exists()) {
            println("DEBUG: content=${file.readText()}")
        }
        assertTrue("cohort.json must exist from previous test", file.exists())
    }
}
