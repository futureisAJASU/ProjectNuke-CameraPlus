package com.projectnuke.keplernightlab

import android.Manifest
import android.content.Context
import android.content.Intent
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
    fun prepareR4Cohort() {
        grantCameraPermission()
        // Isolate R4 exactly: clean any stale R4 dirs and any stale R4 evidence before preparing.
        // Do NOT delete unrelated R3 cohort unless it collides on the same root prefix.
        runCatching {
            val staleR4 = keplerGalleryRoots(context).flatMap { rootDir ->
                rootDir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(R4_JOB_PREFIX) }
            }
            if (staleR4.isNotEmpty()) {
                cleanupExactR4Artifacts(staleR4)
            }
            val r4Dir = r4EvidenceDirectory()
            r4Dir.listFiles()?.forEach { f ->
                // Keep only r4 cohort/control/result files isolated; do not touch r3-gallery-cold.
                runCatching { if (f.name == COHORT_FILE || f.name == CONTROL_FILE || f.name.startsWith("r4-") || f.name.startsWith(".control-") || f.name.startsWith(".cohort")) f.delete() }
            }
        }
        val cohortId = UUID.randomUUID().toString()
        val root = productionYuvRoot()
        val rootExistedBefore = root.isDirectory
        assertTrue("Unable to create R4 root", root.mkdirs() || root.isDirectory)
        assertNoPreExistingR4Jobs()
        val jobs = mutableListOf<CohortJob>()
        try {
            repeat(R4_JOB_COUNT) { index ->
                createR4TerminalVerifiedJob(root, cohortId, index).also { jobs += it }
            }
            assertExactTerminalContract(jobs, "R4 pre-run", R4_JOB_COUNT, R4_JPEG_COUNT, R4_HEIF_COUNT)
            // Full preconditions per spec section 5: pending 0, recovery debt 0, diagnostic null etc
            // asserted inside assertExactTerminalContract (terminal STABLE, VERIFIED, etc)
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
            writeR4Manifest(manifest)
            println(
                "R4_COHORT_BASELINE total=$R4_JOB_COUNT jpeg=$R4_JPEG_COUNT heif=$R4_HEIF_COUNT terminal=$R4_JOB_COUNT " +
                    "verified=$R4_JOB_COUNT pending=0 diagnosticNull=$R4_JOB_COUNT journalVerified=$R4_JOB_COUNT " +
                    "terminalMetadataPersisted=$R4_JOB_COUNT recoveryDebt=0 sameCohort=true root=$ROOT_NAME"
            )
        } catch (failure: Throwable) {
            // On failure remove exactly the R4 cohort we just created so next prepare is clean.
            cleanupExactR4Artifacts(jobs.map { it.jobDir })
            throw failure
        }
    }

    @Test
    fun measureR4ColdRun() {
        grantCameraPermission()
        assertDeviceReady()
        val runNumber = InstrumentationRegistry.getArguments().getString(R4_RUN_ARGUMENT)?.toIntOrNull()
            ?: throw AssertionError("Missing $R4_RUN_ARGUMENT instrumentation argument")
        assertTrue("R4 run number must be 1..3", runNumber in 1..3)
        val manifest = readR4Manifest()
        assertEquals("R4 cohort must remain 46 jobs", R4_JOB_COUNT, manifest.jobs.size)
        val runId = "r4-${manifest.cohortId.replace('-', '_')}-$runNumber"
        installR4Control(runId)
        launchProductionGallery()
        val result = awaitResult(runId)
        try {
        // Section 7: fail if recovery did not actually execute — fresh evidence required
        val processStartedAtNanos = result.optLong("processStartedAtNanos", -1L)
        val recoveryStartedAtNanos = result.optLong("recoveryStartedAtNanos", -1L)
        val recoveryFinishedAtNanos = result.optLong("recoveryFinishedAtNanos", -1L)
        assertTrue("run $runNumber recoveryStartedAtNanos > processStartedAtNanos: $recoveryStartedAtNanos > $processStartedAtNanos", recoveryStartedAtNanos > processStartedAtNanos)
        assertTrue("run $runNumber recoveryFinishedAtNanos > recoveryStartedAtNanos: $recoveryFinishedAtNanos > $recoveryStartedAtNanos", recoveryFinishedAtNanos > recoveryStartedAtNanos)
        assertEquals("run $runNumber recovery jobs", R4_JOB_COUNT, result.getInt("recoveryJobCount"))
        assertEquals("run $runNumber recovered jobs", R4_JOB_COUNT, result.getInt("recoveredJobCount"))
        assertEquals("run $runNumber recovery failures", 0, result.getInt("recoveryFailureCount"))
        val verification = result.getJSONObject("verification")
        assertEquals("run $runNumber inspections", R4_JOB_COUNT, verification.getInt("inspectionsAttempted"))
        assertEquals("run $runNumber verified", R4_JOB_COUNT, verification.getInt("verifiedTrue"))
        assertEquals("run $runNumber unverified", 0, verification.getInt("verifiedFalse"))
        assertEquals("run $runNumber pending", 0, verification.getInt("pendingTrue"))
        assertEquals("run $runNumber diagnostics", 0, verification.getJSONObject("diagnosticReasons").length())
        assertEquals("run $runNumber JPEG inspections", R4_JPEG_COUNT, verification.getJSONObject("jpegInspectionMs").getInt("count"))
        assertEquals("run $runNumber HEIF inspections", R4_HEIF_COUNT, verification.getJSONObject("heifInspectionMs").getInt("count"))
        // Section 8: zero-write contract
        val metadata = result.getJSONObject("metadata")
        val bySource = metadata.getJSONObject("bySource")
        assertEquals("run $runNumber reconstructionWriteAttempts", 0, metadata.getInt("reconstructionWriteAttempts"))
        assertEquals("run $runNumber RECONSTRUCT_MAIN_EXPORT writes", 0, bySource.getJSONObject("RECONSTRUCT_MAIN_EXPORT").getInt("writeAttempts"))
        assertEquals("run $runNumber TERMINAL_STABLE_SETTLEMENT writes", 0, bySource.getJSONObject("TERMINAL_STABLE_SETTLEMENT").getInt("writeAttempts"))
        assertEquals("run $runNumber contentChangingWrites", 0, metadata.getInt("contentChangingWrites"))
        assertEquals("run $runNumber sameContentRewrites", 0, metadata.getInt("sameContentRewrites"))
        assertEquals("run $runNumber journalWrites", 0, metadata.getInt("journalWrites"))
        assertEquals("run $runNumber terminalMetadataWrites", 0, metadata.getInt("terminalMetadataWrites"))
        // Also verify bySource same-content counters are zero for the two suppressed sources
        assertEquals("run $runNumber RECONSTRUCT contentChanging", 0, bySource.getJSONObject("RECONSTRUCT_MAIN_EXPORT").getInt("contentChangingWrites"))
        assertEquals("run $runNumber RECONSTRUCT sameContent", 0, bySource.getJSONObject("RECONSTRUCT_MAIN_EXPORT").getInt("sameContentWrites"))
        assertEquals("run $runNumber TERMINAL_STABLE contentChanging", 0, bySource.getJSONObject("TERMINAL_STABLE_SETTLEMENT").getInt("contentChangingWrites"))
        assertEquals("run $runNumber TERMINAL_STABLE sameContent", 0, bySource.getJSONObject("TERMINAL_STABLE_SETTLEMENT").getInt("sameContentWrites"))
        println("R4_COLD_RUN_JSON run=$runNumber $result")
        // Section 9: durable cohort invariants after every run
        val jobs = manifest.jobs.map { manifestJob ->
            CohortJob(
                jobDir = File(productionYuvRoot(), manifestJob.jobDirName),
                uri = manifestJob.uri,
                actualFormat = OutputFormat.valueOf(manifestJob.actualFormat),
                uris = setOf(Uri.parse(manifestJob.uri))
            )
        }
        assertPostRunTerminalContract(jobs, manifest, result, runNumber, R4_JOB_COUNT, R4_JPEG_COUNT, R4_HEIF_COUNT)
        // Timing report (section 10) — only after contract satisfied
        val recoveryMs = result.getDouble("recoveryMs")
        val inspectionAgg = verification.getDouble("aggregateMs")
        val jpegAgg = verification.getJSONObject("jpegInspectionMs").getDouble("aggregateMs")
        val heifAgg = verification.getJSONObject("heifInspectionMs").getDouble("aggregateMs")
        val metadataPersistenceMs = metadata.getDouble("metadataPersistenceMs")
        val postRecoveryToGalleryMs = result.getDouble("postRecoveryToGalleryReadyMs")
        val totalMs = result.getDouble("totalProcessColdGalleryReadyMs")
        println(
            "R4_TIMING run=$runNumber recoveryMs=$recoveryMs verificationAggMs=$inspectionAgg jpegAggMs=$jpegAgg heifAggMs=$heifAgg " +
                "metadataPersistenceMs=$metadataPersistenceMs postRecoveryToGalleryReadyMs=$postRecoveryToGalleryMs totalMs=$totalMs"
        )
        } finally {
            clearR4ControlIfOwned(manifest)
            // Completed run must leave no R4-owned control — fail-closed for unrelated R3
            assertFalse(
                "R4-owned control.json remains after completed run ${R3GalleryColdMeasurement.controlFile(context)}",
                isR4OwnedControl(manifest)
            )
        }
    }

    @Test
    fun cleanupR4Cohort() {
        grantCameraPermission()
        val manifest = runCatching { readR4Manifest() }.getOrNull()
        val exactDirectories = if (manifest != null) {
            manifest.jobs.map { File(productionYuvRoot(), it.jobDirName) }
        } else {
            r4JobDirectories()
        }
        cleanupExactR4Artifacts(exactDirectories)
        assertTrue("R4 job directories remain: ${r4JobDirectories()}", r4JobDirectories().isEmpty())
        // Final cleanup must verify the REAL authoritative control location: filesDir/r3-gallery-cold/control.json
        // and the real r3-gallery-cold directory for R4 artifacts — not r4-gallery-cold/CONTROL_FILE.
        clearR4ControlIfOwned(manifest)
        val coldDir = File(context.filesDir, R3_EVIDENCE_DIRECTORY)
        if (coldDir.isDirectory) {
            val r4Results = coldDir.listFiles().orEmpty().filter { it.name.startsWith("r4-") }
            assertTrue("R4 result files remain in r3-gallery-cold: $r4Results", r4Results.isEmpty())
            val r4Temps = coldDir.listFiles().orEmpty().filter { it.name.startsWith(".control-r4-") }
            assertTrue("R4 temp control files remain in r3-gallery-cold: $r4Temps", r4Temps.isEmpty())
            assertFalse(
                "R4-owned control.json remains in r3-gallery-cold after cleanup",
                isR4OwnedControl(manifest)
            )
        } else {
            assertFalse("R4-owned control.json remains (no coldDir) ", isR4OwnedControl(manifest))
        }
        // Verify R4 manifest/evidence files are gone from r4-gallery-cold
        val evidenceDirectory = r4EvidenceDirectory()
        assertFalse("R4 cohort manifest remains: $COHORT_FILE", File(evidenceDirectory, COHORT_FILE).exists())
        val remainingResults = evidenceDirectory.listFiles().orEmpty().filter { it.name.startsWith("r4-") }
        assertTrue("R4 result files remain in r4-gallery-cold: $remainingResults", remainingResults.isEmpty())
        if (evidenceDirectory.isDirectory) {
            val leftover = evidenceDirectory.listFiles().orEmpty().filter { it.name.startsWith(".control-") || it.name.startsWith(".cohort") || it.name == CONTROL_FILE }
            leftover.forEach { runCatching { it.delete() } }
            if (evidenceDirectory.listFiles().isNullOrEmpty()) {
                assertTrue("R4 evidence directory cleanup failed", evidenceDirectory.delete())
            }
        }
        // Verify exact rows are absent — if manifest was present, we already deleted them in cleanupExactR4Artifacts.
        // If no manifest, we have no URI list; best-effort check is that no R4 rows remain via directory scan (already empty).
    }

    @Test
    fun r4ControlCleanupIsOwnershipChecked() {
        grantCameraPermission()
        val control = R3GalleryColdMeasurement.controlFile(context)
        val parent = requireNotNull(control.parentFile)
        assertTrue(parent.mkdirs() || parent.isDirectory)
        // Ensure clean start
        runCatching { control.delete() }
        parent.listFiles().orEmpty().filter { it.name.startsWith(".control-r4-") || it.name.startsWith(".control-r3-") }.forEach { runCatching { it.delete() } }

        // A. R4-owned control is deleted by ownership helper
        control.writeText(JSONObject().put("runId", "r4-example-1").toString(), StandardCharsets.UTF_8)
        assertTrue("setup: R4 control exists", control.isFile)
        clearR4ControlIfOwned()
        assertFalse("R4-owned control must be deleted by R4 cleanup", control.exists())

        // B. unrelated R3 control is NOT deleted by R4-specific helper
        control.writeText(JSONObject().put("runId", "r3-example-1").toString(), StandardCharsets.UTF_8)
        assertTrue("setup: R3 control exists", control.isFile)
        clearR4ControlIfOwned()
        assertTrue("unrelated R3 control must NOT be deleted by R4 helper", control.isFile)
        assertEquals("r3-example-1", JSONObject(control.readText(StandardCharsets.UTF_8)).optString("runId"))
        // When manifest is provided, R4 helper must also not delete R3; and when helper is called with null manifest for R4 mismatch
        clearR4ControlIfOwned(null)
        assertTrue("R3 control still present after second R4 clear", control.isFile)

        // Also verify that R4 helper with mismatched cohort prefix does not delete a different R4 run
        control.writeText(JSONObject().put("runId", "r4-other-cohort-1").toString(), StandardCharsets.UTF_8)
        val fakeManifest = CohortManifest(cohortId = "11111111-2222-3333-4444-555555555555", rootCreatedByR3 = false, jobs = emptyList())
        // fakeManifest prefix is r4-11111111_2222_3333_4444_555555555555- ; our control is r4-other-cohort-1 -> should NOT be deleted
        clearR4ControlIfOwned(fakeManifest)
        assertTrue("R4 control with mismatched cohort must NOT be deleted when manifest prefix required", control.isFile)
        // Without manifest, any r4- is owned -> should be deleted
        clearR4ControlIfOwned(null)
        assertFalse("R4 control with any r4- prefix must be deleted when no manifest filter", control.exists())

        // C. malformed/unknown control is handled fail-closed without deleting unrelated evidence
        control.writeText("not-json-at-all", StandardCharsets.UTF_8)
        assertTrue("setup: malformed control exists", control.isFile)
        clearR4ControlIfOwned()
        assertTrue("malformed control must be left intact (fail-closed)", control.isFile)
        // empty json without runId
        control.writeText(JSONObject().toString(), StandardCharsets.UTF_8)
        clearR4ControlIfOwned()
        assertTrue("control without runId must be left intact", control.isFile)
        // unknown prefix
        control.writeText(JSONObject().put("runId", "unknown-prefix-1").toString(), StandardCharsets.UTF_8)
        clearR4ControlIfOwned()
        assertTrue("unknown prefix control must be left intact", control.isFile)

        // Final hygiene: leave no control
        runCatching { control.delete() }
        assertFalse("cleanup regression must leave no control", control.exists())
        // Also ensure no temp controls remain
        parent.listFiles().orEmpty().filter { it.name.startsWith(".control-") }.forEach { runCatching { it.delete() } }
    }

    private fun createR4TerminalVerifiedJob(root: File, cohortId: String, index: Int): CohortJob {
        val jobDir = File(root, "KPL_YUV_FUSION_R4_${cohortId}_$index")
        assertTrue(jobDir.mkdirs())
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
                displayNameBase = "r4-${cohortId.take(8)}-$index-${UUID.randomUUID()}",
                requestedFormat = requested,
                relativeAlbumPath = TEST_RELATIVE_PATH,
                quality = 92,
                cancellation = NoOpKeplerPipelineCancellation,
                jobDir = jobDir
            )
            assertTrue("Production export failed for R4 member $index: $export", export.success)
            assertEquals(GalleryExportCommitState.VERIFIED, export.publicCommitState)
            assertNotNull(export.uriString)
            assertTrue(export.verification is GalleryExportVerification.Verified)
            assertEquals(null, diagnosticReason(export.verification))
            updateExportMetadata(jobDir, export, verified = true, finalOutputFormat = finalFormat)
            KeplerJobMetadata.update(jobDir) {
                it.put("status", "COMPLETE").put("processStatus", "PIPELINE_COMPLETE")
            }
            KeplerJobMetadata.findOperationLease(jobDir)?.let { KeplerJobMetadata.releaseOperation(it) }
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
            assertFalse("R4 cohort row must be non-pending", mediaStoreRowPending(uri))
            CohortJob(jobDir, export.uriString, export.formatUsed, setOf(uri))
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeR4Manifest(manifest: CohortManifest) {
        val directory = r4EvidenceDirectory()
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

    private fun readR4Manifest(): CohortManifest {
        val json = JSONObject(File(r4EvidenceDirectory(), COHORT_FILE).readText(StandardCharsets.UTF_8))
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

    private fun r4EvidenceDirectory(): File = File(context.filesDir, "r4-gallery-cold")

    private fun r4JobDirectories(): List<File> = keplerGalleryRoots(context).flatMap { root ->
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(R4_JOB_PREFIX) }
    }

    private fun cleanupExactR4Artifacts(jobDirectories: List<File>) {
        val uris = jobDirectories.flatMap { jobDir ->
            MediaStoreExportJournal.list(jobDir).mapNotNull { it.uri }.map(Uri::parse)
        }.toSet()
        // Also include manifest URIs if directories already deleted but manifest still references them
        val manifestUris = runCatching { readR4Manifest() }.getOrNull()?.jobs?.map { Uri.parse(it.uri) }?.toSet().orEmpty()
        (uris + manifestUris).forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
            assertFalse("R4 exact MediaStore row must be deleted: $uri", mediaStoreRowExists(uri))
        }
        jobDirectories.forEach { jobDir ->
            assertTrue("R4 exact job directory cleanup failed: $jobDir", !jobDir.exists() || jobDir.deleteRecursively())
        }
        val evidenceDirectory = r4EvidenceDirectory()
        if (evidenceDirectory.isDirectory) {
            evidenceDirectory.listFiles().orEmpty()
                .filter { it.name == COHORT_FILE || it.name == CONTROL_FILE || it.name.startsWith("r4-") || it.name.startsWith(".control-") || it.name.startsWith(".cohort") }
                .forEach { file -> assertTrue("R4 evidence cleanup failed: $file", !file.exists() || file.delete()) }
        }
        // Also clean any stale control/result in r3-gallery-cold that belong to r4 — exact ownership for control.json
        val coldDir = File(context.filesDir, R3_EVIDENCE_DIRECTORY)
        if (coldDir.isDirectory) {
            val manifestForControl = runCatching { readR4Manifest() }.getOrNull()
            clearR4ControlIfOwned(manifestForControl)
            coldDir.listFiles().orEmpty()
                .filter { it.name.startsWith("r4-") || it.name.startsWith(".control-r4-") }
                .forEach { runCatching { it.delete() } }
        }
    }

    private fun installR4Control(runId: String) {
        val file = R3GalleryColdMeasurement.controlFile(context)
        val parent = requireNotNull(file.parentFile)
        assertTrue(parent.mkdirs() || parent.isDirectory)
        val temporary = File(file.parentFile, ".control-$runId.tmp")
        temporary.writeText(JSONObject().put("runId", runId).toString(), StandardCharsets.UTF_8)
        assertTrue("Could not publish R4 control", temporary.renameTo(file))
    }

    private fun clearR4ControlIfOwned(manifest: CohortManifest? = null) {
        val control = R3GalleryColdMeasurement.controlFile(context)
        if (!control.isFile) return
        val runId = runCatching {
            JSONObject(control.readText(StandardCharsets.UTF_8)).optString("runId")
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        if (!runId.startsWith("r4-")) return
        if (manifest != null) {
            val cohortPrefix = "r4-${manifest.cohortId.replace('-', '_')}-"
            if (!runId.startsWith(cohortPrefix)) return
        }
        runCatching { control.delete() }
        val tmp = File(requireNotNull(control.parentFile), ".control-$runId.tmp")
        runCatching { if (tmp.isFile) tmp.delete() }
    }

    private fun isR4OwnedControl(manifest: CohortManifest? = null): Boolean {
        val control = R3GalleryColdMeasurement.controlFile(context)
        if (!control.isFile) return false
        val runId = runCatching {
            JSONObject(control.readText(StandardCharsets.UTF_8)).optString("runId")
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        if (!runId.startsWith("r4-")) return false
        if (manifest != null) {
            val cohortPrefix = "r4-${manifest.cohortId.replace('-', '_')}-"
            if (!runId.startsWith(cohortPrefix)) return false
        }
        return true
    }

    private fun assertNoPreExistingR4Jobs() {
        val existing = r4JobDirectories()
        assertTrue("R4 requires a clean R4 job cohort root: $existing", existing.isEmpty())
    }

    private fun assertNoPreExistingProductionJobs() {
        val existing = keplerGalleryRoots(context).flatMap { root ->
            root.listFiles().orEmpty().filter { it.isDirectory && matchesJobPrefix(root, it.name) }
        }
        assertTrue("R4 requires a clean production job cohort root: $existing", existing.isEmpty())
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

    private fun assertExactTerminalContract(jobs: List<CohortJob>, phase: String, expectedTotal: Int = JOB_COUNT, expectedJpeg: Int = JPEG_COUNT, expectedHeif: Int = HEIF_COUNT) {
        assertEquals("$phase cohort size", expectedTotal, jobs.size)
        assertEquals("$phase JPEG count", expectedJpeg, jobs.count { it.actualFormat == OutputFormat.JPEG })
        assertEquals("$phase HEIF count", expectedHeif, jobs.count { it.actualFormat == OutputFormat.HEIF })
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
        runNumber: Int,
        expectedTotal: Int = JOB_COUNT,
        expectedJpeg: Int = JPEG_COUNT,
        expectedHeif: Int = HEIF_COUNT
    ) {
        assertEquals("post-run $runNumber job count", expectedTotal, jobs.count { it.jobDir.isDirectory })
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
        assertEquals("post-run $runNumber result cohort", expectedTotal, result.getInt("recoveryJobCount"))
    }

    private fun launchProductionGallery() {
        // Cold-start via shell (faithful to R3 protocol). For Secure Folder user 150,
        // shell `am` without user flag may target user 0; check Status then fallback to
        // Context.startActivity which respects the instrumented user.
        val shellOutput = runCatching { shell("am start -W -n ${context.packageName}/.MainActivity") }.getOrNull() ?: ""
        val shellOk = shellOutput.contains("Status: ok")
        SystemClock.sleep(800)
        dismissPermissionDialogIfPresent()
        var root = device.wait(Until.findObject(By.res("kepler.camera.root")), 6000)
        if (root == null && !shellOk) {
            // Shell likely targeted wrong user (Secure Folder isolation); fallback once.
            runCatching {
                val explicit = Intent().apply {
                    setClassName(context.packageName, "${context.packageName}.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(explicit)
            }
            SystemClock.sleep(1000)
            dismissPermissionDialogIfPresent()
            root = device.wait(Until.findObject(By.res("kepler.camera.root")), UI_TIMEOUT_MS)
        } else if (root == null) {
            // Shell claimed OK but root still not found - wait longer before fallback
            root = device.wait(Until.findObject(By.res("kepler.camera.root")), UI_TIMEOUT_MS - 6000)
            if (root == null) {
                runCatching {
                    val explicit = Intent().apply {
                        setClassName(context.packageName, "${context.packageName}.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    context.startActivity(explicit)
                }
                SystemClock.sleep(1000)
                root = device.wait(Until.findObject(By.res("kepler.camera.root")), UI_TIMEOUT_MS)
            }
        }
        if (root == null) {
            val pkg = runCatching { device.currentPackageName }.getOrNull() ?: "unknown"
            val dump = runCatching { shell("uiautomator dump /dev/tty") }.getOrNull()?.take(4000) ?: "no dump"
            throw AssertionError("kepler.camera.root not ready after am start. pkg=$pkg shellOutput=$shellOutput dump=$dump")
        }
        // PermissionScreen fallback: if the app shows "카메라 권한 허용" ask again via UI.
        val permissionFallback = device.wait(Until.findObject(By.text("카메라 권한 허용")), 2000)
        if (permissionFallback != null) {
            permissionFallback.click()
            dismissPermissionDialogIfPresent()
            SystemClock.sleep(1000)
            assertNotNull(
                "kepler.camera.root after permission grant",
                device.wait(Until.findObject(By.res("kepler.camera.root")), UI_TIMEOUT_MS)
            )
        }
        SystemClock.sleep(500)
        val open = device.wait(Until.findObject(By.res("kepler.gallery.open")), UI_TIMEOUT_MS)
            ?: device.wait(Until.findObject(By.desc("최근 결과")), 2000)
            ?: device.wait(Until.findObject(By.text("결과")), 2000)
            ?: device.wait(Until.findObject(By.text("Open Gallery / Jobs")), 2000)
        if (open == null) {
            val pkg = runCatching { device.currentPackageName }.getOrNull() ?: "unknown"
            val dump = runCatching { shell("uiautomator dump /dev/tty") }.getOrNull()?.take(4000) ?: "no dump"
            throw AssertionError("Production Gallery entry point was not ready. pkg=$pkg dump=$dump")
        }
        open.click()
        // Wait for gallery to be visible - ensures LaunchedEffect that triggers galleryReady runs
        device.wait(Until.findObject(By.res("kepler.gallery.storageSummary")), 10_000)
            ?: device.wait(Until.findObject(By.res("kepler.gallery.selectAll")), 5000)
            ?: device.wait(Until.findObject(By.text("뒤로")), 5000)
    }

    private fun dismissPermissionDialogIfPresent() {
        runCatching {
            device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button")), 2000)?.click()
                ?: device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_one_time_button")), 2000)?.click()
        }
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
        val control = R3GalleryColdMeasurement.controlFile(context)
        val deadline = SystemClock.elapsedRealtime() + RESULT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (result.isFile) {
                runCatching { JSONObject(result.readText(StandardCharsets.UTF_8)) }
                    .getOrNull()?.let { return it }
            }
            SystemClock.sleep(100L)
        }
        val controlExists = control.isFile
        val controlText = runCatching { control.readText(StandardCharsets.UTF_8) }.getOrNull() ?: "no control"
        val dir = result.parentFile
        val listing = dir?.listFiles()?.joinToString(",") { "${it.name}:${it.length()}" } ?: "no dir"
        val pkg = runCatching { device.currentPackageName }.getOrNull() ?: "unknown"
        throw AssertionError("Timed out waiting for R3 result $runId controlExists=$controlExists control=$controlText listing=[$listing] pkg=$pkg")
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
        const val R4_JOB_PREFIX = "KPL_YUV_FUSION_R4_"
        const val R4_EVIDENCE_DIRECTORY = "r4-gallery-cold"
        const val COHORT_FILE = "cohort.json"
        const val CONTROL_FILE = "control.json"
        const val RUN_ARGUMENT = "r3.run"
        const val R4_RUN_ARGUMENT = "r4.run"
        const val JOB_COUNT = 46
        const val JPEG_COUNT = 23
        const val HEIF_COUNT = 23
        const val R4_JOB_COUNT = 46
        const val R4_JPEG_COUNT = 23
        const val R4_HEIF_COUNT = 23
        const val UI_TIMEOUT_MS = 30_000L
        const val RESULT_TIMEOUT_MS = 120_000L
    }
}
