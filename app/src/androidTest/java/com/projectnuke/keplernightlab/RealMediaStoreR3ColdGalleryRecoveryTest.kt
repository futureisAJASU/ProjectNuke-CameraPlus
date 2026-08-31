package com.projectnuke.keplernightlab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.app.KeyguardManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * R3 measurement-only proof. Setup, each cold run, and cleanup are separate instrumentation
 * invocations so the host can force-stop the target process without killing this test runner.
 */
@RunWith(AndroidJUnit4::class)
class RealMediaStoreR3ColdGalleryRecoveryTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context: Context
        get() = instrumentation.targetContext

    private val device: UiDevice
        get() = UiDevice.getInstance(instrumentation)

    @Test
    fun prepareOneCohort() {
        grantCameraPermission()
        cleanupStaleR3JobDirectories()
        val root = productionYuvRoot()
        val rootExistedBefore = root.isDirectory
        assertNoPreExistingProductionJobs()
        val cohortId = UUID.randomUUID().toString()
        val jobs = mutableListOf<CohortJob>()
        try {
            assertTrue("Unable to create the production Gallery root", root.mkdirs() || root.isDirectory)
            repeat(JOB_COUNT) { index ->
                createTerminalVerifiedJob(root, cohortId, index).also { jobs += it }
            }
            assertExactTerminalContract(jobs, "pre-run")
            val manifest = CohortManifest(
                cohortId = cohortId,
                rootCreatedByR3 = !rootExistedBefore,
                jobs = jobs.map { job ->
                    ManifestJob(
                        jobDirName = job.jobDir.name,
                        uri = requireNotNull(job.uri),
                        actualFormat = job.actualFormat.name,
                        snapshot = snapshot(job.jobDir)
                    )
                }
            )
            writeManifest(manifest)
            println(
                "R3_COHORT_BASELINE total=$JOB_COUNT jpeg=$JPEG_COUNT heif=$HEIF_COUNT terminal=$JOB_COUNT " +
                    "verified=$JOB_COUNT pending=0 diagnosticNull=$JOB_COUNT journalVerified=$JOB_COUNT " +
                    "terminalMetadataPersisted=$JOB_COUNT recoveryDebt=0 sameCohort=true root=$ROOT_NAME"
            )
        } catch (failure: Throwable) {
            cleanupExactR3Artifacts(jobs.map { it.jobDir })
            throw failure
        }
    }

    @Test
    fun measureColdRun() {
        grantCameraPermission()
        assertDeviceReady()
        val runNumber = InstrumentationRegistry.getArguments().getString(RUN_ARGUMENT)?.toIntOrNull()
            ?: throw AssertionError("Missing $RUN_ARGUMENT instrumentation argument")
        assertTrue("R3 run number must be 1..3", runNumber in 1..3)
        val manifest = readManifest()
        assertEquals("R3 cohort must remain 46 jobs", JOB_COUNT, manifest.jobs.size)
        val runId = "r3-${manifest.cohortId.replace('-', '_')}-$runNumber"
        installControl(runId)
        launchProductionGallery()
        val result = awaitResult(runId)
        assertMeasurementContract(result, runId, runNumber)
        val jobs = manifest.jobs.map { manifestJob ->
            CohortJob(
                jobDir = File(productionYuvRoot(), manifestJob.jobDirName),
                uri = manifestJob.uri,
                actualFormat = OutputFormat.valueOf(manifestJob.actualFormat),
                uris = setOf(Uri.parse(manifestJob.uri))
            )
        }
        assertPostRunTerminalContract(jobs, manifest, result, runNumber)
        println("R3_COLD_RUN_JSON=$result")
    }

    @Test
    fun cleanupR3Cohort() {
        grantCameraPermission()
        val manifest = runCatching { readManifest() }.getOrNull()
        val exactDirectories = if (manifest != null) {
            manifest.jobs.map { File(productionYuvRoot(), it.jobDirName) }
        } else {
            r3JobDirectories()
        }
        cleanupExactR3Artifacts(exactDirectories)
        assertTrue("R3 job directories remain", r3JobDirectories().isEmpty())
        val evidenceDirectory = r3EvidenceDirectory()
        listOf(COHORT_FILE, CONTROL_FILE).forEach { name ->
            assertFalse("R3 evidence remains: $name", File(evidenceDirectory, name).exists())
        }
        if (evidenceDirectory.isDirectory) {
            assertTrue("R3 evidence directory is not empty", evidenceDirectory.listFiles().isNullOrEmpty())
            assertTrue("R3 evidence directory cleanup failed", evidenceDirectory.delete())
        }
    }

    private fun createTerminalVerifiedJob(root: File, cohortId: String, index: Int): CohortJob {
        val jobDir = File(root, "KPL_YUV_FUSION_R3_${cohortId}_$index")
        assertTrue("Unable to create R3 job directory", jobDir.mkdirs())
        val requested = if (index % 2 == 0) OutputFormat.JPEG else OutputFormat.HEIF
        val finalFormat = if (requested == OutputFormat.JPEG) FinalOutputFormat.JPEG else FinalOutputFormat.HEIF
        KeplerJobMetadata.write(
            jobDir,
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "PROCESSING")
                .put("processStatus", "PROCESSING")
                .put("currentPipelineStage", "PROCESSING")
                .put("recoveryState", "STABLE")
                .put("createdAt", System.currentTimeMillis())
        )
        val bitmap = deterministicBitmap(64, 64, index)
        return try {
            val export = exportNightFusionBitmapToGallery(
                context = context,
                bitmap = bitmap,
                displayNameBase = "r3-${cohortId.take(8)}-$index-${UUID.randomUUID()}",
                requestedFormat = requested,
                relativeAlbumPath = TEST_RELATIVE_PATH,
                quality = 92,
                cancellation = NoOpKeplerPipelineCancellation,
                jobDir = jobDir
            )
            assertTrue("Production export failed for R3 member $index: $export", export.success)
            assertEquals(GalleryExportCommitState.VERIFIED, export.publicCommitState)
            assertNotNull(export.uriString)
            assertTrue(export.verification is GalleryExportVerification.Verified)
            assertEquals(null, diagnosticReason(export.verification))
            updateExportMetadata(jobDir, export, verified = true, finalOutputFormat = finalFormat)
            KeplerJobMetadata.update(jobDir) {
                it.put("status", "COMPLETE").put("processStatus", "PIPELINE_COMPLETE")
            }
            KeplerJobMetadata.findOperationLease(jobDir)?.let { KeplerJobMetadata.releaseOperation(it) }
            // Match the production Gallery read path's durable storage summary before the
            // baseline is captured. Otherwise the first timed Gallery load legitimately fills
            // these fields and invalidates the unchanged-cohort assertion.
            repeat(2) {
                val settledMetadata = KeplerJobMetadata.read(jobDir)
                maybePersistStorageMetadata(
                    directory = jobDir,
                    job = settledMetadata,
                    storage = computeKeplerJobStorage(jobDir, settledMetadata, finalPreview = null)
                )
            }
            val metadata = KeplerJobMetadata.read(jobDir)
            val journal = mainJournal(jobDir)
            val uri = Uri.parse(requireNotNull(export.uriString))
            assertEquals("COMPLETE", metadata.optString("currentPipelineStage"))
            assertEquals("STABLE", metadata.optString("recoveryState"))
            assertTrue(metadata.optBoolean("galleryExportCommitted"))
            assertTrue(metadata.optBoolean("exportVerified"))
            assertEquals("", metadata.optString(ACTIVE_OPERATION_ID))
            assertEquals(MediaStoreExportState.VERIFIED, journal.state)
            assertTrue(journal.terminalMetadataPersisted)
            assertFalse("R3 cohort row must be non-pending", mediaStoreRowPending(uri))
            CohortJob(jobDir, export.uriString, export.formatUsed, setOf(uri))
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertExactTerminalContract(jobs: List<CohortJob>, phase: String) {
        assertEquals("$phase cohort size", JOB_COUNT, jobs.size)
        assertEquals("$phase JPEG count", JPEG_COUNT, jobs.count { it.actualFormat == OutputFormat.JPEG })
        assertEquals("$phase HEIF count", HEIF_COUNT, jobs.count { it.actualFormat == OutputFormat.HEIF })
        jobs.forEach { item ->
            val metadata = KeplerJobMetadata.read(item.jobDir)
            val journal = mainJournal(item.jobDir)
            val uri = Uri.parse(requireNotNull(journal.uri))
            val inspection = ContextMediaStoreExportRecoveryAccess(context).inspect(uri, journal)
            assertEquals("$phase terminal metadata", "COMPLETE", metadata.optString("currentPipelineStage"))
            assertEquals("$phase recovery state", "STABLE", metadata.optString("recoveryState"))
            assertTrue("$phase committed", metadata.optBoolean("galleryExportCommitted"))
            assertTrue("$phase verified", metadata.optBoolean("exportVerified"))
            assertEquals("$phase active operation", "", metadata.optString(ACTIVE_OPERATION_ID))
            assertEquals("$phase journal", MediaStoreExportState.VERIFIED, journal.state)
            assertTrue("$phase terminal metadata persisted", journal.terminalMetadataPersisted)
            assertTrue("$phase row exists", inspection.exists)
            assertFalse("$phase row pending", inspection.pending)
            assertTrue("$phase row verified", inspection.verified)
            assertEquals("$phase diagnostic", null, inspection.verificationDiagnosticReason)
        }
    }

    private fun assertMeasurementContract(result: JSONObject, runId: String, runNumber: Int) {
        assertEquals("run id", runId, result.getString("runId"))
        assertEquals("recovery jobs", JOB_COUNT, result.getInt("recoveryJobCount"))
        assertEquals("recovered jobs", JOB_COUNT, result.getInt("recoveredJobCount"))
        assertEquals("recovery failures", 0, result.getInt("recoveryFailureCount"))
        assertEquals("Gallery jobs", JOB_COUNT, result.getInt("galleryJobCount"))
        assertTrue("run $runNumber total timing", result.getDouble("totalProcessColdGalleryReadyMs") > 0.0)
        assertTrue("run $runNumber recovery timing", result.getDouble("recoveryMs") > 0.0)
        val verification = result.getJSONObject("verification")
        assertEquals("run $runNumber inspections", JOB_COUNT, verification.getInt("inspectionsAttempted"))
        assertEquals("run $runNumber verified", JOB_COUNT, verification.getInt("verifiedTrue"))
        assertEquals("run $runNumber unverified", 0, verification.getInt("verifiedFalse"))
        assertEquals("run $runNumber pending", 0, verification.getInt("pendingTrue"))
        assertEquals("run $runNumber non-pending", JOB_COUNT, verification.getInt("pendingFalse"))
        assertEquals("run $runNumber diagnostics", 0, verification.getJSONObject("diagnosticReasons").length())
        assertEquals("run $runNumber JPEG inspections", JPEG_COUNT, verification.getJSONObject("jpegInspectionMs").getInt("count"))
        assertEquals("run $runNumber HEIF inspections", HEIF_COUNT, verification.getJSONObject("heifInspectionMs").getInt("count"))
    }

    private fun assertPostRunTerminalContract(
        jobs: List<CohortJob>,
        manifest: CohortManifest,
        result: JSONObject,
        runNumber: Int
    ) {
        assertEquals("post-run $runNumber job count", JOB_COUNT, jobs.count { it.jobDir.isDirectory })
        jobs.forEach { item ->
            val metadata = KeplerJobMetadata.read(item.jobDir)
            val journal = mainJournal(item.jobDir)
            val uri = Uri.parse(requireNotNull(journal.uri))
            assertEquals("post-run $runNumber stage", "COMPLETE", metadata.optString("currentPipelineStage"))
            assertEquals("post-run $runNumber state", "STABLE", metadata.optString("recoveryState"))
            assertTrue("post-run $runNumber committed", metadata.optBoolean("galleryExportCommitted"))
            assertTrue("post-run $runNumber verified", metadata.optBoolean("exportVerified"))
            assertEquals("post-run $runNumber active", "", metadata.optString(ACTIVE_OPERATION_ID))
            assertEquals("post-run $runNumber journal", MediaStoreExportState.VERIFIED, journal.state)
            assertTrue("post-run $runNumber terminal persisted", journal.terminalMetadataPersisted)
            assertFalse("post-run $runNumber pending", mediaStoreRowPending(uri))
            assertTrue("post-run $runNumber row exists", mediaStoreRowExists(uri))
        }
        val current = jobs.associate { it.jobDir.name to snapshot(it.jobDir) }
        val expected = manifest.jobs.associate { it.jobDirName to it.snapshot }
        assertEquals("post-run $runNumber metadata hashes", expected.mapValues { it.value.metadataHash }, current.mapValues { it.value.metadataHash })
        assertEquals("post-run $runNumber journal hashes", expected.mapValues { it.value.journalHash }, current.mapValues { it.value.journalHash })
        assertEquals("post-run $runNumber result cohort", JOB_COUNT, result.getInt("recoveryJobCount"))
    }

    private fun assertNoPreExistingProductionJobs() {
        val existing = keplerGalleryRoots(context).flatMap { root ->
            root.listFiles().orEmpty().filter { it.isDirectory && matchesJobPrefix(root, it.name) }
        }
        assertTrue("R3 requires a clean production job cohort root: $existing", existing.isEmpty())
    }

    private fun launchProductionGallery() {
        val output = shell("am start -W -n ${context.packageName}/.MainActivity")
        assertTrue("Production MainActivity did not start: $output", output.contains("Status: ok"))
        val open = device.wait(Until.findObject(By.res("kepler.gallery.open")), UI_TIMEOUT_MS)
        assertNotNull("Production Gallery entry point was not ready", open)
        requireNotNull(open).click()
    }

    private fun assertDeviceReady() {
        device.wakeUp()
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assertTrue(
            "R3 requires an interactive unlocked device",
            power.isInteractive && !keyguard.isKeyguardLocked
        )
    }

    private fun awaitResult(runId: String): JSONObject {
        val result = R3GalleryColdMeasurement.resultFile(context, runId)
        val deadline = SystemClock.elapsedRealtime() + RESULT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (result.isFile) {
                runCatching { JSONObject(result.readText(StandardCharsets.UTF_8)) }
                    .getOrNull()?.let { return it }
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Timed out waiting for R3 result $runId")
    }

    private fun installControl(runId: String) {
        val file = R3GalleryColdMeasurement.controlFile(context)
        val parent = requireNotNull(file.parentFile)
        assertTrue(parent.mkdirs() || parent.isDirectory)
        val temporary = File(file.parentFile, ".control-$runId.tmp")
        temporary.writeText(JSONObject().put("runId", runId).toString(), StandardCharsets.UTF_8)
        assertTrue("Could not publish R3 control", temporary.renameTo(file))
    }

    private fun cleanupStaleR3JobDirectories() {
        cleanupExactR3Artifacts(r3JobDirectories())
    }

    private fun cleanupExactR3Artifacts(jobDirectories: List<File>) {
        val uris = jobDirectories.flatMap { jobDir ->
            MediaStoreExportJournal.list(jobDir).mapNotNull { it.uri }.map(Uri::parse)
        }.toSet()
        uris.forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
            assertFalse("R3 exact MediaStore row must be deleted: $uri", mediaStoreRowExists(uri))
        }
        jobDirectories.forEach { jobDir ->
            assertTrue("R3 exact job directory cleanup failed: $jobDir", !jobDir.exists() || jobDir.deleteRecursively())
        }
        val evidenceDirectory = r3EvidenceDirectory()
        if (evidenceDirectory.isDirectory) {
            evidenceDirectory.listFiles().orEmpty()
                .filter { it.name == COHORT_FILE || it.name == CONTROL_FILE || it.name.startsWith("r3-") || it.name.startsWith(".control-") }
                .forEach { file -> assertTrue("R3 evidence cleanup failed: $file", !file.exists() || file.delete()) }
        }
    }

    private fun r3JobDirectories(): List<File> = keplerGalleryRoots(context).flatMap { root ->
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(R3_JOB_PREFIX) }
    }

    private fun productionYuvRoot(): File = File(
        requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)),
        ROOT_NAME
    )

    private fun r3EvidenceDirectory(): File = File(context.filesDir, R3_EVIDENCE_DIRECTORY)

    private fun writeManifest(manifest: CohortManifest) {
        val directory = r3EvidenceDirectory()
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val json = JSONObject()
            .put("cohortId", manifest.cohortId)
            .put("rootCreatedByR3", manifest.rootCreatedByR3)
            .put("jobs", JSONArray().apply {
                manifest.jobs.forEach { job ->
                    put(JSONObject()
                        .put("jobDirName", job.jobDirName)
                        .put("uri", job.uri)
                        .put("actualFormat", job.actualFormat)
                        .put("metadataHash", job.snapshot.metadataHash)
                        .put("journalHash", job.snapshot.journalHash))
                }
            })
        val file = File(directory, COHORT_FILE)
        val temporary = File(directory, ".cohort.tmp")
        temporary.writeText(json.toString(), StandardCharsets.UTF_8)
        assertTrue(temporary.renameTo(file))
    }

    private fun readManifest(): CohortManifest {
        val json = JSONObject(File(r3EvidenceDirectory(), COHORT_FILE).readText(StandardCharsets.UTF_8))
        val jobsJson = json.getJSONArray("jobs")
        val jobs = (0 until jobsJson.length()).map { index ->
            val job = jobsJson.getJSONObject(index)
            ManifestJob(
                jobDirName = job.getString("jobDirName"),
                uri = job.getString("uri"),
                actualFormat = job.getString("actualFormat"),
                snapshot = DurableSnapshot(job.getString("metadataHash"), job.getString("journalHash"))
            )
        }
        return CohortManifest(json.getString("cohortId"), json.getBoolean("rootCreatedByR3"), jobs)
    }

    private fun mainJournal(jobDir: File): MediaStoreExportJournal =
        MediaStoreExportJournal.list(jobDir).single { it.role == MediaStoreExportRole.MAIN_IMAGE }

    private fun snapshot(jobDir: File): DurableSnapshot {
        val metadata = File(jobDir, JOB_JSON_FILE_NAME)
        val journal = mainJournal(jobDir)
        val journalFile = MediaStoreExportJournal.fileFor(jobDir, journal.exportAttemptId)
        return DurableSnapshot(sha256(metadata), sha256(journalFile))
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun mediaStoreRowExists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    private fun mediaStoreRowPending(uri: Uri): Boolean = requireNotNull(
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0) != 0
    }

    private fun grantCameraPermission() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun diagnosticReason(result: GalleryExportVerification?): GalleryExportVerificationReason? = when (result) {
        is GalleryExportVerification.RetryableFailure -> result.diagnosticReason
        is GalleryExportVerification.PermanentFailure -> result.diagnosticReason
        is GalleryExportVerification.Verified, null -> null
    }

    private fun deterministicBitmap(width: Int, height: Int, seed: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until height) for (x in 0 until width) {
                bitmap.setPixel(
                    x,
                    y,
                    android.graphics.Color.argb(
                        255,
                        (x * 4 + seed) % 256,
                        (y * 4 + seed * 3) % 256,
                        ((x + y) * 2 + seed * 5) % 256
                    )
                )
            }
        }

    private data class CohortJob(
        val jobDir: File,
        val uri: String?,
        val actualFormat: OutputFormat,
        val uris: Set<Uri>
    )

    private data class DurableSnapshot(val metadataHash: String, val journalHash: String)

    private data class ManifestJob(
        val jobDirName: String,
        val uri: String,
        val actualFormat: String,
        val snapshot: DurableSnapshot
    )

    private data class CohortManifest(
        val cohortId: String,
        val rootCreatedByR3: Boolean,
        val jobs: List<ManifestJob>
    )

    private companion object {
        const val ROOT_NAME = "KeplerYuvFusion"
        const val TEST_RELATIVE_PATH = "Pictures/KeplerR3TrueColdGallery"
        const val R3_JOB_PREFIX = "KPL_YUV_FUSION_R3_"
        const val R3_EVIDENCE_DIRECTORY = "r3-gallery-cold"
        const val COHORT_FILE = "cohort.json"
        const val CONTROL_FILE = "control.json"
        const val RUN_ARGUMENT = "r3.run"
        const val JOB_COUNT = 46
        const val JPEG_COUNT = 23
        const val HEIF_COUNT = 23
        const val UI_TIMEOUT_MS = 30_000L
        const val RESULT_TIMEOUT_MS = 120_000L
    }
}
