package com.projectnuke.keplernightlab

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject

/**
 * U2.3-I1 — SAFE VERSION/VOLUME-GENERATION FAST PATH (DEFAULT OFF).
 *
 * Accepted design (§20C): COARSE INVALIDATION on exact-volume MediaStore version +
 * volume generation, with every other predicate leg fail-closed. ROW GENERATION IS
 * NEVER AN ALLOW-SIGNAL (C3) — it is not read, not persisted for authority, and never
 * consulted by the predicate below.
 *
 * When the gate is OFF, recovery behavior is byte-identical to baseline: no extra
 * provider reads, no evidence issuance, full verifier every cold start.
 */
internal object U23FastPathGate {
    /** Test/debug-only override. Production default is OFF; never persisted anywhere. */
    @Volatile
    var overrideForTest: Boolean = false

    fun isEnabled(): Boolean = BuildConfig.DEBUG && overrideForTest
}

/** Which verification actually executed for a recovery inspection result. */
internal enum class U23VerificationMode {
    FULL,
    STABLE_MEDIASTORE_EVIDENCE
}

internal const val U23_EVIDENCE_SCHEMA_VERSION = 1
internal const val U23_VERIFICATION_ALGORITHM_VERSION = 1

/**
 * Additive, versioned durable verification evidence. Issued ONLY from a stable full
 * verification (see [decideStableEvidence]); never inferred from legacy booleans.
 */
internal data class U23VerificationEvidence(
    val schemaVersion: Int,
    val algorithmVersion: Int,
    val exactVolumeName: String,
    val mediaStoreVersion: String,
    val volumeGeneration: Long,
    val rowId: Long,
    val uri: String,
    val size: Long,
    val mimeType: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val appVersionCode: Long,
    val bootCount: Int,
    val fullVerifiedAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("algorithmVersion", algorithmVersion)
        .put("exactVolumeName", exactVolumeName)
        .put("mediaStoreVersion", mediaStoreVersion)
        .put("volumeGeneration", volumeGeneration)
        .put("rowId", rowId)
        .put("uri", uri)
        .put("size", size)
        .put("mimeType", mimeType)
        .put("displayName", displayName)
        .put("width", width)
        .put("height", height)
        .put("appVersionCode", appVersionCode)
        .put("bootCount", bootCount)
        .put("fullVerifiedAt", fullVerifiedAt)

    companion object {
        fun fromJson(json: JSONObject): U23VerificationEvidence? {
            return try {
                U23VerificationEvidence(
                    schemaVersion = json.getInt("schemaVersion"),
                algorithmVersion = json.getInt("algorithmVersion"),
                exactVolumeName = json.getString("exactVolumeName").takeIf { it.isNotBlank() } ?: return null,
                mediaStoreVersion = json.getString("mediaStoreVersion").takeIf { it.isNotBlank() } ?: return null,
                volumeGeneration = json.getLong("volumeGeneration").takeIf { it >= 0 } ?: return null,
                rowId = json.getLong("rowId").takeIf { it > 0 } ?: return null,
                uri = json.getString("uri").takeIf { it.isNotBlank() } ?: return null,
                size = json.getLong("size").takeIf { it > 0 } ?: return null,
                mimeType = json.getString("mimeType").takeIf { it.isNotBlank() } ?: return null,
                displayName = json.getString("displayName").takeIf { it.isNotBlank() } ?: return null,
                width = json.getInt("width").takeIf { it > 0 } ?: return null,
                height = json.getInt("height").takeIf { it > 0 } ?: return null,
                appVersionCode = json.getLong("appVersionCode").takeIf { it >= 0 } ?: return null,
                bootCount = json.getInt("bootCount").takeIf { it >= 0 } ?: return null,
                fullVerifiedAt = json.getLong("fullVerifiedAt").takeIf { it > 0 } ?: return null
            )
        } catch (_: Exception) {
            null
        }
        }
    }
}

/** Stored-evidence shape: absent, malformed, or valid. Malformed is NOT valid. */
internal sealed interface U23StoredEvidence {
    data object Absent : U23StoredEvidence
    data object Malformed : U23StoredEvidence
    data class Valid(val evidence: U23VerificationEvidence) : U23StoredEvidence
}

internal enum class U23FallbackReason {
    NO_EVIDENCE,
    MALFORMED_EVIDENCE,
    SCHEMA_MISMATCH,
    ALGORITHM_MISMATCH,
    APP_VERSION_BOUNDARY,
    BOOT_BOUNDARY,
    ROW_MISSING,
    QUERY_FAILED,
    PENDING,
    IDENTITY_MISMATCH,
    MEDIASTORE_VERSION_MISMATCH,
    VOLUME_GENERATION_MISMATCH,
    SIZE_MISMATCH,
    MIME_MISMATCH,
    NAME_MISMATCH,
    DIMENSION_MISMATCH,
    UNSTABLE_FULL_VERIFY_SNAPSHOT
}

/** Explicit read semantics: failures are never "unchanged" or "changed". */
internal sealed interface U23Read<out T> {
    data class Value<T>(val value: T) : U23Read<T>
    data object RowAbsent : U23Read<Nothing>
    data class QueryFailed(val error: String) : U23Read<Nothing>
    data object Unavailable : U23Read<Nothing>
}

internal data class U23RowSnapshot(
    val id: Long,
    val pending: Boolean,
    val size: Long,
    val mimeType: String?,
    val displayName: String?,
    val width: Int,
    val height: Int
)

internal data class U23ProviderState(
    val version: U23Read<String>,
    val volumeGeneration: U23Read<Long>
)

/** Provider-read seam: production uses ContentResolver; host tests inject fakes. */
internal interface U23MediaReads {
    fun resolveVolume(uriString: String): U23Read<String>
    fun rowSnapshot(uriString: String): U23Read<U23RowSnapshot>
    fun providerState(volume: String): U23ProviderState
    fun bootCount(): U23Read<Int>
    fun appVersionCode(): U23Read<Long>
}

internal class AndroidU23MediaReads(private val context: Context) : U23MediaReads {
    override fun resolveVolume(uriString: String): U23Read<String> = try {
        val volume = MediaStore.getVolumeName(Uri.parse(uriString))
        if (volume.isBlank()) U23Read.QueryFailed("blank-volume") else U23Read.Value(volume)
    } catch (e: Exception) {
        U23Read.QueryFailed(e.javaClass.simpleName)
    }

    override fun rowSnapshot(uriString: String): U23Read<U23RowSnapshot> = try {
        val cursor = context.contentResolver.query(
            Uri.parse(uriString),
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT
            ),
            null, null, null
        ) ?: return U23Read.QueryFailed("null-cursor")
        cursor.use {
            if (!it.moveToFirst()) return U23Read.RowAbsent
            U23Read.Value(
                U23RowSnapshot(
                    id = it.getLong(0),
                    pending = it.getInt(1) != 0,
                    size = it.getLong(2),
                    mimeType = it.getString(3),
                    displayName = it.getString(4),
                    width = it.getInt(5),
                    height = it.getInt(6)
                )
            )
        }
    } catch (e: Exception) {
        U23Read.QueryFailed(e.javaClass.simpleName)
    }

    override fun providerState(volume: String): U23ProviderState {
        val version: U23Read<String> = try {
            val v = MediaStore.getVersion(context, volume)
            if (v.isNullOrBlank()) U23Read.QueryFailed("null-version") else U23Read.Value(v)
        } catch (e: Exception) {
            U23Read.QueryFailed(e.javaClass.simpleName)
        }
        val generation: U23Read<Long> = try {
            U23Read.Value(MediaStore.getGeneration(context, volume))
        } catch (e: Exception) {
            U23Read.QueryFailed(e.javaClass.simpleName)
        }
        return U23ProviderState(version, generation)
    }

    override fun bootCount(): U23Read<Int> = try {
        // Settings.Global.BOOT_COUNT: stable across process death, changes across reboot,
        // readable without any dangerous permission. -1 (default) means unavailable.
        val count = android.provider.Settings.Global.getInt(
            context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1
        )
        if (count < 0) U23Read.Unavailable else U23Read.Value(count)
    } catch (e: Exception) {
        U23Read.QueryFailed(e.javaClass.simpleName)
    }

    override fun appVersionCode(): U23Read<Long> = try {
        val info = context.packageManager.getPackageInfo(
            context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)
        )
        U23Read.Value(info.longVersionCode)
    } catch (e: Exception) {
        U23Read.QueryFailed(e.javaClass.simpleName)
    }
}

internal sealed interface U23Decision {
    data object Hit : U23Decision
    data class Miss(val reason: U23FallbackReason) : U23Decision
}

/**
 * Pure fast-path predicate (§20C.1). Row GENERATION_MODIFIED is deliberately not an
 * input: it can never authorize the fast path.
 *
 * Callers MUST check [U23FastPathGate.isEnabled] first: when the gate is OFF the
 * predicate is never evaluated and recovery takes the identical legacy path.
 */
internal fun evaluateU23Predicate(
    stored: U23StoredEvidence,
    journalUri: String,
    resolvedVolume: U23Read<String>,
    appVersionCode: U23Read<Long>,
    bootCount: U23Read<Int>,
    row: U23Read<U23RowSnapshot>,
    providerBefore: U23ProviderState,
    providerAfter: U23ProviderState
): U23Decision {
    val evidence = when (stored) {
        U23StoredEvidence.Absent -> return U23Decision.Miss(U23FallbackReason.NO_EVIDENCE)
        U23StoredEvidence.Malformed -> return U23Decision.Miss(U23FallbackReason.MALFORMED_EVIDENCE)
        is U23StoredEvidence.Valid -> stored.evidence
    }
    val volume = (resolvedVolume as? U23Read.Value)?.value
        ?: return U23Decision.Miss(U23FallbackReason.IDENTITY_MISMATCH)
    if (volume != evidence.exactVolumeName) {
        return U23Decision.Miss(U23FallbackReason.IDENTITY_MISMATCH)
    }
    if (evidence.schemaVersion != U23_EVIDENCE_SCHEMA_VERSION) {
        return U23Decision.Miss(U23FallbackReason.SCHEMA_MISMATCH)
    }
    if (evidence.algorithmVersion != U23_VERIFICATION_ALGORITHM_VERSION) {
        return U23Decision.Miss(U23FallbackReason.ALGORITHM_MISMATCH)
    }
    when (appVersionCode) {
        is U23Read.Value -> if (appVersionCode.value != evidence.appVersionCode) {
            return U23Decision.Miss(U23FallbackReason.APP_VERSION_BOUNDARY)
        }
        else -> return U23Decision.Miss(U23FallbackReason.APP_VERSION_BOUNDARY)
    }
    when (bootCount) {
        is U23Read.Value -> if (bootCount.value != evidence.bootCount) {
            return U23Decision.Miss(U23FallbackReason.BOOT_BOUNDARY)
        }
        else -> return U23Decision.Miss(U23FallbackReason.BOOT_BOUNDARY)
    }
    val snapshot = when (row) {
        is U23Read.Value -> row.value
        U23Read.RowAbsent -> return U23Decision.Miss(U23FallbackReason.ROW_MISSING)
        is U23Read.QueryFailed -> return U23Decision.Miss(U23FallbackReason.QUERY_FAILED)
        U23Read.Unavailable -> return U23Decision.Miss(U23FallbackReason.QUERY_FAILED)
    }
    if (snapshot.pending) return U23Decision.Miss(U23FallbackReason.PENDING)
    if (journalUri != evidence.uri || snapshot.id != evidence.rowId) {
        return U23Decision.Miss(U23FallbackReason.IDENTITY_MISMATCH)
    }
    // Ordering bracket (§7): version/gen before AND after the row snapshot must all
    // equal the persisted values. Any drift during inspection -> FULL VERIFY.
    val versionBefore = (providerBefore.version as? U23Read.Value)?.value
        ?: return U23Decision.Miss(versionMissReason(providerBefore.version))
    val versionAfter = (providerAfter.version as? U23Read.Value)?.value
        ?: return U23Decision.Miss(versionMissReason(providerAfter.version))
    if (versionBefore != evidence.mediaStoreVersion || versionAfter != evidence.mediaStoreVersion ||
        versionBefore != versionAfter
    ) {
        return U23Decision.Miss(U23FallbackReason.MEDIASTORE_VERSION_MISMATCH)
    }
    val genBefore = (providerBefore.volumeGeneration as? U23Read.Value)?.value
        ?: return U23Decision.Miss(U23FallbackReason.VOLUME_GENERATION_MISMATCH)
    val genAfter = (providerAfter.volumeGeneration as? U23Read.Value)?.value
        ?: return U23Decision.Miss(U23FallbackReason.VOLUME_GENERATION_MISMATCH)
    if (genBefore != evidence.volumeGeneration || genAfter != evidence.volumeGeneration ||
        genBefore != genAfter
    ) {
        return U23Decision.Miss(U23FallbackReason.VOLUME_GENERATION_MISMATCH)
    }
    // Exact volume is part of identity: reads were taken on the resolved volume, which
    // must equal the persisted one (caller resolves from the journal URI).
    if (snapshot.size != evidence.size) return U23Decision.Miss(U23FallbackReason.SIZE_MISMATCH)
    if (!snapshot.mimeType.equals(evidence.mimeType, ignoreCase = true)) {
        return U23Decision.Miss(U23FallbackReason.MIME_MISMATCH)
    }
    if (snapshot.displayName != evidence.displayName ||
        !u23AcceptsDisplayName(evidence.mimeType, snapshot.displayName)
    ) {
        return U23Decision.Miss(U23FallbackReason.NAME_MISMATCH)
    }
    if (snapshot.width != evidence.width || snapshot.height != evidence.height) {
        return U23Decision.Miss(U23FallbackReason.DIMENSION_MISMATCH)
    }
    return U23Decision.Hit
}

private fun versionMissReason(read: U23Read<String>): U23FallbackReason = when (read) {
    is U23Read.Value -> U23FallbackReason.MEDIASTORE_VERSION_MISMATCH
    U23Read.RowAbsent -> U23FallbackReason.MEDIASTORE_VERSION_MISMATCH
    is U23Read.QueryFailed -> U23FallbackReason.QUERY_FAILED
    U23Read.Unavailable -> U23FallbackReason.QUERY_FAILED
}

/** Display-name/extension truth mirrored from production verification policy. */
internal fun u23AcceptsDisplayName(mimeType: String, displayName: String?): Boolean {
    val lower = displayName?.lowercase(java.util.Locale.US) ?: return false
    if (lower.isBlank()) return false
    return when (mimeType.lowercase(java.util.Locale.US)) {
        "image/heif" -> lower.endsWith(".heif") || lower.endsWith(".heic")
        "image/jpeg" -> lower.endsWith(".jpg")
        "image/png" -> lower.endsWith(".png")
        else -> false
    }
}

/**
 * Stable evidence issuance (§9, pure). Returns evidence ONLY when a true FULL verifier
 * Verified result is bracketed by a stable version+generation window and the final row
 * metadata agrees with the verified result. Anything else -> null (no trust issued).
 */
internal fun decideStableEvidence(
    gateEnabled: Boolean,
    verifierVerified: GalleryExportVerification.Verified?,
    volume: String,
    journalUri: String,
    versionBefore: U23Read<String>,
    genBefore: U23Read<Long>,
    versionAfter: U23Read<String>,
    genAfter: U23Read<Long>,
    finalRow: U23Read<U23RowSnapshot>,
    appVersionCode: U23Read<Long>,
    bootCount: U23Read<Int>,
    nowMs: Long
): U23VerificationEvidence? {
    if (!gateEnabled || verifierVerified == null) return null
    val vb = (versionBefore as? U23Read.Value)?.value ?: return null
    val va = (versionAfter as? U23Read.Value)?.value ?: return null
    if (vb != va) return null
    val gb = (genBefore as? U23Read.Value)?.value ?: return null
    val ga = (genAfter as? U23Read.Value)?.value ?: return null
    if (gb != ga) return null
    val row = (finalRow as? U23Read.Value)?.value ?: return null
    if (row.pending) return null
    // Final row identity/metadata must agree with the verified result.
    if (row.size != verifierVerified.size) return null
    if (!row.mimeType.equals(verifierVerified.mediaStoreMime, ignoreCase = true)) return null
    if (row.displayName != verifierVerified.displayName) return null
    if (row.width != verifierVerified.width || row.height != verifierVerified.height) return null
    val app = (appVersionCode as? U23Read.Value)?.value ?: return null
    val boot = (bootCount as? U23Read.Value)?.value ?: return null
    return U23VerificationEvidence(
        schemaVersion = U23_EVIDENCE_SCHEMA_VERSION,
        algorithmVersion = U23_VERIFICATION_ALGORITHM_VERSION,
        exactVolumeName = volume,
        mediaStoreVersion = va,
        volumeGeneration = ga,
        rowId = row.id,
        uri = journalUri,
        size = row.size,
        mimeType = row.mimeType ?: verifierVerified.mediaStoreMime,
        displayName = row.displayName ?: verifierVerified.displayName,
        width = row.width,
        height = row.height,
        appVersionCode = app,
        bootCount = boot,
        fullVerifiedAt = nowMs
    )
}

/** In-memory only pilot/diagnostic counters. Never persisted. */
internal object U23Counters {
    private val lock = Any()
    var cheapInspections: Int = 0
        private set
    var fastPathHits: Int = 0
        private set
    var fullVerifierRuns: Int = 0
        private set
    var fallbacks: Int = 0
        private set
    private val fallbackReasons: MutableMap<U23FallbackReason, Int> = mutableMapOf()

    fun reset() = synchronized(lock) {
        cheapInspections = 0
        fastPathHits = 0
        fullVerifierRuns = 0
        fallbacks = 0
        fallbackReasons.clear()
    }

    fun cheapInspection() = synchronized(lock) { cheapInspections++ }
    fun fastPathHit() = synchronized(lock) { fastPathHits++ }
    fun fullVerifierRun() = synchronized(lock) { fullVerifierRuns++ }
    fun fallback(reason: U23FallbackReason) = synchronized(lock) {
        fallbacks++
        fallbackReasons[reason] = (fallbackReasons[reason] ?: 0) + 1
    }

    fun reasonCount(reason: U23FallbackReason): Int = synchronized(lock) {
        fallbackReasons[reason] ?: 0
    }

    fun snapshot(): Map<String, Int> = synchronized(lock) {
        buildMap {
            put("cheapInspections", cheapInspections)
            put("fastPathHits", fastPathHits)
            put("fullVerifierRuns", fullVerifierRuns)
            put("fallbacks", fallbacks)
            fallbackReasons.forEach { (reason, count) -> put("fallback:${reason.name}", count) }
        }
    }
}
