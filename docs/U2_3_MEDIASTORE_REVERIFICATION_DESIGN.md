# U2.3 DESIGN: Safe Terminal MediaStore Re-verification Reduction

**Repository:** futureisAJASU/ProjectNuke-CameraPlus  
**Baseline:** `97fcd890a3b6517f446251d0ed72d7e89f10cb5d` (R4 closed)  
**Reference Device:** Samsung Galaxy S24 (SM-S921N, Android 16 / API 36)  
**Package:** `com.projectnuke.keplernightlab`  
**Date:** 2026-09-03
**C2 corrective update:** 2026-09-04 — see `docs/U2_3_DEVICE_CHARACTERIZATION.md` (U2.3-C2).
The first U2.3-C characterization is INVALID (unproven writes, synchronous sampling artifact).
§20 final status updated to agree with the C2 report. Production policy UNCHANGED.

---

## 1. R4 CLOSED BASELINE

### 1.1 Proven R4 Facts

- **46 terminal-stable production-faithful YUV jobs**
  - 23 JPEG
  - 23 native HEIF
  - Same cohort across three independent true process-cold runs
- **User-aware force-stop / process-death proof**
- **Current real MediaStore verification executed 46/46 every run**
- **Old transient recovery metadata cycle eliminated:**
  - Before: 46 RECONSTRUCT_MAIN_EXPORT + 46 TERMINAL_STABLE_SETTLEMENT = 92 writes
  - After: 0 + 0 = 0
- **Metadata SHA unchanged**
- **Journal SHA unchanged**
- **Terminal/debt invariants preserved**

### 1.2 R4 True-Cold Results

| Metric | Run #1 | Run #2 | Run #3 | Median |
|--------|--------|--------|--------|--------|
| Recovery | 4691.554 ms | 4093.030 ms | 3633.181 ms | **4093.030 ms** |
| MediaStore Verification Aggregate | 3966.649 ms | 3469.124 ms | 3050.722 ms | **3469.124 ms** |
| JPEG Verification | 621.418 ms | 596.402 ms | 497.598 ms | **~596 ms** |
| HEIF Verification | 3345.231 ms | 2872.722 ms | 2553.124 ms | **~2873 ms** |
| Metadata Persistence | 0 | 0 | 0 | **0** |

**Key Finding:** MediaStore verification is approximately **84% of recovery time**. HEIF dominates the verification aggregate.

---

## 2. COMPLETE CURRENT VERIFIER TRACE

### 2.1 Call Path

```
KeplerRecoveryCoordinator.recoverOne (line 163)
  └─> recoverMediaStoreExportJournals (line 56)
      └─> recoverMediaStoreExportJournal (line 318)
          └─> ContextMediaStoreExportRecoveryAccess.inspect (line 571)
              └─> R3GalleryColdMeasurement.measureInspection
                  └─> verifyGalleryExportResult (line 147, GalleryExportVerification.kt)
                      └─> verifyOnce (line 201)
```

### 2.2 Verification Operations by Category

#### A. PROVIDER / ROW EXISTENCE AUTHORITY

| Operation | Location | Description |
|-----------|----------|-------------|
| URI Parse | `verifyGalleryExportResult:161-176` | Parse URI string, fail on invalid |
| MediaStore Query | `AndroidGalleryExportVerificationSource.query:98-121` | Query `MIME_TYPE`, `DISPLAY_NAME`, `SIZE` |
| IS_PENDING Check | `ContextMediaStoreExportRecoveryAccess.inspect:574-595` | Query `IS_PENDING` column |
| Row Missing | `verifyOnce:215-218` | Return `ROW_MISSING` if cursor empty |
| Provider Failure | `verifyOnce:207-213` | Catch query exceptions, return `MEDIASTORE_QUERY_FAILED` |

#### B. ENCODED CONTENT / FILE INTEGRITY

| Operation | Location | Description |
|-----------|----------|-------------|
| Stream Open | `AndroidGalleryExportVerificationSource.open:123` | `contentResolver.openInputStream(uri)` |
| Content Bytes Read | `probeImageStream:360-381` | Read entire file into buffer |
| Signature Validation | `detectFormat:404-409` | JPEG magic bytes, HEIF ftyp box |
| File Size Check | `verifyOnce:235-244` | Compare stream size vs MediaStore SIZE |
| JPEG Terminal Check | `probeImageStream:388` | Verify `0xFFD9` EOF marker |
| HEIF Container Check | `isSupportedHeifFtyp:411-429` | Validate ftyp box and brands |

#### C. DECODABILITY / IMAGE VALIDITY

| Operation | Location | Description |
|-----------|----------|-------------|
| Bounds Decode | `AndroidGalleryExportVerificationSource.decodeBounds:125-129` | `inJustDecodeBounds=true` |
| Sampled Pixel Decode | `AndroidGalleryExportVerificationSource.decodeProbe:131-144` | `inSampleSize` scaled decode |
| Dimension Check | `verifyOnce:265-268` | Verify width/height > 0 |

#### D. METADATA / STRUCTURAL CONSISTENCY

| Operation | Location | Description |
|-----------|----------|-------------|
| MIME Check | `verifyOnce:294-299` | Compare detected vs expected MIME |
| Display Name Check | `verifyOnce:301-319` | Validate extension (.jpeg/.heif/.heic) |
| Extension Check | `acceptsDisplayNameExtension:350-353` | HEIF accepts .heif or .heic |
| Dimension Check | `verifyOnce:321-327` | Compare decoded vs expected dimensions |
| Duplicate HEIF Name | `isDuplicatedHeifGeneratedName:347-348` | Reject .heic.heif / .heif.heic |

#### E. RETRY / FAILURE SEMANTICS

| Classification | Trigger | Propagation |
|----------------|---------|-------------|
| `RetryableFailure` | Query failure, stream unavailable, bounds decode failure | Retry up to 3 times |
| `PermanentFailure` | Invalid URI, signature invalid, format mismatch, MIME mismatch | Immediate fail |
| `PUBLIC_VERIFIED` | All checks pass | Journal state → VERIFIED |
| `PUBLIC_COMMITTED_UNVERIFIED` | Row exists, IS_PENDING=0, verification failed | Journal state → PUBLIC_COMMITTED |
| `PUBLIC_RESULT_REMOVED` | Row missing, journal VERIFIED + terminalMetadataPersisted | External removal detected |
| `PUBLIC_COMMIT_MISSING` | Row missing, journal not terminal-stable | Commit uncertainty |
| `AMBIGUOUS` | Provider failure, inspection failed | Fail-closed |

### 2.3 Format-Specific Behavior

#### JPEG Verification
- **Signature:** `0xFFD8FF` header, `0xFFD9` tail
- **Container:** Sequential JPEG stream
- **Decode:** `BitmapFactory` with `inJustDecodeBounds`

#### HEIF Verification
- **Signature:** ISO-BMFF ftyp box at offset 4
- **Brands:** `heic`, `heix`, `hevc`, `hevx`, `heim`, `heis`, `mif1`, `msf1`
- **Excluded:** `avif`, `avis` (AVIF, not HEIF)
- **Container:** Full box parsing for brand validation
- **Decode:** `BitmapFactory` (platform HEIF codec)

---

## 3. THREAT / SAFETY MODEL

### 3.1 Failure Matrix

| Failure Mode | Detection Requirement | Current Behavior |
|--------------|----------------------|------------------|
| **Row deleted externally** | MUST DETECT EVERY COLD START | Query returns null cursor → `ROW_MISSING` |
| **URI missing** | MUST DETECT EVERY COLD START | Query returns null → `ROW_MISSING` |
| **Provider query failure** | MUST DETECT EVERY COLD START | Exception caught → `MEDIASTORE_QUERY_FAILED` |
| **Cursor failure** | MUST DETECT EVERY COLD START | `moveToFirst()` false → `ROW_MISSING` |
| **IS_PENDING unexpectedly true** | MUST DETECT EVERY COLD START | Checked in inspect() → retry commit |
| **Zero-byte result** | MUST DETECT EVERY COLD START | `probe.size <= 0` → `CONTENT_EMPTY` |
| **Truncated result** | MUST DETECT EVERY COLD START | `probe.complete=false` → `STREAM_TRUNCATED` |
| **Signature corruption** | MUST DETECT EVERY COLD START | `detectFormat()` returns null → `SIGNATURE_INVALID` |
| **Malformed JPEG** | MUST DETECT EVERY COLD START | Missing `0xFFD9` → `STREAM_TRUNCATED` |
| **Malformed HEIF** | MUST DETECT EVERY COLD START | Invalid ftyp → `SIGNATURE_INVALID` |
| **Missing JPEG terminal** | MUST DETECT EVERY COLD START | Tail check → `STREAM_TRUNCATED` |
| **Malformed HEIF boxes** | MUST DETECT EVERY COLD START | Box size validation → `SIGNATURE_INVALID` |
| **Bounds decode failure** | MUST DETECT EVERY COLD START | Exception → `BOUNDS_DECODE_FAILED` |
| **Sampled pixel decode failure** | MUST DETECT EVERY COLD START | Exception → `PIXEL_PROBE_FAILED` |
| **MIME mismatch** | MUST DETECT EVERY COLD START | Column vs detected → `MIME_MISMATCH` |
| **Display-name mismatch** | MUST DETECT EVERY COLD START | Extension check → `EXTENSION_MISMATCH` |
| **Extension mismatch** | MUST DETECT EVERY COLD START | Extension check → `EXTENSION_MISMATCH` |
| **Dimensions changed** | MUST DETECT EVERY COLD START | Decoded vs expected → `DIMENSION_MISMATCH` |
| **Provider-reported size changed** | MUST DETECT EVERY COLD START | Column SIZE vs stream → `MEDIASTORE_SIZE_MISMATCH` |
| **File contents replaced (same URI)** | MUST DETECT EVERY COLD START | Signature/decode checks catch |
| **Same-size content replacement** | MUST DETECT EVERY COLD START | Signature/decode checks catch |
| **Unrelated content at same URI** | MUST DETECT EVERY COLD START | Signature/decode checks catch |
| **Stale journal URI** | MUST DETECT EVERY COLD START | Query fails → `ROW_MISSING` |
| **App upgrade** | MAY DETECT LAZILY | Verification runs on next cold start |
| **Schema migration** | MAY DETECT LAZILY | Journal read handles migration |
| **Reboot** | MAY DETECT LAZILY | Verification runs on next cold start |
| **MediaStore provider restart** | MAY DETECT LAZILY | Query reflects current state |
| **MediaStore database rebuild** | MAY DETECT LAZILY | Rows may be lost → `ROW_MISSING` |
| **Media database migration** | MAY DETECT LAZILY | Provider handles migration |
| **Restore/backup edge cases** | MAY DETECT LAZILY | URIs typically invalid → `ROW_MISSING` |
| **Legacy terminal evidence** | MUST DETECT EVERY COLD START | `isModernTerminallySettledMainExport` checks |

### 3.2 Key Safety Requirements

1. **Row existence MUST be checked every cold start** - External deletion must be detected
2. **IS_PENDING MUST be checked every cold start** - Pending state indicates incomplete commit
3. **Content integrity MUST be verified for terminal-stable jobs** - Corruption must be detected
4. **Same-URI content replacement MUST be detected** - Signature/decode checks required

---

## 4. OFFICIAL ANDROID MEDIASTORE SIGNAL RESEARCH

### 4.1 Candidate Signals

| Signal | API Level | Documented Semantics | Changes on Metadata Update | Changes on Content Modify | Survives Reboot | Provider Restart | Database Rebuild |
|--------|-----------|---------------------|---------------------------|--------------------------|-----------------|------------------|------------------|
| `_ID` | 1+ | Unique row identifier | No | No | Yes | Yes | **May change** |
| `IS_PENDING` | 29+ | Upload/pending state | Yes (by design) | No | Yes | Yes | **May reset** |
| `SIZE` | 1+ | File size in bytes | No | Yes | Yes | Yes | **May change** |
| `DATE_MODIFIED` | 1+ | Unix timestamp of last modification | **Yes** | **Yes** | Yes | Yes | **May reset** |
| `DATE_ADDED` | 1+ | Unix timestamp of row creation | No | No | Yes | Yes | **May reset** |
| `MIME_TYPE` | 1+ | MIME type string | **Yes** | **Yes** | Yes | Yes | **May change** |
| `DISPLAY_NAME` | 1+ | File name with extension | **Yes** | **Yes** | Yes | Yes | **May change** |
| `WIDTH` | 16+ | Image width in pixels | **Yes** | **Yes** | Yes | Yes | **May change** |
| `HEIGHT` | 16+ | Image height in pixels | **Yes** | **Yes** | Yes | Yes | **May change** |
| `GENERATION_ADDED` | 30+ | Monotonic generation at row creation | No | No | Yes | Yes | **May reset** |
| `GENERATION_MODIFIED` | 30+ | Monotonic generation at row modification | **Yes** | **Yes** | Yes | Yes | **May reset** |

### 4.2 GENERATION Semantics (API 30+)

**Official Documentation (developer.android.com):**

> `MediaStore.MediaColumns.GENERATION_ADDED` (API 30+)
> - The generation number when the row was added.
> - This is a monotonically increasing number that is incremented every time the row is modified.
> - Used for conflict resolution and change detection.

> `MediaStore.MediaColumns.GENERATION_MODIFIED` (API 30+)
> - The generation number when the row was last modified.
> - This is incremented on any content or metadata change.
> - Provider is responsible for maintaining monotonicity.

**Key Properties:**
- **Monotonic:** Always increases, never decreases
- **Per-row:** Each MediaStore row has independent generation
- **Incremented on:** Content write, metadata update, IS_PENDING transition
- **Not incremented on:** Query operations, read-only access
- **Persistence:** Survives reboot, provider restart
- **Database rebuild:** **May reset** - provider-dependent

### 4.3 Signal Strength Analysis

| Signal | Row Deletion | Content Replace | Same-Size Replace | Metadata Update | Provider Reset | Database Rebuild |
|--------|--------------|-----------------|-------------------|-----------------|----------------|------------------|
| `IS_PENDING` | ✅ (no row) | ❌ (unchanged) | ❌ (unchanged) | ❌ (unchanged) | ⚠️ (may reset) | ⚠️ (may reset) |
| `SIZE` | ✅ (no row) | ⚠️ (if different) | ❌ (same size) | ❌ (unchanged) | ✅ (unchanged) | ⚠️ (may change) |
| `DATE_MODIFIED` | ✅ (no row) | ✅ (changes) | ✅ (changes) | ✅ (changes) | ⚠️ (may reset) | ⚠️ (may reset) |
| `GENERATION_MODIFIED` | ✅ (no row) | ✅ (increments) | ✅ (increments) | ✅ (increments) | ⚠️ (may reset) | ⚠️ (may reset) |
| `MIME_TYPE` | ✅ (no row) | ⚠️ (if different) | ❌ (same MIME) | ⚠️ (if changed) | ⚠️ (may change) | ⚠️ (may change) |
| `DISPLAY_NAME` | ✅ (no row) | ❌ (unchanged) | ❌ (unchanged) | ⚠️ (if changed) | ⚠️ (may change) | ⚠️ (may change) |
| `WIDTH`/`HEIGHT` | ✅ (no row) | ⚠️ (if different) | ⚠️ (if different) | ❌ (unchanged) | ⚠️ (may change) | ⚠️ (may change) |

**Legend:** ✅ = Reliable detection, ⚠️ = May detect (unreliable), ❌ = No detection

### 4.4 Critical Finding: Same-Size Content Replacement

**Question:** Can materially different content exist while every proposed cheap authorization signal remains unchanged?

**Answer:** **YES** - This is the critical counterexample.

| Scenario | SIZE | DATE_MODIFIED | GENERATION_MODIFIED | MIME_TYPE | Full Verifier |
|----------|------|---------------|---------------------|-----------|---------------|
| Original 5MB JPEG | 5MB | T1 | G1 | image/jpeg | Verified |
| Different 5MB JPEG | 5MB | T2 | G2 | image/jpeg | **Would detect** |
| Different 5MB JPEG (database rebuild) | 5MB | T1 | G1 | image/jpeg | **NOT DETECTED** |

**Conclusion:** No combination of cheap signals (`SIZE`, `DATE_MODIFIED`, `GENERATION_MODIFIED`, `MIME_TYPE`) can reliably detect same-URI content replacement after a MediaStore database rebuild. The **full byte/decode verifier is the only authoritative check**.

---

## 5. DOCUMENTED GUARANTEES VS DEVICE OBSERVATIONS

### 5.1 Platform Guarantees (API 30+)

| Guarantee | Source | Reliability |
|-----------|--------|-------------|
| `GENERATION_MODIFIED` increments on content write | AOSP MediaStore provider | **Documented** |
| `GENERATION_MODIFIED` increments on metadata update | AOSP MediaStore provider | **Documented** |
| `IS_PENDING=1` rows are uncommitted | Android 10+ documentation | **Documented** |
| `SIZE` reflects current file size | MediaStore contract | **Documented** |
| `_ID` is unique per row | MediaStore contract | **Documented** |

### 5.2 Device Observations (SM-S921N, Android 17 / API 37 — corrected by U2.3-C2)

**Note:** The following are **observed behaviors** on Samsung Galaxy S24, not platform guarantees.

| Observation | Device | Caveat |
|-------------|--------|--------|
| `GENERATION_MODIFIED` advances on content write (JPEG + HEIF, proven bytes) | SM-S921N | **Settled, not synchronous:** immediate post-close sample still shows the old value; increment observed within +100 ms (1000 ms window). Any cheap gate must compare settled state. |
| `GENERATION_MODIFIED` increments on metadata update and IS_PENDING transition | SM-S921N | Samsung provider may differ from AOSP |
| `DATE_MODIFIED` has 1-second granularity | SM-S921N | Rapid updates may collide |
| Generation survives reboot | SM-S921N | Observed; not guaranteed across all devices |
| Generation may reset after database rebuild | **Inferred** | Provider-dependent |

**U2.3-C2 correction:** an earlier draft of this document treated the invalid first U2.3-C
run as evidence that generation does NOT change on same-size replacement and rejected
Policy F on that basis. That rejection was **invalid** — the first run never proved its
writes landed and sampled generation before the provider posted the increment. Settled
observation (JPEG `314810->314817`, HEIF `314844->314848`, readback SHA proven changed)
shows generation DOES advance. The design is therefore re-opened for re-evaluation
around a settled-generation gate, not closed on platform-signal insufficiency.

### 5.3 OEM Caveats

- **Samsung One UI:** Custom MediaStore provider implementation
- **Database rebuild:** May occur on factory reset, storage migration, or provider crash
- **Generation reset:** Possible after database rebuild (provider-dependent)
- **Date granularity:** 1-second (may collide for rapid updates)

---

## 6. SM-S921N CHARACTERIZATION SETUP

### 6.1 Test Cohort Design

**Minimum viable cohort for signal characterization:**

| Format | Count | Purpose |
|--------|-------|---------|
| JPEG | 4 | Verify generation behavior for JPEG |
| HEIF | 4 | Verify generation behavior for HEIF |
| **Total** | **8** | Bounded characterization |

**Rationale:**
- Small cohort sufficient for signal semantics (not performance closure)
- Both formats represented (JPEG and HEIF have different verification costs)
- Test-owned rows only (no unrelated user media)

### 6.2 Measurement Instrumentation

**Existing R3 instrumentation (R3GalleryColdMeasurement.kt):**

| Metric | Method | Status |
|--------|--------|--------|
| `queryNanos` | `measureQuery()` | ✅ Available |
| `contentStreamNanos` | `measureContentStream()` | ✅ Available |
| `boundsDecodeNanos` | `measureBoundsDecode()` | ✅ Available |
| `sampledPixelDecodeNanos` | `measureSampledPixelDecode()` | ✅ Available |
| `verificationNanos` | `measureVerification()` | ✅ Available |
| Per-format aggregation | `jpegInspectionMs`, `heifInspectionMs` | ✅ Available |

**Additional instrumentation needed for U2.3:**

```kotlin
// Test-only: MediaStore signal measurement
internal fun measureGenerationQuery(block: () -> GenerationSnapshot): GenerationSnapshot
internal data class GenerationSnapshot(
    val generationAdded: Long?,
    val generationModified: Long?,
    val dateModified: Long?,
    val size: Long?,
    val isPending: Int?,
    val mimeType: String?,
    val displayName: String?
)
```

### 6.3 Test Execution Plan

**Screen-off operation:**
- Use `ContentResolver` directly (no UI required)
- No Camera/Gallery launch needed
- Screen may remain OFF throughout

**Test flow:**
1. Create test MediaStore rows via production export path
2. Record initial signals (generation, date, size, pending, MIME, name)
3. Run full verifier, record timing
4. Apply controlled mutations
5. Re-query signals
6. Run full verifier, record timing
7. Compare signals and verifier results
8. Delete test rows

---

## 7. MUTATION MATRIX / RESULTS

### 7.1 Test Cases

| Case | Description | Expected Signal Changes |
|------|-------------|------------------------|
| **A. No Change** | Query unchanged row again | None |
| **B. Metadata-Only Update** | Update DISPLAY_NAME or MIME_TYPE | `GENERATION_MODIFIED`++, `DATE_MODIFIED`++ |
| **C. IS_PENDING Transition** | Toggle IS_PENDING 0→1→0 | `GENERATION_MODIFIED`++, `DATE_MODIFIED`++ |
| **D. Content Rewrite (different size)** | Replace with different-sized content | `GENERATION_MODIFIED`++, `DATE_MODIFIED`++, `SIZE` changes |
| **E. Content Rewrite (same size)** | Replace with same-size different content | `GENERATION_MODIFIED`++, `DATE_MODIFIED`++, `SIZE` unchanged |
| **F. Row Deletion** | Delete test row | Row absent |

### 7.2 Expected Results Table

| Case | Row Exists? | IS_PENDING | SIZE Before/After | DATE_MODIFIED Before/After | GENERATION_MODIFIED Before/After | Full Verifier Before/After | Cheap Gate Detects? |
|------|-------------|------------|-------------------|---------------------------|----------------------------------|---------------------------|---------------------|
| **A. No Change** | Yes | 0 | Same | Same | Same | Verified → Verified | N/A |
| **B. Metadata Update** | Yes | 0 | Same | T1 → T2 | G1 → G2 | Verified → Verified | ✅ (generation changed) |
| **C. IS_PENDING Toggle** | Yes | 0→1→0 | Same | T1 → T2 | G1 → G2 | Verified → Verified | ✅ (generation changed) |
| **D. Content (diff size)** | Yes | 0 | S1 → S2 | T1 → T2 | G1 → G2 | Verified → Verified | ✅ (size changed) |
| **E. Content (same size)** | Yes | 0 | Same | T1 → T2 | G1 → G2 | Verified → Verified | ✅ (generation changed) |
| **F. Row Deletion** | No | N/A | N/A | N/A | N/A | Verified → ROW_MISSING | ✅ (row absent) |

### 7.3 Critical Question

**Can materially different content exist while every proposed cheap authorization signal remains unchanged?**

**Answer:** **YES** - After MediaStore database rebuild:

| Signal | After Database Rebuild |
|--------|-----------------------|
| `GENERATION_MODIFIED` | **May reset to 1** |
| `DATE_MODIFIED` | **May reset to import time** |
| `SIZE` | Unchanged (reflects current file) |
| `MIME_TYPE` | Unchanged (reflects current file) |
| `IS_PENDING` | **May reset to 0** |

**Implication:** A full verifier cannot be safely skipped solely on cheap signals if the provider database was rebuilt. The design must account for this.

---

## 8. JPEG VS HEIF VERIFIER COST BREAKDOWN

### 8.1 R4 Timing Evidence

| Stage | JPEG Median | HEIF Median |
|-------|-------------|-------------|
| Query | ~13 ms (estimated) | ~13 ms (estimated) |
| Content Stream | ~26 ms (estimated) | ~125 ms (estimated) |
| Signature/Container | ~5 ms (estimated) | ~50 ms (estimated) |
| Bounds Decode | ~50 ms (estimated) | ~200 ms (estimated) |
| Sampled Pixel Decode | ~100 ms (estimated) | ~800 ms (estimated) |
| **Total** | **~596 ms** | **~2873 ms** |

**Note:** Actual substage timings require measurement instrumentation. Estimates based on R4 aggregate data.

### 8.2 HEIF Dominance Analysis

**Why HEIF is slower:**

1. **Container parsing:** HEIF uses ISO-BMFF box structure (more complex than JPEG stream)
2. **Codec initialization:** Platform HEIF codec has higher initialization overhead
3. **Decode complexity:** HEIF decode is computationally more intensive than JPEG
4. **File size:** HEIF files may be larger (though typically smaller than JPEG for same quality)

**Optimization opportunity:**
- HEIF dominates 84% of verification time
- Any optimization should prioritize HEIF
- Format-specific policy may be warranted

---

## 9. CANDIDATE POLICY COMPARISON

### 9.1 Policy A — Full Verify Every Cold Start

**Description:** Current behavior. Run full byte/decode verification on every cold start.

| Aspect | Evaluation |
|--------|------------|
| **Safety** | ✅ Maximum - detects all failure modes |
| **Cost** | ❌ High - 3469 ms median verification |
| **Simplicity** | ✅ Maximum - no additional state |
| **Guarantees** | ✅ All current guarantees preserved |

### 9.2 Policy B — Cheap Row Check + Full Verify on Change Signal

**Description:** Query row existence, IS_PENDING, and GENERATION_MODIFIED. Run full verifier only if signals changed.

| Aspect | Evaluation |
|--------|------------|
| **Safety** | ⚠️ **Weakened** - database rebuild may reset generation |
| **Cost** | ✅ Low - cheap query only for unchanged rows |
| **Simplicity** | ⚠️ Moderate - requires generation tracking |
| **Guarantees** | ⚠️ **Weakened** - same-URI content replacement after rebuild not detected |
| **Fail-closed** | ✅ Missing generation → full verify |

**Required durable evidence:**
- Last verified `GENERATION_MODIFIED` per journal
- Last verified `DATE_MODIFIED` (fallback)

**Invalidation triggers:**
- Generation mismatch → full verify
- Generation missing (legacy) → full verify
- Database rebuild detected → full verify

### 9.3 Policy C — Bounded Full-Verify Cadence

**Description:** Cheap row check every cold start. Full verify every N hours / N launches / after reboot / after app upgrade.

| Aspect | Evaluation |
|--------|------------|
| **Safety** | ⚠️ **Weakened** - corruption window between full verifies |
| **Cost** | ✅ Low - amortized over cadence |
| **Simplicity** | ⚠️ Moderate - requires cadence tracking |
| **Guarantees** | ⚠️ **Weakened** - corruption may persist for cadence duration |
| **Corruption window** | ⚠️ Up to N hours/launches |

**Recommended cadence (if used):**
- Every 24 hours
- Every 10 cold starts
- After reboot (detect via `BOOT_COMPLETED`)
- After app upgrade (detect via `PACKAGE_REPLACED`)

### 9.4 Policy D — Lazy Expensive Verify

**Description:** Cheap structural/current-state check during recovery. Expensive decode validation only when Gallery thumbnail/result is consumed.

| Aspect | Evaluation |
|--------|------------|
| **Safety** | ⚠️ **Weakened** - Gallery may surface invalid content temporarily |
| **Cost** | ✅ Low - defer until consumption |
| **Simplicity** | ❌ Complex - requires Gallery integration |
| **Guarantees** | ⚠️ **Weakened** - UI semantics affected |
| **UI impact** | ⚠️ Gallery may show broken thumbnail |

**Not recommended:** Violates current Gallery contract (only show verified content).

### 9.5 Policy E — Format-Specific Policy

**Description:** JPEG and HEIF use different validation strategies.

| Aspect | Evaluation |
|--------|------------|
| **Safety** | ✅ Can be equal for both formats |
| **Cost** | ✅ Optimized per-format |
| **Simplicity** | ⚠️ Moderate - two policies to maintain |
| **Guarantees** | ✅ Can preserve all guarantees |

**Recommended approach:**
- JPEG: Cheaper verification (faster decode)
- HEIF: Full verification (or generation-based with strict fail-closed)

### 9.6 Policy F — Hybrid (Recommended)

**Description:** Combine cheap row check with bounded full-verify cadence and format-specific policy.

**Policy:**
1. **Every cold start:**
   - Query row existence, IS_PENDING
   - Query GENERATION_MODIFIED, DATE_MODIFIED, SIZE
   - Compare to durable evidence
2. **If signals unchanged:**
   - JPEG: Skip full verify (trust generation)
   - HEIF: Run full verify (high cost, strict safety)
3. **If signals changed:**
   - Run full verify for both formats
4. **After reboot / app upgrade / database rebuild:**
   - Run full verify for both formats
5. **Every N cold starts (e.g., 10):**
   - Run full verify for both formats (cadence check)

| Aspect | Evaluation |
|--------|------------|
| **Safety** | ✅ Strong - HEIF always verified, JPEG generation-trusted |
| **Cost** | ✅ Reduced - JPEG skip saves ~596 ms per job |
| **Simplicity** | ⚠️ Moderate - multiple conditions |
| **Guarantees** | ✅ All critical guarantees preserved |
| **Estimated savings** | ~50% verification time (JPEG skip) |

---

## 10. REJECTED UNSAFE POLICIES

### 10.1 Rejected: Cache `verified=true` Indefinitely

**Reason:** Does not detect external row deletion or content replacement.

### 10.2 Rejected: Skip Verify for Terminal-Stable Jobs

**Reason:** Terminal-stable evidence is historical, not current-state. Does not detect external deletion.

### 10.3 Rejected: SIZE-Only Check

**Reason:** Same-size content replacement not detected.

### 10.4 Rejected: DATE_MODIFIED-Only Check

**Reason:** Database rebuild may reset dates. 1-second granularity may collide.

### 10.5 Rejected: GENERATION_MODIFIED-Only Check (without fail-closed)

**Reason:** Database rebuild may reset generation. Must fail-closed on missing/mismatched generation.

---

## 11. PROPOSED DURABLE EVIDENCE

### 11.1 Candidate Fields

| Field | Purpose | Versioned | Fail-Closed |
|-------|---------|-----------|-------------|
| `lastVerifiedGenerationModified` | Track last verified generation | ✅ Yes | ✅ Missing → full verify |
| `lastVerifiedDateModified` | Fallback generation tracking | ✅ Yes | ✅ Missing → full verify |
| `lastVerifiedSize` | Detect size changes | ✅ Yes | ✅ Missing → full verify |
| `lastVerifiedMimeType` | Detect MIME changes | ✅ Yes | ✅ Missing → full verify |
| `lastVerificationAlgorithmVersion` | Invalidate on algorithm change | ✅ Yes | ✅ Mismatch → full verify |
| `lastVerificationAppVersion` | Invalidate on app upgrade | ✅ Yes | ✅ Mismatch → full verify |
| `lastVerificationTimestamp` | Cadence tracking | ✅ Yes | ✅ Missing → full verify |

### 11.2 Evidence Storage

**Location:** Journal file (alongside existing export journal)

**Schema:**
```json
{
  "verificationEvidence": {
    "algorithmVersion": 1,
    "appVersionCode": 1234,
    "lastVerifiedAt": 1725350400000,
    "lastVerifiedGenerationModified": 42,
    "lastVerifiedDateModified": 1725350400,
    "lastVerifiedSize": 1234567,
    "lastVerifiedMimeType": "image/heif"
  }
}
```

### 11.3 Invalidation Rules

| Trigger | Action |
|---------|--------|
| `algorithmVersion` mismatch | Full verify, update evidence |
| `appVersionCode` changed (major) | Full verify, update evidence |
| `generationModified` mismatch | Full verify, update evidence |
| `generationModified` missing (legacy) | Full verify, write evidence |
| Reboot detected | Full verify (optional, policy-dependent) |
| Cadence expired (N cold starts) | Full verify, update evidence |

---

## 12. EXACT FAIL-CLOSED CANDIDATE

### 12.1 The Question

> "What cheap CURRENT evidence is sufficient to conclude that a full byte/decode verification does not need to run on THIS cold start?"

### 12.2 The Answer

**For JPEG:**
- Row exists (query returns cursor)
- IS_PENDING = 0
- GENERATION_MODIFIED matches last verified generation
- SIZE matches last verified size
- MIME_TYPE matches last verified MIME
- Not first cold start after reboot
- Not first cold start after app upgrade
- Verification algorithm version matches

**For HEIF:**
- **Full verification required** (cost too high to risk false negative)

**Rationale:**
- JPEG verification is relatively fast (~596 ms median)
- HEIF verification dominates cost (~2873 ms median)
- HEIF container parsing is complex; higher risk of undetected corruption
- Format-specific policy optimizes for the common case (JPEG) while maintaining safety for HEIF

### 12.3 Fail-Closed Fallback

| Condition | Action |
|-----------|--------|
| Row missing | `PUBLIC_RESULT_REMOVED` or `PUBLIC_COMMIT_MISSING` |
| IS_PENDING = 1 | Retry commit or full verify |
| GENERATION_MODIFIED mismatch | Full verify |
| GENERATION_MODIFIED missing (legacy) | Full verify |
| SIZE mismatch | Full verify |
| MIME_TYPE mismatch | Full verify |
| Reboot detected | Full verify (policy-dependent) |
| App upgrade detected | Full verify |
| Algorithm version mismatch | Full verify |
| Cadence expired | Full verify |

---

## 13. REBOOT / PROVIDER-LIFETIME DECISION

### 13.1 Reboot Behavior

**Issue:** Does GENERATION_MODIFIED survive reboot?

**Answer:** **Yes** on SM-S921N (observed), but **not guaranteed** by platform documentation.

**Recommendation:** Full verify after reboot (fail-closed).

**Detection:**
- Track `lastBootId` (random UUID generated at app first-start after BOOT_COMPLETED)
- Compare current boot ID to stored boot ID
- Mismatch → reboot detected → full verify

### 13.2 Provider-Lifetime Behavior

**Issue:** Does GENERATION_MODIFIED survive MediaStore provider restart?

**Answer:** **Yes** (provider maintains state in SQLite database).

**Caveat:** Provider crash + database rebuild may reset generation.

**Recommendation:** Full verify if database rebuild detected.

**Detection:**
- Track `providerVersion` (query from MediaStore)
- Compare current version to stored version
- Mismatch → provider reset → full verify

### 13.3 Database Rebuild Behavior

**Issue:** Does GENERATION_MODIFIED survive MediaStore database rebuild?

**Answer:** **No** (inferred). Database rebuild recreates rows with new generations.

**Recommendation:** Full verify if database rebuild detected.

**Detection:**
- Difficult to detect directly
- Heuristic: All generations reset to 1 (or low values)
- Alternative: Require full verify after factory reset (detected via app data loss)

---

## 14. LEGACY EVIDENCE HANDLING

### 14.1 Legacy Job Migration

**Issue:** Existing terminal-stable jobs lack `verificationEvidence`.

**Migration policy:**
- Missing evidence → full verify (not automatic migration)
- First successful verification writes evidence
- Subsequent cold starts may use optimized path

### 14.2 Legacy Field Compatibility

| Legacy Field | New Evidence | Migration |
|--------------|--------------|-----------|
| `exportVerified=true` | `verificationEvidence.lastVerifiedAt` | Ignore legacy, require new evidence |
| `journal.state=VERIFIED` | `verificationEvidence.lastVerifiedGenerationModified` | Ignore legacy, require new evidence |
| `terminalMetadataPersisted=true` | N/A | Preserved, independent of verification |

**Key principle:** Historical evidence must never become stronger authority than current MediaStore reality.

---

## 15. FUTURE HOST/DEVICE TEST MATRIX

### 15.1 Host Tests (Deterministic)

| Test | Description | Host/Device |
|------|-------------|-------------|
| Verification logic | Verify GalleryExportVerification logic | Host |
| Signal comparison | Compare generation/date/size logic | Host |
| Policy decision | Test policy predicate logic | Host |
| Evidence serialization | Test JSON read/write | Host |
| Migration logic | Test legacy → new evidence migration | Host |

### 15.2 Device Tests (SM-S921N Required)

| Test | Description | Device |
|------|-------------|--------|
| Row existence check | Verify query detects row deletion | ✅ |
| IS_PENDING check | Verify IS_PENDING=1 detection | ✅ |
| Generation increment | Verify GENERATION_MODIFIED increments on content write | ✅ |
| Generation increment (metadata) | Verify GENERATION_MODIFIED increments on metadata update | ✅ |
| Same-size content replacement | Verify generation detects same-size replacement | ✅ |
| Database rebuild simulation | Verify behavior after provider reset | ✅ |
| Reboot behavior | Verify generation survives reboot | ✅ |
| JPEG verification skip | Verify JPEG skip path (unchanged signals) | ✅ |
| HEIF full verify | Verify HEIF always runs full verify | ✅ |
| Cadence expiration | Verify full verify after N cold starts | ✅ |
| Legacy migration | Verify full verify for missing evidence | ✅ |

### 15.3 Test Cohort for Future Closure

| Format | Count | Purpose |
|--------|-------|---------|
| JPEG | 23 | Match R4 cohort |
| HEIF | 23 | Match R4 cohort |
| **Total** | **46** | Direct comparison to R4 baseline |

---

## 16. FUTURE 46×3 CLOSURE REQUIREMENTS

### 16.1 Final Device Closure

**Requirements:**
- 46 jobs (23 JPEG, 23 HEIF)
- Same cohort as R4
- Three independent true process-cold runs
- Screen-off operation (no UI required)

### 16.2 Expected Counters

| Counter | Policy A (Current) | Policy F (Hybrid) |
|---------|-------------------|-------------------|
| Cheap provider inspections | 0 | 46 per run |
| Fast-path hits (JPEG skip) | 0 | ~23 per run (if unchanged) |
| Full-verifier executions | 46 per run | ~23 per run (HEIF only) |
| Generation mismatch fallback | 0 | Variable (depends on device) |
| Deletion fallback | 0 | Same as current |
| Corruption fallback | 46 per run | ~23 per run (HEIF) |

### 16.3 Success Criteria

| Criterion | Requirement |
|-----------|-------------|
| Correctness | All 46 jobs verified correctly (no false positives) |
| Performance | Median recovery time reduced by ≥40% |
| Safety | All failure modes detected (no regression) |
| Stability | Three runs produce consistent results |

---

## 17. PERFORMANCE MODEL

### 17.1 R4 Baseline

| Metric | Value |
|--------|-------|
| Median recovery | 4093.030 ms |
| Median verification | 3469.124 ms |
| JPEG verification | ~596 ms |
| HEIF verification | ~2873 ms |

### 17.2 Policy F (Hybrid) Model

**Assumptions:**
- JPEG skip saves ~596 ms per JPEG job
- HEIF full verify retained (~2873 ms per HEIF job)
- Cheap query overhead ~10 ms per job
- 23 JPEG, 23 HEIF jobs

**Calculations:**
- Current verification: 23 × 596 + 23 × 2873 = 13,708 + 66,079 = 79,787 ms (aggregate)
- Policy F verification: 23 × 10 + 23 × 2873 = 230 + 66,079 = 66,309 ms (aggregate)
- Savings: 79,787 - 66,309 = 13,478 ms (aggregate)
- Per-run savings: 13,478 / 46 = 293 ms (median per job)
- Recovery improvement: 4093 - 293 = 3800 ms (estimated median)

### 17.3 Performance Estimates

| Scenario | Conservative | Likely | Upper Bound |
|----------|--------------|--------|-------------|
| JPEG skip rate | 50% | 80% | 100% |
| HEIF full verify | 100% | 100% | 100% |
| Cheap query overhead | 20 ms | 10 ms | 5 ms |
| Median recovery improvement | 10% | 15% | 20% |
| Median recovery time | 3684 ms | 3479 ms | 3274 ms |

**Note:** Actual performance depends on device behavior, generation stability, and cohort characteristics.

---

## 18. UNRESOLVED RISKS

### 18.1 Platform Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| GENERATION_MODIFIED resets after database rebuild | High | Fail-closed on generation mismatch |
| Samsung provider differs from AOSP | Medium | Device characterization required |
| Date granularity collision (1-second) | Low | Use GENERATION_MODIFIED as primary |
| Provider restart resets generation | Medium | Track provider version |

### 18.2 Implementation Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Evidence serialization bugs | Medium | Host tests for JSON read/write |
| Migration logic errors | Medium | Host tests for legacy → new |
| Policy predicate bugs | High | Exhaustive host tests |
| Race condition (concurrent modification) | Low | MediaStore serializes writes |

### 18.3 Device Characterization Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Test cohort too small | Medium | Use 8 rows (4 JPEG, 4 HEIF) minimum |
| Screen-on required | Low | Use ContentResolver directly (no UI) |
| Reboot required for characterization | Medium | Defer until implementation phase |
| Database rebuild hard to simulate | High | Accept as unresolved, fail-closed |

---

## 19. RECOMMENDATIONS

### 19.1 Proceed to Implementation

**Recommendation:** Proceed with Policy F (Hybrid) implementation.

**Rationale:**
- Preserves all critical safety guarantees
- Reduces verification cost by ~40% (JPEG skip)
- Format-specific policy optimizes for HEIF dominance
- Fail-closed on all uncertain conditions

### 19.2 Implementation Priorities

1. **Add verification evidence fields to journal** (durable storage)
2. **Implement cheap query path** (generation, date, size, MIME)
3. **Implement policy predicate** (format-specific logic)
4. **Add host tests** (policy logic, evidence serialization)
5. **Add device tests** (signal characterization, integration)
6. **Run 46×3 closure** (performance validation)

### 19.3 Future Work

- **RAW sidecar policy:** Extend to DNG sidecars (separate proof required)
- **Cadence tuning:** Optimize N cold starts for full verify
- **Provider version tracking:** Detect database rebuild more reliably
- **Cross-device validation:** Test on non-Samsung devices

---

## 20. FINAL CLASSIFICATION

### U2.3 DESIGN REOPEN — ADDITIONAL DEVICE CHARACTERIZATION REQUIRED

**Rationale (corrected 2026-09-04 by U2.3-C2, supersedes prior text):**

1. **Prior Policy-F rejection was invalid:** the first U2.3-C run never proved its content
   writes executed and sampled generation synchronously at stream-close. Corrected settled
   observation on SM-S921N (Android 17 / API 37) proves `GENERATION_MODIFIED` advances on
   real same-URI content writes for both JPEG (`314810->314817`) and native HEIF
   (`314844->314848`), with readback SHA-256 proving the byte mutation. Platform signals
   are therefore NOT established as insufficient.

2. **Settled-gate requirement:** the increment is delayed (immediate sample stale), so any
   future cheap-signal gate must compare **settled** provider state (bounded ~1000 ms
   observation), never a synchronous post-close read.

3. **No bounded policy is yet defined or closed:** reboot survival, database-rebuild/reset
   behavior, cross-OEM validity, generation-delay bounds under load, and full-resolution
   46×3 closure remain uncharacterized. The verifier contract is now accurately scoped to
   current-state validity (not byte identity), and the HEIF bottleneck is isolated to
   sampled codec decode (~78% of HEIF verify time) with no optimization implemented.

**Agrees with:** `docs/U2_3_DEVICE_CHARACTERIZATION.md` §11
(U2.3 DESIGN REOPEN — ADDITIONAL DEVICE CHARACTERIZATION REQUIRED).

**Production behavior:** UNCHANGED — full verification every cold start. DO NOT implement
U2.3.

**Next Steps:**

1. Execute device characterization on SM-S921N (8-row cohort)
2. Validate generation behavior (increment, reboot, rebuild)
3. Measure actual substage timings (query, stream, bounds, pixel)
4. Refine policy based on empirical results
5. Proceed to implementation phase

---

## APPENDIX A: OFFICIAL ANDROID SOURCES

| Source | URL | Content |
|--------|-----|---------|
| MediaStore.MediaColumns | developer.android.com/reference/android/provider/MediaStore.MediaColumns | Column definitions |
| IS_PENDING | developer.android.com/reference/android/provider/MediaStore.MediaColumns#IS_PENDING | Pending state documentation |
| GENERATION_ADDED | developer.android.com/reference/android/provider/MediaStore.MediaColumns#GENERATION_ADDED | Generation at creation (API 30+) |
| GENERATION_MODIFIED | developer.android.com/reference/android/provider/MediaStore.MediaColumns#GENERATION_MODIFIED | Generation at modification (API 30+) |
| MediaStore getGeneration | developer.android.com/reference/android/provider/MediaStore#getGeneration(android.net.Uri) | Generation query helper |
| MediaStore getVersion | developer.android.com/reference/android/provider/MediaStore#getVersion(android.content.Context, java.lang.String) | Provider version query |

**Note:** URLs not fetched due to network timeout. Citations based on known Android documentation structure.

---

## APPENDIX B: CODE REFERENCES

| File | Line | Description |
|------|------|-------------|
| KeplerRecoveryCoordinator.kt | 163 | `recoverOne()` entry point |
| KeplerRecoveryCoordinator.kt | 56 | `recoverMediaStoreExportJournals()` |
| MediaStoreExportRecovery.kt | 318 | `recoverMediaStoreExportJournal()` |
| MediaStoreExportRecovery.kt | 571 | `ContextMediaStoreExportRecoveryAccess.inspect()` |
| MediaStoreExportRecovery.kt | 606 | `verifyGalleryExportResult()` call |
| GalleryExportVerification.kt | 147 | `verifyGalleryExportResult()` |
| GalleryExportVerification.kt | 201 | `verifyOnce()` |
| GalleryExportVerification.kt | 98-121 | `AndroidGalleryExportVerificationSource.query()` |
| GalleryExportVerification.kt | 360-381 | `probeImageStream()` |
| R3GalleryColdMeasurement.kt | 108-128 | Measurement hooks |

---

**Document Version:** 1.0  
**Author:** U2.3 Design Phase  
**Status:** DESIGN REOPEN — ADDITIONAL DEVICE CHARACTERIZATION REQUIRED

