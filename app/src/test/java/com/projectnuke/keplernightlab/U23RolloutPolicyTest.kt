package com.projectnuke.keplernightlab

import android.net.Uri
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * U2.3-I3 non-canary proof for the production rollout policy.
 *
 * The policy is pure and separate from the safety predicate. The validated target
 * (Samsung SM-S921N, API 37, platform incremental S921NKSUHZZHL) is ON; every other
 * environment is OFF with no fall-through to ON. Rollout OFF means zero U2.3 cheap
 * reads and the existing FULL verifier path.
 */
@RunWith(RobolectricTestRunner::class)
class U23RolloutPolicyTest {

    private fun validated() = U23Environment(
        manufacturer = "samsung",
        model = "SM-S921N",
        sdk = 37,
        platformIncremental = "S921NKSUHZZHL"
    )

    @After
    fun resetGate() {
        U23FastPathGate.testOverride = U23TestOverride.UNSET
        U23Counters.reset()
    }

    @Test
    fun validatedTarget_isProductionEnabled() {
        assertTrue(U23RolloutPolicy.isProductionEnabled(validated()))
    }

    @Test
    fun wrongModel_isOff() {
        assertFalse(U23RolloutPolicy.isProductionEnabled(validated().copy(model = "SM-S928N")))
        assertFalse(U23RolloutPolicy.isProductionEnabled(validated().copy(model = "Pixel 9")))
        assertFalse(U23RolloutPolicy.isProductionEnabled(validated().copy(model = "")))
    }

    @Test
    fun wrongApi_isOff() {
        assertFalse(U23RolloutPolicy.isProductionEnabled(validated().copy(sdk = 36)))
        assertFalse(U23RolloutPolicy.isProductionEnabled(validated().copy(sdk = 38)))
        assertFalse(U23RolloutPolicy.isProductionEnabled(validated().copy(sdk = 0)))
    }

    @Test
    fun unsupportedManufacturer_isOff() {
        assertFalse(U23RolloutPolicy.isProductionEnabled(validated().copy(manufacturer = "Google")))
        assertFalse(U23RolloutPolicy.isProductionEnabled(validated().copy(manufacturer = "unknown")))
        assertFalse(U23RolloutPolicy.isProductionEnabled(validated().copy(manufacturer = "")))
    }

    @Test
    fun platformIncrementalMismatch_isOff() {
        // Different incremental (OTA) -> OFF until revalidated.
        assertFalse(
            U23RolloutPolicy.isProductionEnabled(
                validated().copy(platformIncremental = "S921NKSUHZZI1")
            )
        )
        // Blank/unknown incremental -> OFF.
        assertFalse(U23RolloutPolicy.isProductionEnabled(validated().copy(platformIncremental = "")))
        // Prefix trick (extra leading content) -> OFF (exact equality, not endsWith).
        assertFalse(
            U23RolloutPolicy.isProductionEnabled(
                validated().copy(platformIncremental = "XS921NKSUHZZHL")
            )
        )
        // Suffix trick (extra trailing content) -> OFF.
        assertFalse(
            U23RolloutPolicy.isProductionEnabled(
                validated().copy(platformIncremental = "S921NKSUHZZHLX")
            )
        )
        // Full Build.DISPLAY string is NOT the incremental -> OFF. DISPLAY is diagnostic
        // only and never rollout authority.
        assertFalse(
            U23RolloutPolicy.isProductionEnabled(
                validated().copy(platformIncremental = "CP2A.260605.016.S921NKSUHZZHL")
            )
        )
    }

    @Test
    fun blankEnvironment_neverFallsThroughToOn() {
        assertFalse(
            U23RolloutPolicy.isProductionEnabled(
                U23Environment(manufacturer = "", model = "", sdk = 0, platformIncremental = "")
            )
        )
    }

    @Test
    fun manufacturerMatching_isCaseInsensitive_butOtherLegsStillApply() {
        assertTrue(U23RolloutPolicy.isProductionEnabled(validated().copy(manufacturer = "Samsung")))
        assertTrue(U23RolloutPolicy.isProductionEnabled(validated().copy(manufacturer = "SAMSUNG")))
        assertFalse(
            U23RolloutPolicy.isProductionEnabled(
                validated().copy(manufacturer = "Samsung", model = "SM-S928N")
            )
        )
    }

    @Test
    fun robolectricEnvironment_isNonCanary_policyOff_gateOff() {
        val env = U23RolloutPolicy.currentEnvironment()
        assertFalse(
            "unit-test environment must never be the canary (env=$env)",
            U23RolloutPolicy.isProductionEnabled(env)
        )
        U23FastPathGate.testOverride = U23TestOverride.UNSET
        assertFalse(U23FastPathGate.isEnabled())
    }

    // Rollout OFF (via policy, not a forced override) -> ZERO U2.3 cheap reads and the
    // existing FULL verifier path. Any U23 provider read throws.
    @Test
    fun policyOff_zeroCheapReads_fullVerifierPath() {
        U23FastPathGate.testOverride = U23TestOverride.UNSET
        assertFalse(U23FastPathGate.isEnabled())
        U23Counters.reset()
        val dir = Files.createTempDirectory("u23-rolloff-").toFile()
        try {
            val created = MediaStoreExportJournal.create(
                jobDir = dir, role = MediaStoreExportRole.MAIN_IMAGE, frameIndex = null,
                displayName = "shot.jpg", relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg", collectionUri = Uri.parse("content://media/external/images/media")
            )
            created.transition(dir, MediaStoreExportState.ROW_INSERTED, "content://media/external/images/media/42")
            val exploding = ContextMediaStoreExportRecoveryAccess(
                RuntimeEnvironment.getApplication(),
                object : U23MediaReads {
                    private fun boom(): Nothing = throw AssertionError("U23 reads must not run when rollout is OFF")
                    override fun resolveVolume(uriString: String): U23Read<String> = boom()
                    override fun rowSnapshot(uriString: String): U23Read<U23RowSnapshot> = boom()
                    override fun providerState(volume: String): U23ProviderState = boom()
                    override fun bootCount(): U23Read<Int> = boom()
                    override fun appVersionCode(): U23Read<Long> = boom()
                }
            )
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
}
