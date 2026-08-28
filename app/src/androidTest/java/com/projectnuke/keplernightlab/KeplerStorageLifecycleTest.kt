package com.projectnuke.keplernightlab

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import java.io.File
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiObject2
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

private const val SCENARIO_B_RECONCILIATION_MAX_PASSES = 3

@RunWith(AndroidJUnit4::class)
class KeplerStorageLifecycleTest {
    private val cameraPermissionRule = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    instrumentation.uiAutomation.grantRuntimePermission(
                        instrumentation.targetContext.packageName,
                        Manifest.permission.CAMERA
                    )
                    assertEquals(
                        PackageManager.PERMISSION_GRANTED,
                        ContextCompat.checkSelfPermission(
                            instrumentation.targetContext,
                            Manifest.permission.CAMERA
                        )
                    )
                    base.evaluate()
                }
            }
    }

    private val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(cameraPermissionRule)
        .around(activityRule)

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val targetContext: Context
        get() = instrumentation.targetContext

    private val device: UiDevice
        get() = UiDevice.getInstance(instrumentation)

    @Test
    fun scenarioA_externalDeleteRecoversStableThenLocalDeleteRemovesJob() {
        assumeTrue("kepler.hardwareE2E.storageLifecycle=true is required", storageLifecycleEnabled())
        assumeTrue("usable camera is required", hasUsableCamera())
        assumeTrue(
            "Physical-device UI test requires an interactive, unlocked device. " +
                "PowerManager.isInteractive=${deviceIsInteractive()} " +
                "KeyguardManager.isKeyguardLocked=${deviceIsKeyguardLocked()} " +
                "ActivityScenario state=${activityRule.scenario.state} " +
                "CAMERA permission=${ContextCompat.checkSelfPermission(targetContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED}",
            deviceIsInteractive() && !deviceIsKeyguardLocked()
        )

        var testRunId: String? = null
        var testJobDir: File? = null
        var testExportUri: String? = null
        var captureCompleted = false
        var providerDeleteCompleted = false
        var recoveryCompleted = false
        var localDeleteCompleted = false

        val originalSettings = CameraSettingsStore.load(targetContext)
        try {
            ensureActivityReadyForUi()
            configureSettings(PipelineMode.YUV_NIGHT_FUSION.name)
            val previousRunId = HardwareE2EReportStore.readLatest(targetContext)?.runId
            val invocationStart = System.currentTimeMillis()
            val shutter = awaitUiObject("kepler.camera.shutter", 5_000L)
            assertTrue("shutter not enabled", shutter.isEnabled)
            shutter.click()
            val report = awaitExactTerminalReport(
                previousRunId = previousRunId,
                invocationStart = invocationStart,
                expectedPipeline = PipelineMode.YUV_NIGHT_FUSION.name
            )
            assertSuccessfulSmoke(report, PipelineMode.YUV_NIGHT_FUSION.name)
            captureCompleted = true

            testRunId = report.runId
            testJobDir = File(report.latestJobDirectory!!)
            val job = KeplerJobMetadata.read(testJobDir)
            testExportUri = job.optString("exportUri")
            assertTrue("exportUri must be non-blank after capture", testExportUri.isNotBlank())

            val uri = Uri.parse(testExportUri)
            assertTrue("MediaStore row must exist before deletion", mediaStoreRowExists(targetContext, uri))

            val deleted = targetContext.contentResolver.delete(uri, null, null)
            assertEquals("Exact MediaStore row must be deleted", 1L, deleted.toLong())
            assertFalse("MediaStore row must be absent after deletion", mediaStoreRowExists(targetContext, uri))
            providerDeleteCompleted = true

            KeplerRecoveryCoordinator.reconcileAgain(targetContext).get()

            val updatedJob = KeplerJobMetadata.read(testJobDir)
            val summary = readKeplerGalleryJob(testJobDir!!)
            assertEquals("STABLE", summary.recoveryState)
            assertEquals("REMOVED_EXTERNALLY", summary.metadata?.optString("exportStatus"))
            assertFalse(summary.publicResultAvailable)
            assertFalse(updatedJob.optBoolean("publicResultAvailable", true))
            assertFalse("exportUri key must be absent after external removal", updatedJob.has("exportUri"))
            assertTrue("exportUri value must be blank after external removal", updatedJob.optString("exportUri").isBlank())
            assertFalse("galleryPublicExportLinkage key must be absent after external removal", updatedJob.has("galleryPublicExportLinkage"))
            assertTrue("galleryPublicExportLinkage value must be blank after external removal", updatedJob.optString("galleryPublicExportLinkage").isBlank())
            assertEquals(
                "lastVerifiedExportUri must preserve original exportUri",
                testExportUri,
                updatedJob.optString("lastVerifiedExportUri")
            )

            recoveryCompleted = true

            val gate = KeplerJobMetadata.inspectRecoveryMutationGate(
                testJobDir, JobRecoveryMutationIntent.JOB_DELETE
            )
            assertEquals("JOB_DELETE must be ALLOWED after external removal", JobRecoveryMutationGateOutcome.ALLOWED, gate)

            val deleteResult = deleteKeplerGalleryJob(targetContext, testJobDir)
            assertTrue("Local job delete must succeed", deleteResult.isSuccess)
            localDeleteCompleted = true

            assertFalse("Job directory must be gone after local delete", testJobDir!!.exists())
            assertTrue("All Scenario A milestones must complete", captureCompleted && providerDeleteCompleted && recoveryCompleted && localDeleteCompleted)
        } finally {
            if (testExportUri != null) {
                try {
                    targetContext.contentResolver.delete(Uri.parse(testExportUri), null, null)
                } catch (_: Exception) { }
            }
            CameraSettingsStore.save(targetContext, originalSettings)
        }
    }

    @Test
    fun scenarioB_externalDeleteThenReprocessProducesNewUri() {
        assumeTrue("kepler.hardwareE2E.storageLifecycle=true is required", storageLifecycleEnabled())
        assumeTrue("usable camera is required", hasUsableCamera())
        assumeTrue(
            "Physical-device UI test requires an interactive, unlocked device. " +
                "PowerManager.isInteractive=${deviceIsInteractive()} " +
                "KeyguardManager.isKeyguardLocked=${deviceIsKeyguardLocked()} " +
                "ActivityScenario state=${activityRule.scenario.state} " +
                "CAMERA permission=${ContextCompat.checkSelfPermission(targetContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED}",
            deviceIsInteractive() && !deviceIsKeyguardLocked()
        )

        var testRunId: String? = null
        var testJobDir: File? = null
        var originalExportUri: String? = null
        var reprocessExportUri: String? = null
        var reprocessTransactionSucceeded = false
        var reprocessWarnings: List<String>? = null

        val originalSettings = CameraSettingsStore.load(targetContext)
        try {
            ensureActivityReadyForUi()
            configureSettings(PipelineMode.YUV_NIGHT_FUSION.name)
            val previousRunId = HardwareE2EReportStore.readLatest(targetContext)?.runId
            val invocationStart = System.currentTimeMillis()
            val shutter = awaitUiObject("kepler.camera.shutter", 5_000L)
            assertTrue("shutter not enabled", shutter.isEnabled)
            shutter.click()
            val report = awaitExactTerminalReport(
                previousRunId = previousRunId,
                invocationStart = invocationStart,
                expectedPipeline = PipelineMode.YUV_NIGHT_FUSION.name
            )
            assertSuccessfulSmoke(report, PipelineMode.YUV_NIGHT_FUSION.name)

            testRunId = report.runId
            testJobDir = File(report.latestJobDirectory!!)
            val job = KeplerJobMetadata.read(testJobDir)
            originalExportUri = job.optString("exportUri")
            assertTrue("original exportUri must be non-blank", originalExportUri.isNotBlank())

            val originalUri = Uri.parse(originalExportUri)
            assertTrue("Original MediaStore row must exist", mediaStoreRowExists(targetContext, originalUri))

            assertEquals(1L, targetContext.contentResolver.delete(originalUri, null, null).toLong())
            assertFalse("Original MediaStore row must be absent", mediaStoreRowExists(targetContext, originalUri))

            KeplerRecoveryCoordinator.reconcileAgain(targetContext).get()

            val afterRecovery = readKeplerGalleryJob(testJobDir!!)
            assertEquals("STABLE", afterRecovery.recoveryState)
            assertEquals("REMOVED_EXTERNALLY", afterRecovery.metadata?.optString("exportStatus"))
            assertFalse(afterRecovery.publicResultAvailable)

            val capability = detectReprocessCapability(targetContext, testJobDir)
            assertTrue("Reprocess must be allowed after external removal", capability.canReprocess)

            val reprocessResult = runBlocking {
                reprocessKeplerGalleryJob(targetContext, testJobDir, FinalOutputFormat.HEIF) { progress ->
                    println("REPROCESS_PROGRESS=$progress")
                }
            }
            assertTrue("Reprocess must succeed", reprocessResult.isSuccess)
            val reprocessJob = reprocessResult.getOrNull()
            assertNotNull(reprocessJob)
            reprocessTransactionSucceeded = true
            reprocessWarnings = reprocessJob?.warnings

            // The generic reprocess Result only proves the LOCAL transaction committed:
            // COMMITTED_PARTIAL (local result, no verified public row) is a local success.
            // Scenario B acceptance requires a VERIFIED public export, asserted here from durable
            // export truth BEFORE any MediaStore row query.
            var currentJob = KeplerJobMetadata.read(testJobDir)
            if (currentJob.optString("exportCommitState") in setOf(
                    GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name,
                    GalleryExportCommitState.UNKNOWN.name
                )
            ) {
                for (pass in 1..SCENARIO_B_RECONCILIATION_MAX_PASSES) {
                    println(
                        "STORAGE_B_RECONCILIATION_PASS=$pass state=${currentJob.optString("exportCommitState")}"
                    )
                    KeplerRecoveryCoordinator.reconcileAgain(targetContext).get()
                    settleMediaStoreExportDebt(targetContext, testJobDir)
                    currentJob = KeplerJobMetadata.read(testJobDir)
                    if (currentJob.optString("exportCommitState") == GalleryExportCommitState.VERIFIED.name) break
                }
            }
            assertScenarioBVerifiedPublicContract(
                currentJob, testJobDir, originalExportUri, reprocessTransactionSucceeded, reprocessWarnings
            )
            reprocessExportUri = currentJob.optString("exportUri")
            assertTrue("New exportUri must be non-blank", reprocessExportUri.isNotBlank())
            assertTrue("New URI must differ from original", reprocessExportUri != originalExportUri)

            val newUri = Uri.parse(reprocessExportUri)
            assertTrue("New MediaStore row must exist", mediaStoreRowExists(targetContext, newUri))

            val afterReprocess = readKeplerGalleryJob(testJobDir!!)
            assertEquals("STABLE", afterReprocess.recoveryState)
            assertTrue(afterReprocess.publicResultAvailable)
            assertTrue(afterReprocess.metadata?.optBoolean("exportVerified") == true)

            KeplerRecoveryCoordinator.reconcileAgain(targetContext).get()
            val afterSecondRecovery = readKeplerGalleryJob(testJobDir!!)
            assertEquals("STABLE", afterSecondRecovery.recoveryState)
            assertTrue("Second recovery must preserve new current result", afterSecondRecovery.publicResultAvailable)
            assertTrue("New export must remain verified", afterSecondRecovery.metadata?.optBoolean("exportVerified") == true)
            assertFalse("Old missing URI must not set REMOVED_EXTERNALLY on current result",
                afterSecondRecovery.metadata?.optString("exportStatus") == "REMOVED_EXTERNALLY")

            val secondRecoveryMetadata = afterSecondRecovery.metadata ?: throw AssertionError("Second recovery metadata is null")
            assertTrue("metadata.exportUri key must exist after second recovery", secondRecoveryMetadata.has("exportUri"))
            assertEquals("metadata.exportUri must remain URI_B", reprocessExportUri, secondRecoveryMetadata.optString("exportUri"))

            val linkage = secondRecoveryMetadata.optString("galleryPublicExportLinkage")
            if (linkage.isNotBlank()) {
                assertEquals("galleryPublicExportLinkage must remain URI_B", reprocessExportUri, linkage)
            }
        } catch (failure: Throwable) {
            // Failure artifacts must survive long enough to diagnose: print the bounded public
            // export snapshot before cleanup removes the job directory.
            try {
                val dir = testJobDir
                if (dir != null && dir.exists()) {
                    println(
                        "STORAGE_B_DIAGNOSTIC=" + buildReprocessPublicExportDiagnostic(
                            dir, originalExportUri, reprocessTransactionSucceeded, reprocessWarnings
                        )
                    )
                }
            } catch (_: Exception) { }
            throw failure
        } finally {
            if (originalExportUri != null) {
                try {
                    targetContext.contentResolver.delete(Uri.parse(originalExportUri), null, null)
                } catch (_: Exception) { }
            }
            if (reprocessExportUri != null) {
                try {
                    targetContext.contentResolver.delete(Uri.parse(reprocessExportUri), null, null)
                } catch (_: Exception) { }
            }

            if (testJobDir != null && testJobDir.exists()) {
                try {
                    KeplerRecoveryCoordinator.reconcileAgain(targetContext).get()
                    val gate = KeplerJobMetadata.inspectRecoveryMutationGate(
                        testJobDir, JobRecoveryMutationIntent.JOB_DELETE
                    )
                    if (gate == JobRecoveryMutationGateOutcome.ALLOWED) {
                        val cleanupResult = deleteKeplerGalleryJob(targetContext, testJobDir)
                        if (!cleanupResult.isSuccess) {
                            println("TEST_CLEANUP: deleteKeplerGalleryJob failed: $cleanupResult")
                        }
                    } else {
                        println("TEST_CLEANUP: JOB_DELETE gate not ALLOWED, gate=$gate")
                    }
                } catch (e: Exception) {
                    println("TEST_CLEANUP: local cleanup failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            CameraSettingsStore.save(targetContext, originalSettings)
        }
    }

    /**
     * Scenario B acceptance: the reprocess must leave a VERIFIED public export (not merely a
     * committed local transaction). Every clause is asserted from durable job.json export truth;
     * a violation throws with the bounded public-export diagnostic attached.
     */
    private fun assertScenarioBVerifiedPublicContract(
        job: JSONObject,
        jobDir: File?,
        originalExportUri: String?,
        reprocessTransactionSucceeded: Boolean,
        reprocessResultWarnings: List<String>?
    ) {
        val state = ReprocessPublicExportState.fromDurableMetadata(job)
        val rawExportUri = job.optString("exportUri")
        val violations = mutableListOf<String>()
        if (job.optString("currentPipelineStage") != "COMPLETE") {
            violations.add("currentPipelineStage=${job.optString("currentPipelineStage")}, expected COMPLETE")
        }
        if (job.optString("reprocessStatus") != "COMPLETE") {
            violations.add("reprocessStatus=${job.optString("reprocessStatus")}, expected COMPLETE")
        }
        if (job.optString("exportStatus") != "EXPORTED") {
            violations.add("exportStatus=${job.optString("exportStatus")}, expected EXPORTED")
        }
        if (state.commitState != GalleryExportCommitState.VERIFIED) {
            violations.add("exportCommitState=${state.commitState.name}, expected VERIFIED")
        }
        if (!state.verified) {
            violations.add("exportVerified != true")
        }
        if (!job.optBoolean("galleryExportCommitted", false)) {
            violations.add("galleryExportCommitted != true")
        }
        if (!job.has("exportUri") || job.isNull("exportUri")) {
            violations.add("exportUri key is missing or JSON null")
        }
        if (rawExportUri.isBlank() || rawExportUri == "null") {
            violations.add("exportUri raw value is blank or coerced \"null\": $rawExportUri")
        }
        if (state.uri == null) {
            violations.add("exportUri is not a row-level content URI: raw=$rawExportUri")
        }
        if (state.uri != null && rawExportUri == originalExportUri) {
            violations.add("exportUri still equals the original deleted URI")
        }
        if (violations.isEmpty()) return
        val diagnostic = if (jobDir != null && jobDir.exists()) {
            buildReprocessPublicExportDiagnostic(
                jobDir, originalExportUri, reprocessTransactionSucceeded, reprocessResultWarnings
            )
        } else {
            JSONObject().put("jobDirExists", jobDir?.exists()).toString()
        }
        throw AssertionError(
            "Scenario B verified-public contract failed: ${violations.joinToString("; ")}; " +
                "STORAGE_B_DIAGNOSTIC=$diagnostic"
        )
    }

    private fun storageLifecycleEnabled(): Boolean =
        InstrumentationRegistry.getArguments()
            .getString("kepler.hardwareE2E.storageLifecycle")
            ?.equals("true", ignoreCase = true) == true

    private fun deviceIsInteractive(): Boolean {
        val powerManager = targetContext.getSystemService(PowerManager::class.java)
        return powerManager.isInteractive
    }

    private fun deviceIsKeyguardLocked(): Boolean {
        val keyguardManager = targetContext.getSystemService(KeyguardManager::class.java)
        return keyguardManager.isKeyguardLocked
    }

    private fun ensureActivityReadyForUi() {
        val scenario = activityRule.scenario
        if (scenario.state != Lifecycle.State.RESUMED) {
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        awaitUiObject("kepler.camera.root", 10_000L)
    }

    private fun awaitUiObject(resourceId: String, timeoutMs: Long): UiObject2 {
        val obj = device.wait(Until.findObject(By.res(resourceId)), timeoutMs)
        if (obj == null) {
            throw AssertionError("Timed out after ${timeoutMs}ms waiting for UiObject with resourceId: $resourceId")
        }
        return obj
    }

    private fun configureSettings(pipelineModeName: String) {
        val settings = CameraSettingsStore.load(targetContext).copy(
            selectedResolutionName = CaptureResolutionMode.MP12.name,
            selectedLensSlotName = LensSlot.MAIN_1X.name,
            pipelineModeName = pipelineModeName,
            frameCountModeName = FrameCountMode.MANUAL.name,
            manualFrames = 4,
            captureModeName = CaptureMode.MULTI_FRAME.name
        )
        CameraSettingsStore.save(targetContext, settings)
        activityRule.scenario.recreate()
        ensureActivityReadyForUi()
    }

    private fun awaitExactTerminalReport(
        previousRunId: String?,
        invocationStart: Long,
        expectedPipeline: String
    ): HardwareE2ERunReport {
        var runId: String? = null
        try {
            awaitCondition(180_000L) {
                if (runId == null) {
                    runId = HardwareE2EReportStore.findLatestAfter(
                        context = targetContext,
                        previousRunId = previousRunId,
                        invocationStartWallClock = invocationStart,
                        expectedScenario = "production_main_camera_screen",
                        expectedPipeline = expectedPipeline
                    )?.runId
                }
                runId?.let { exactId ->
                    HardwareE2EReportStore.read(targetContext, exactId)?.let { report ->
                        report.terminalEvent != null &&
                            (report.finalJob != null || report.failure != null || report.status == HardwareE2EClassification.SKIPPED_UNSUPPORTED)
                    }
                } == true
            }
        } catch (timeout: Throwable) {
            throw AssertionError(
                "Timed out waiting for exact hardware run. " +
                    "previousRunId=$previousRunId invocationStart=$invocationStart " +
                    "lockedRunId=$runId latest=${HardwareE2EReportStore.readLatest(targetContext)?.toJson()?.toString(2)}",
                timeout
            )
        }
        val exactRunId = runId ?: throw AssertionError("No new exact hardware run was observed")
        val report = HardwareE2EReportStore.read(targetContext, exactRunId)
            ?: throw AssertionError("Exact hardware report disappeared for runId=$exactRunId")
        println("HARDWARE_E2E_RUN_ID=$exactRunId")
        return report
    }

    private fun assertSuccessfulSmoke(report: HardwareE2ERunReport, expectedPipeline: String) {
        assertEquals(
            "Production smoke failed: ${diagnosticSummary(report)}",
            HardwareE2EClassification.PASS,
            report.status
        )
        assertEquals(HardwareE2EClassificationReason.PASS_SUCCESS, report.classificationReason)
        assertEquals("production_main_camera_screen", report.scenario.requestedTestScenario)
        assertEquals(expectedPipeline, report.scenario.selectedPipelineMode)
        assertEquals(HardwareE2EJobCorrelation.EXACT, report.jobCorrelation)
        assertTrue(report.terminalEvent == CameraPipelineEvent.Terminal.Kind.COMPLETE.name)
        assertTrue(report.latestJobDirectory?.isNotBlank() == true)
        assertTrue(report.finalJob?.readable == true)
        assertFalse(report.finalJob?.liveOperationRegistered == true)
        assertTrue(report.finalJob?.requiredOutputFilePresent == true)
        assertTrue(report.terminalFlags["requiredOutputCommitted"] == true)
    }

    private fun diagnosticSummary(report: HardwareE2ERunReport): String =
        report.toJson().toString(2)

    private fun waitForPipelineIdle() {
        val cleared = device.wait(Until.gone(By.res("kepler.pipeline.busy")), 15_000L)
        if (!cleared) {
            throw AssertionError(
                "Pipeline busy state did not clear: " +
                    HardwareE2EReportStore.readLatest(targetContext)
                        ?.toJson()
                        ?.toString(2)
            )
        }
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(50L)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for condition")
    }

    private fun mediaStoreRowExists(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasUsableCamera(): Boolean = runCatching {
        val manager = targetContext.getSystemService(CameraManager::class.java)
        manager.cameraIdList.isNotEmpty()
    }.getOrDefault(false)
}
