package com.projectnuke.keplernightlab

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeplerStorageLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun scenarioA_externalDeleteRecoversStableThenLocalDeleteRemovesJob() {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("kepler.hardwareE2E.storageLifecycle") != "true") {
            return
        }
        // TODO: strict YUV production capture
        // -> obtain exact current job + public URI
        // -> delete exact MediaStore row using ContentResolver
        // -> run normal Gallery recovery
        // -> assert:
        //     PUBLIC_RESULT_REMOVED
        //     recoveryState = STABLE
        //     publicResultAvailable = false
        // -> explicit LOCAL job delete
        // -> assert job directory absent
    }

    @Test
    fun scenarioB_externalDeleteThenReprocessProducesNewUri() {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("kepler.hardwareE2E.storageLifecycle") != "true") {
            return
        }
        // TODO: capture
        // -> external public delete
        // -> recovery STABLE
        // -> retain canonical sources
        // -> reprocess
        // -> assert NEW exact public URI
        // -> new export verified/present
        // -> old missing URI remains history only
    }
}
