package com.projectnuke.keplernightlab

import android.os.Build

/**
 * U2.3-I3 — PRODUCTION CANARY ROLLOUT POLICY (pure, testable).
 *
 * This policy is SEPARATE from the safety predicate. It decides only whether the U2.3
 * fast path may be attempted at all in production. The safety predicate
 * ([evaluateU23Predicate]) still fail-closes every leg independently; this policy never
 * authorizes a hit, it only selects the validated canary scope.
 *
 * Initial canary = the exact physically validated platform class. Everything else is OFF,
 * and OFF means zero U2.3 cheap reads with the existing FULL verifier every cold start.
 * There is no fall-through to ON for unknown environments.
 *
 * Exact incremental pin: the validated `ro.build.version.incremental` is S921NKSUHZZHL,
 * resolved literally from Build.VERSION.INCREMENTAL and required by exact equality.
 * Build.DISPLAY is NOT rollout authority. A platform update changes the incremental, so
 * rollout goes OFF until revalidated. Unknown or blank incremental is OFF. Row
 * GENERATION_MODIFIED is never consulted here.
 */
internal data class U23Environment(
    val manufacturer: String,
    val model: String,
    val sdk: Int,
    /** Exact platform incremental; resolved from Build.VERSION.INCREMENTAL. */
    val platformIncremental: String
)

internal object U23RolloutPolicy {
    const val TARGET_MANUFACTURER = "samsung"
    const val TARGET_MODEL = "SM-S921N"
    const val TARGET_SDK = 37
    const val TARGET_PLATFORM_INCREMENTAL = "S921NKSUHZZHL"

    fun isProductionEnabled(env: U23Environment): Boolean {
        if (!env.manufacturer.equals(TARGET_MANUFACTURER, ignoreCase = true)) return false
        if (env.model != TARGET_MODEL) return false
        if (env.sdk != TARGET_SDK) return false
        if (env.platformIncremental != TARGET_PLATFORM_INCREMENTAL) return false
        return true
    }

    fun currentEnvironment(): U23Environment = U23Environment(
        manufacturer = Build.MANUFACTURER ?: "",
        model = Build.MODEL ?: "",
        sdk = Build.VERSION.SDK_INT,
        platformIncremental = Build.VERSION.INCREMENTAL ?: ""
    )
}
