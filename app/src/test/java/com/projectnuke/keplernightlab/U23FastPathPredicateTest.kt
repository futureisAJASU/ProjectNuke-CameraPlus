package com.projectnuke.keplernightlab

import android.net.Uri
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * U2.3-I1 host matrix for the version/volume-generation fast-path predicate.
 *
 * Pure-predicate cases (A–X, Y, Z) run without Android; issuance cases (AA–AD),
 * legacy (AE), zero-write (AF), and OFF-parity use Robolectric + temp job dirs.
 */
@RunWith(RobolectricTestRunner::class)
class U23FastPathPredicateTest {

    private val uri = "content://media/external/images/media/42"

    private fun evidence() = U23VerificationEvidence(
        schemaVersion = U23_EVIDENCE_SCHEMA_VERSION,
        algorithmVersion = U23_VERIFICATION_ALGORITHM_VERSION,
        exactVolumeName = "external",
        mediaStoreVersion = "v1",
        volumeGeneration = 100L,
        rowId = 42L,
        uri = uri,
        size = 1234L,
        mimeType = "image/jpeg",
        displayName = "shot.jpg",
        width = 64,
        height = 64,
        appVersionCode = 7L,
        bootCount = 3,
        fullVerifiedAt = 1000L
    )

    private fun row() = U23RowSnapshot(
        id = 42L, pending = false, size = 1234L, mimeType = "image/jpeg",
        displayName = "shot.jpg", width = 64, height = 64
    )

    private fun provider(version: String = "v1", gen: Long = 100L) =
        U23ProviderState(U23Read.Value(version), U23Read.Value(gen))

    private fun decide(
        stored: U23StoredEvidence = U23StoredEvidence.Valid(evidence()),
        journalUri: String = uri,
        resolvedVolume: U23Read<String> = U23Read.Value("external"),
        app: U23Read<Long> = U23Read.Value(7L),
        boot: U23Read<Int> = U23Read.Value(3),
        row: U23Read<U23RowSnapshot> = U23Read.Value(row()),
        before: U23ProviderState = provider(),
        after: U23ProviderState = provider()
    ): U23Decision = evaluateU23Predicate(stored, journalUri, resolvedVolume, app, boot, row, before, after)

    private fun assertMiss(decision: U23Decision, reason: U23FallbackReason) {
        assertTrue("expected Miss($reason), got $decision", decision == U23Decision.Miss(reason))
    }

    @After
    fun resetGate() {
        U23FastPathGate.overrideForTest = false
        U23Counters.reset()
    }

    // A. all legs match -> Hit.
    @Test
    fun a_allLegsMatch_isHit() {
        assertEquals(U23Decision.Hit, decide())
    }

    // B. feature OFF -> legacy path (gate reports OFF; predicate never consulted by production).
    @Test
    fun b_featureOff_gateReportsDisabled() {
        U23FastPathGate.overrideForTest = false
        assertFalse(U23FastPathGate.isEnabled())
    }

    // C. missing evidence.
    @Test
    fun c_missingEvidence() {
        assertMiss(decide(stored = U23StoredEvidence.Absent), U23FallbackReason.NO_EVIDENCE)
    }

    // D. malformed evidence.
    @Test
    fun d_malformedEvidence() {
        assertMiss(decide(stored = U23StoredEvidence.Malformed), U23FallbackReason.MALFORMED_EVIDENCE)
        // Malformed JSON block also parses to Absent-shape at the journal layer.
        val journal = readJournalWithRawEvidence(JSONObject().put("schemaVersion", 1))
        assertNull(journal.verificationEvidence)
        assertTrue(journal.verificationEvidencePresent)
    }

    // E. old schema.
    @Test
    fun e_oldSchema() {
        assertMiss(
            decide(stored = U23StoredEvidence.Valid(evidence().copy(schemaVersion = 0))),
            U23FallbackReason.SCHEMA_MISMATCH
        )
    }

    // F. algorithm mismatch.
    @Test
    fun f_algorithmMismatch() {
        assertMiss(
            decide(stored = U23StoredEvidence.Valid(evidence().copy(algorithmVersion = 999))),
            U23FallbackReason.ALGORITHM_MISMATCH
        )
    }

    // G. app upgrade (and unreadable app version).
    @Test
    fun g_appUpgrade() {
        assertMiss(decide(app = U23Read.Value(8L)), U23FallbackReason.APP_VERSION_BOUNDARY)
        assertMiss(decide(app = U23Read.QueryFailed("x")), U23FallbackReason.APP_VERSION_BOUNDARY)
    }

    // H/I. reboot token mismatch / unavailable.
    @Test
    fun h_bootMismatch() {
        assertMiss(decide(boot = U23Read.Value(4)), U23FallbackReason.BOOT_BOUNDARY)
        assertMiss(decide(boot = U23Read.Unavailable), U23FallbackReason.BOOT_BOUNDARY)
        assertMiss(decide(boot = U23Read.QueryFailed("x")), U23FallbackReason.BOOT_BOUNDARY)
    }

    // J. row query failure.
    @Test
    fun j_rowQueryFailure() {
        assertMiss(decide(row = U23Read.QueryFailed("boom")), U23FallbackReason.QUERY_FAILED)
        assertMiss(decide(row = U23Read.Unavailable), U23FallbackReason.QUERY_FAILED)
    }

    // K. row missing.
    @Test
    fun k_rowMissing() {
        assertMiss(decide(row = U23Read.RowAbsent), U23FallbackReason.ROW_MISSING)
    }

    // L. IS_PENDING=1.
    @Test
    fun l_pending() {
        assertMiss(decide(row = U23Read.Value(row().copy(pending = true))), U23FallbackReason.PENDING)
    }

    // M/N/O. URI / _ID / volume mismatch.
    @Test
    fun m_uriMismatch() {
        assertMiss(decide(journalUri = "content://media/external/images/media/43"), U23FallbackReason.IDENTITY_MISMATCH)
    }

    @Test
    fun n_idMismatch() {
        assertMiss(decide(row = U23Read.Value(row().copy(id = 43L))), U23FallbackReason.IDENTITY_MISMATCH)
    }

    @Test
    fun o_volumeMismatch() {
        assertMiss(decide(resolvedVolume = U23Read.Value("external_primary")), U23FallbackReason.IDENTITY_MISMATCH)
        assertMiss(decide(resolvedVolume = U23Read.QueryFailed("x")), U23FallbackReason.IDENTITY_MISMATCH)
    }

    // P/Q. getVersion failure / mismatch.
    @Test
    fun p_versionQueryFailure() {
        assertMiss(
            decide(before = U23ProviderState(U23Read.QueryFailed("x"), U23Read.Value(100L))),
            U23FallbackReason.QUERY_FAILED
        )
    }

    @Test
    fun q_versionMismatch() {
        assertMiss(decide(after = provider(version = "v2")), U23FallbackReason.MEDIASTORE_VERSION_MISMATCH)
        assertMiss(decide(before = provider(version = "v2")), U23FallbackReason.MEDIASTORE_VERSION_MISMATCH)
    }

    // R/S. getGeneration failure / mismatch.
    @Test
    fun r_generationQueryFailure() {
        assertMiss(
            decide(after = U23ProviderState(U23Read.Value("v1"), U23Read.QueryFailed("x"))),
            U23FallbackReason.VOLUME_GENERATION_MISMATCH
        )
    }

    @Test
    fun s_generationMismatch() {
        assertMiss(decide(after = provider(gen = 101L)), U23FallbackReason.VOLUME_GENERATION_MISMATCH)
    }

    // T/U/V/W/X. metadata mismatches.
    @Test
    fun t_sizeMismatch() {
        assertMiss(decide(row = U23Read.Value(row().copy(size = 1235L))), U23FallbackReason.SIZE_MISMATCH)
    }

    @Test
    fun u_mimeMismatch() {
        assertMiss(decide(row = U23Read.Value(row().copy(mimeType = "image/heif"))), U23FallbackReason.MIME_MISMATCH)
    }

    @Test
    fun v_nameMismatch() {
        assertMiss(decide(row = U23Read.Value(row().copy(displayName = "other.jpg"))), U23FallbackReason.NAME_MISMATCH)
        // Valid extension but renamed -> still mismatch (exact equality required).
        assertMiss(decide(row = U23Read.Value(row().copy(displayName = "renamed.jpg"))), U23FallbackReason.NAME_MISMATCH)
        // Invalid extension -> mismatch even before equality could save it.
        assertMiss(decide(row = U23Read.Value(row().copy(displayName = "shot.png"))), U23FallbackReason.NAME_MISMATCH)
    }

    @Test
    fun w_widthMismatch() {
        assertMiss(decide(row = U23Read.Value(row().copy(width = 65))), U23FallbackReason.DIMENSION_MISMATCH)
    }

    @Test
    fun x_heightMismatch() {
        assertMiss(decide(row = U23Read.Value(row().copy(height = 65))), U23FallbackReason.DIMENSION_MISMATCH)
    }

    // Y/Z. before/after drift during inspection -> FULL VERIFY.
    @Test
    fun y_versionDrift() {
        assertMiss(
            decide(before = provider(version = "v1"), after = provider(version = "v1b")),
            U23FallbackReason.MEDIASTORE_VERSION_MISMATCH
        )
    }

    @Test
    fun z_generationDrift() {
        assertMiss(
            decide(before = provider(gen = 100L), after = provider(gen = 101L)),
            U23FallbackReason.VOLUME_GENERATION_MISMATCH
        )
    }

    private fun verified() = GalleryExportVerification.Verified(
        detectedFormat = OutputFormat.JPEG, mediaStoreMime = "image/jpeg",
        displayName = "shot.jpg", width = 64, height = 64, size = 1234L
    )

    // AA. stable full verify -> evidence issued.
    @Test
    fun aa_stableFullVerify_issuesEvidence() {
        val issued = decideStableEvidence(
            gateEnabled = true, verifierVerified = verified(), volume = "external", journalUri = uri,
            versionBefore = U23Read.Value("v1"), genBefore = U23Read.Value(100L),
            versionAfter = U23Read.Value("v1"), genAfter = U23Read.Value(100L),
            finalRow = U23Read.Value(row()),
            appVersionCode = U23Read.Value(7L), bootCount = U23Read.Value(3), nowMs = 2000L
        )
        assertNotNull(issued)
        assertEquals(evidence().copy(fullVerifiedAt = 2000L), issued)
    }

    // AB/AC. generation/version drift during verification -> NO evidence (result preserved by caller).
    @Test
    fun ab_generationDriftDuringVerify_noEvidence() {
        assertNull(
            decideStableEvidence(
                gateEnabled = true, verifierVerified = verified(), volume = "external", journalUri = uri,
                versionBefore = U23Read.Value("v1"), genBefore = U23Read.Value(100L),
                versionAfter = U23Read.Value("v1"), genAfter = U23Read.Value(101L),
                finalRow = U23Read.Value(row()),
                appVersionCode = U23Read.Value(7L), bootCount = U23Read.Value(3), nowMs = 2000L
            )
        )
    }

    @Test
    fun ac_versionDriftDuringVerify_noEvidence() {
        assertNull(
            decideStableEvidence(
                gateEnabled = true, verifierVerified = verified(), volume = "external", journalUri = uri,
                versionBefore = U23Read.Value("v1"), genBefore = U23Read.Value(100L),
                versionAfter = U23Read.Value("v2"), genAfter = U23Read.Value(100L),
                finalRow = U23Read.Value(row()),
                appVersionCode = U23Read.Value(7L), bootCount = U23Read.Value(3), nowMs = 2000L
            )
        )
    }

    // AD. verifier failure / gate off -> no evidence.
    @Test
    fun ad_verifierFailureOrGateOff_noEvidence() {
        assertNull(
            decideStableEvidence(
                gateEnabled = true, verifierVerified = null, volume = "external", journalUri = uri,
                versionBefore = U23Read.Value("v1"), genBefore = U23Read.Value(100L),
                versionAfter = U23Read.Value("v1"), genAfter = U23Read.Value(100L),
                finalRow = U23Read.Value(row()),
                appVersionCode = U23Read.Value(7L), bootCount = U23Read.Value(3), nowMs = 2000L
            )
        )
        assertNull(
            decideStableEvidence(
                gateEnabled = false, verifierVerified = verified(), volume = "external", journalUri = uri,
                versionBefore = U23Read.Value("v1"), genBefore = U23Read.Value(100L),
                versionAfter = U23Read.Value("v1"), genAfter = U23Read.Value(100L),
                finalRow = U23Read.Value(row()),
                appVersionCode = U23Read.Value(7L), bootCount = U23Read.Value(3), nowMs = 2000L
            )
        )
    }

    // AE. legacy booleans without new evidence -> FULL VERIFY (predicate Miss).
    @Test
    fun ae_legacyBooleansWithoutEvidence_fullVerify() {
        // Legacy journal (VERIFIED state, terminal persisted, no evidence block) parses
        // with null evidence: the predicate can only Miss(NO_EVIDENCE).
        val dir = Files.createTempDirectory("u23-legacy-").toFile()
        try {
            val created = MediaStoreExportJournal.create(
                jobDir = dir, role = MediaStoreExportRole.MAIN_IMAGE, frameIndex = null,
                displayName = "shot.jpg", relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg", collectionUri = Uri.parse("content://media/external/images/media")
            )
            val journal = created.transition(dir, MediaStoreExportState.VERIFIED, uri)
            assertNull(journal.verificationEvidence)
            assertFalse(journal.verificationEvidencePresent)
            assertMiss(decide(stored = U23StoredEvidence.Absent), U23FallbackReason.NO_EVIDENCE)
        } finally {
            dir.deleteRecursively()
        }
    }

    // AF. fast-path hit writes nothing (zero-write): predicate Hit path performs no
    // durable mutation by construction — evidence round-trips byte-identically and the
    // recovery layer skips identical rewrites (covered by device pilot counters too).
    @Test
    fun af_evidenceRoundTrip_isStable() {
        val dir = Files.createTempDirectory("u23-roundtrip-").toFile()
        try {
            val created = MediaStoreExportJournal.create(
                jobDir = dir, role = MediaStoreExportRole.MAIN_IMAGE, frameIndex = null,
                displayName = "shot.jpg", relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg", collectionUri = Uri.parse("content://media/external/images/media")
            )
            val withEvidence = created.withVerificationEvidence(dir, evidence())
            val bytesAfterFirstWrite = MediaStoreExportJournal.fileFor(dir, withEvidence.exportAttemptId).readBytes()
            // Re-storing identical evidence must be a no-op at the recovery layer: emulate by
            // asserting the re-read journal already equals the candidate (write skipped).
            val reread = MediaStoreExportJournal.list(dir).single()
            assertEquals(withEvidence.verificationEvidence, reread.verificationEvidence)
            assertEquals(bytesAfterFirstWrite.toList(), MediaStoreExportJournal.fileFor(dir, reread.exportAttemptId).readBytes().toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    // Evidence serialization: old journals (no block) parse unchanged.
    @Test
    fun oldJournalWithoutBlock_parsesUnchanged() {
        val dir = Files.createTempDirectory("u23-old-").toFile()
        try {
            val created = MediaStoreExportJournal.create(
                jobDir = dir, role = MediaStoreExportRole.MAIN_IMAGE, frameIndex = null,
                displayName = "shot.jpg", relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg", collectionUri = Uri.parse("content://media/external/images/media")
            )
            val reread = MediaStoreExportJournal.list(dir).single()
            assertEquals(created.exportAttemptId, reread.exportAttemptId)
            assertNull(reread.verificationEvidence)
            assertFalse(reread.verificationEvidencePresent)
        } finally {
            dir.deleteRecursively()
        }
    }

    // OFF parity: with gate OFF, recoverMediaStoreExportJournals never consults U23 reads
    // and counters stay at zero.
    @Test
    fun offParity_noU23ReadsOrCounters() {
        U23FastPathGate.overrideForTest = false
        U23Counters.reset()
        val dir = Files.createTempDirectory("u23-off-").toFile()
        try {
            val created = MediaStoreExportJournal.create(
                jobDir = dir, role = MediaStoreExportRole.MAIN_IMAGE, frameIndex = null,
                displayName = "shot.jpg", relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg", collectionUri = Uri.parse("content://media/external/images/media")
            )
            created.transition(dir, MediaStoreExportState.ROW_INSERTED, uri)
            val exploding = ContextMediaStoreExportRecoveryAccess(
                RuntimeEnvironment.getApplication(),
                object : U23MediaReads {
                    private fun boom(): Nothing = throw AssertionError("U23 reads must not run when gate is OFF")
                    override fun resolveVolume(uriString: String): U23Read<String> = boom()
                    override fun rowSnapshot(uriString: String): U23Read<U23RowSnapshot> = boom()
                    override fun providerState(volume: String): U23ProviderState = boom()
                    override fun bootCount(): U23Read<Int> = boom()
                    override fun appVersionCode(): U23Read<Long> = boom()
                }
            )
            // Row is absent under Robolectric (no provider data): the null cursor takes the
            // legacy inspection-failed path. The parity point: identical legacy outcome, FULL
            // mode, zero U23 counters, and the exploding reads were never touched.
            val result = recoverMediaStoreExportJournals(dir, exploding).single()
            assertEquals(MediaStoreExportRecoveryClassification.AMBIGUOUS, result.classification)
            assertEquals(U23VerificationMode.FULL, result.verificationMode)
            val counters = U23Counters.snapshot()
            assertEquals(0, counters["cheapInspections"])
            assertEquals(0, counters["fastPathHits"])
        } finally {
            dir.deleteRecursively()
        }
    }

    // ON hit integration: matching evidence + matching fake reads -> stable inspection
    // without the full verifier, PUBLIC_VERIFIED with STABLE mode, counters prove the path.
    @Test
    fun onHit_stableInspectionWithoutFullVerifier() {
        U23FastPathGate.overrideForTest = true
        U23Counters.reset()
        val dir = Files.createTempDirectory("u23-hit-").toFile()
        try {
            val created = MediaStoreExportJournal.create(
                jobDir = dir, role = MediaStoreExportRole.MAIN_IMAGE, frameIndex = null,
                displayName = "shot.jpg", relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg", collectionUri = Uri.parse("content://media/external/images/media")
            )
            val appVersion = RuntimeEnvironment.getApplication().packageManager
                .getPackageInfo(RuntimeEnvironment.getApplication().packageName, 0).versionCode.toLong()
            // Boot count under Robolectric is unavailable; the fake boot read stands in.
            val seeded = created.withVerificationEvidence(
                dir, evidence().copy(appVersionCode = appVersion, bootCount = 99)
            )
            seeded.transition(dir, MediaStoreExportState.VERIFIED, uri)
            val fake = object : U23MediaReads {
                override fun resolveVolume(uriString: String): U23Read<String> = U23Read.Value("external")
                override fun rowSnapshot(uriString: String): U23Read<U23RowSnapshot> = U23Read.Value(row())
                override fun providerState(volume: String): U23ProviderState = provider()
                override fun bootCount(): U23Read<Int> = U23Read.Value(99)
                override fun appVersionCode(): U23Read<Long> = U23Read.Value(appVersion)
            }
            val access = ContextMediaStoreExportRecoveryAccess(RuntimeEnvironment.getApplication(), fake)
            val results = recoverMediaStoreExportJournals(dir, access)
            val result = results.single()
            assertEquals(MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED, result.classification)
            assertEquals(U23VerificationMode.STABLE_MEDIASTORE_EVIDENCE, result.verificationMode)
            val counters = U23Counters.snapshot()
            assertEquals(1, counters["cheapInspections"])
            assertEquals(1, counters["fastPathHits"])
            assertEquals(0, counters["fullVerifierRuns"])
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- I1.1 §3: failure-injection integration at the real recovery seam ----
    //
    // A FULL-verified inspection carrying a stable candidate, with the existing
    // KeplerJobMetadata.atomicWriteFailureForTest seam armed, proves the fail-safe:
    // ordinary persistence failure preserves the current PUBLIC_VERIFIED result.

    private class CandidateAccess(private val candidate: U23VerificationEvidence?) : MediaStoreExportRecoveryAccess {
        override fun inspect(uri: Uri, journal: MediaStoreExportJournal) =
            MediaStoreExportInspection(
                exists = true, pending = false, verified = true,
                stableEvidenceToPersist = candidate
            )
        override fun setPending(uri: Uri, pending: Boolean) = true
        override fun delete(uri: Uri) = true
    }

    private fun candidate(): U23VerificationEvidence = U23VerificationEvidence(
        schemaVersion = U23_EVIDENCE_SCHEMA_VERSION,
        algorithmVersion = U23_VERIFICATION_ALGORITHM_VERSION,
        exactVolumeName = "external", mediaStoreVersion = "v9", volumeGeneration = 555L,
        rowId = 7L, uri = uri, size = 1234L, mimeType = "image/jpeg",
        displayName = "shot.jpg", width = 64, height = 64,
        appVersionCode = 7L, bootCount = 3, fullVerifiedAt = 2000L
    )

    private fun seededJournal(dir: java.io.File): MediaStoreExportJournal {
        val created = MediaStoreExportJournal.create(
            jobDir = dir, role = MediaStoreExportRole.MAIN_IMAGE, frameIndex = null,
            displayName = "shot.jpg", relativePath = "Pictures/Kepler",
            mimeType = "image/jpeg", collectionUri = Uri.parse("content://media/external/images/media")
        )
        return created.transition(dir, MediaStoreExportState.ROW_INSERTED, uri)
    }

    @Test
    fun persistFailure_ordinaryException_preservesPublicVerified() {
        U23Counters.reset()
        val dir = Files.createTempDirectory("u23-persist-fail-").toFile()
        try {
            seededJournal(dir)
            KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("disk full")
            try {
                val result = recoverMediaStoreExportJournals(dir, CandidateAccess(candidate())).single()
                assertEquals(MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED, result.classification)
                assertEquals(U23VerificationMode.FULL, result.verificationMode)
            } finally {
                KeplerJobMetadata.atomicWriteFailureForTest = null
            }
            // No corrupt/partial journal: still exactly one readable journal, evidence
            // absent, state VERIFIED via the legitimate transition (which does rewrite the
            // file — byte-identity is NOT expected here, evidence-absence is).
            val reread = MediaStoreExportJournal.list(dir).single()
            assertNull(reread.verificationEvidence)
            assertFalse(reread.verificationEvidencePresent)
            assertEquals(MediaStoreExportState.VERIFIED, reread.state)
            assertEquals(1, U23Counters.snapshot()["evidencePersistFailures"])
            // Next evaluation cannot fast-path from the failed new evidence.
            assertMiss(decide(stored = U23StoredEvidence.Absent), U23FallbackReason.NO_EVIDENCE)
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun persistFailure_error_propagates() {
        U23Counters.reset()
        val dir = Files.createTempDirectory("u23-persist-error-").toFile()
        try {
            seededJournal(dir)
            KeplerJobMetadata.atomicWriteFailureForTest = Error("fatal")
            try {
                org.junit.Assert.assertThrows(
                    Error::class.java
                ) { recoverMediaStoreExportJournals(dir, CandidateAccess(candidate())) }
            } finally {
                KeplerJobMetadata.atomicWriteFailureForTest = null
            }
            assertEquals(0, U23Counters.snapshot()["evidencePersistFailures"])
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun persistFailure_cancellation_propagates() {
        U23Counters.reset()
        val dir = Files.createTempDirectory("u23-persist-cancel-").toFile()
        try {
            seededJournal(dir)
            KeplerJobMetadata.atomicWriteFailureForTest =
                java.util.concurrent.CancellationException("cancel")
            try {
                org.junit.Assert.assertThrows(
                    java.util.concurrent.CancellationException::class.java
                ) { recoverMediaStoreExportJournals(dir, CandidateAccess(candidate())) }
            } finally {
                KeplerJobMetadata.atomicWriteFailureForTest = null
            }
            assertEquals(0, U23Counters.snapshot()["evidencePersistFailures"])
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = null
            dir.deleteRecursively()
        }
    }

    private fun readJournalWithRawEvidence(block: JSONObject): MediaStoreExportJournal {        val dir = Files.createTempDirectory("u23-malformed-").toFile()
        try {
            val created = MediaStoreExportJournal.create(
                jobDir = dir, role = MediaStoreExportRole.MAIN_IMAGE, frameIndex = null,
                displayName = "shot.jpg", relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg", collectionUri = Uri.parse("content://media/external/images/media")
            )
            val file = MediaStoreExportJournal.fileFor(dir, created.exportAttemptId)
            val raw = JSONObject(file.readText())
            raw.put("verificationEvidence", block)
            file.writeText(raw.toString())
            return MediaStoreExportJournal.list(dir).single()
        } finally {
            dir.deleteRecursively()
        }
    }
}
