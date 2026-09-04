# U2.3-C2 CORRECTIVE DEVICE CHARACTERIZATION REPORT

**Repository:** futureisAJASU/ProjectNuke-CameraPlus
**Baseline:** 55e132d35dfcc4cc3136fda344429d0e978027d1
**Reference Device:** Samsung Galaxy S24 (SM-S921N, Android 17 / API 37)
**Package:** com.projectnuke.keplernightlab
**Date:** 2026-09-04
**Test:** U23MediaStoreCharacterizationTest.characterizeMediaStoreSignalsC2
**User:** 0 (owner, confirmed via `adb shell am get-current-user`)
**Screen:** OFF-capable / UI-independent (ContentResolver only; no Camera, Gallery, UiAutomator)

This report SUPERSEDES the first U2.3-C characterization. The first run is declared
INVALID: its content writes used nullable `openOutputStream()?.use`, never proved a byte
landed, and sampled provider generation only at stream-close time.

---

## 1. CORRECTED METHOD (what changed vs U2.3-C)

- Every mutation opens a **non-null** `ContentResolver.openFileDescriptor(uri, "rwt")` handle;
  null FAILS the test. Full payload write, flush, `fd.sync()`, close before any query/verify.
- Every mutation proves content **before and after** by reading the exact URI back:
  byte length, SHA-256, head/tail bytes. Same-size requires
  `beforeLength == afterLength AND beforeSHA != afterSHA AND readback == written payload`.
- Same-size payloads are **deterministic signature kills at fixed offsets preserving length**:
  JPEG SOI `FF D8 FF -> 00 00 00`; HEIF ftyp `66747970 -> 58585858` ("XXXX").
- Different-size payloads **assert `replacement.size != original.size` before writing** and
  `readback.size == replacement.size AND readback SHA == replacement SHA` after.
- Provider signals sampled on a settled schedule: **immediate, +100 ms, +500 ms, +1000 ms**.
  The FINAL settled sample is the result.
- Cohort created through the **production export path**
  `exportNightFusionBitmapToGallery(..., requestedFormat = JPEG/HEIF, ...)`; HEIF rows are
  native HEIF (`formatUsed == HEIF` asserted, `VERIFIED` before any mutation).
- Deletion requires `delete() == 1` PLUS bounded convergence (exact URI queried until absent).
- Substage timing (query / stream / bounds / pixel / total) measured separately for JPEG and
  HEIF, n=12 samples each, on valid unchanged production rows.

---

## 2. RAW DEVICE EVIDENCE (logcat tag U23C2, SM-S921N / Android 17 / API 37)

Provider at start: `volumeGeneration=314781 volumeVersion=a5d0ddddf6b4c05e`.

```
ROW-C2      fmt=JPEG uri=content://media/external/images/media/1000061376 size=2758 gen=314784 ver=Verified(fmt=JPEG)
ROW-C2      fmt=HEIF uri=content://media/external/images/media/1000061377 size=4515 gen=314795 ver=Verified(fmt=HEIF)
TIMING-C2   jpeg n=12 query=3.217ms stream=3.544ms bounds=4.025ms pixel=4.659ms total=15.311ms
TIMING-C2   heif n=12 query=6.519ms stream=6.673ms bounds=7.64ms pixel=107.889ms total=138.737ms
UNCHANGED-C2 fmt=JPEG gen=314784->314784 ver=Verified(fmt=JPEG)
METADATA-C2  fmt=JPEG name=u23c2-jpeg-3bedd53e-....jpg->u23c2-renamed-fdba43a4.jpg gen=314784->314799 ver=Verified(fmt=JPEG)
PENDING-C2   fmt=JPEG pending=0->1->0 gen=314799->314804->314810
SAMESIZE-C2  fmt=JPEG len=2758->2758 sha=70fe86aed75d->445812ab0251
             head=FFD8FFE000104A464946000101000001->000000E000104A464946000101000001
             gen=314810->[314810, 314817, 314817, 314817] ver=PermanentFailure(reason=SIGNATURE_INVALID)
DIFFSIZE-C2  fmt=JPEG len=2758->1439->1439 gen=314817->[314822, 314822, 314822, 314822] ver=Verified(fmt=JPEG)
DELETION-C2  fmt=JPEG convergedMs=3
UNCHANGED-C2 fmt=HEIF gen=314795->314795 ver=Verified(fmt=HEIF)
METADATA-C2  fmt=HEIF name=u23c2-heif-2f87ca52-....heif->u23c2-renamed-147e63b9.heif gen=314795->314829 ver=Verified(fmt=HEIF)
PENDING-C2   fmt=HEIF pending=0->1->0 gen=314829->314836->314844
SAMESIZE-C2  fmt=HEIF len=4515->4515 sha=76ee034ff0ab->8ca22a7ffb02
             head=00000018667479706865696300000000->00000018585858586865696300000000
             gen=314844->[314844, 314848, 314848, 314848] ver=PermanentFailure(reason=SIGNATURE_INVALID)
ROW-C2       fmt=HEIF (temp 64x64 donor) uri=content://media/external/images/media/1000061378 size=1713 gen=314857 ver=Verified(fmt=HEIF)
DIFFSIZE-C2  fmt=HEIF len=4515->1713->1713 gen=314848->[314865, 314865, 314865, 314865] ver=Verified(fmt=HEIF)
DELETION-C2  fmt=HEIF convergedMs=5
CLEANUP-C2   uri=.../1000061376 absent=true
CLEANUP-C2   uri=.../1000061377 absent=true
```

Full SHA-256 (from structured evidence JSON):

| Case | before SHA-256 | after SHA-256 |
|------|---------------|--------------|
| JPEG same-size (2758 B) | `70fe86aed75d…449dad6` | `445812ab0251…0082dfab` |
| JPEG diff-size (2758→1439 B) | `445812ab0251…0082dfab` | `bd6d255bb012…080b12ad346` |
| HEIF same-size (4515 B) | `76ee034ff0ab…7401142a8` | `8ca22a7ffb02…1532b980ed` |
| HEIF diff-size (4515→1713 B) | `8ca22a7ffb02…1532b980ed` | `8bbd58e1c2f5…c64359ba3160` |

Settled samples are `GENERATION_MODIFIED/SIZE/DATE_MODIFIED/IS_PENDING`; row `_ID`/URI
asserted stable across every mutation (test FAILS otherwise).

---

## 3. KEY CORRECTED FINDING — GENERATION **DOES** ADVANCE (WITH DELAY)

For **both** JPEG and HEIF, a proven same-size byte replacement advances the settled
`GENERATION_MODIFIED`:

- JPEG: `314810 -> 314817` (immediate sample still `314810`; advanced by +100 ms)
- HEIF: `314844 -> 314848` (immediate sample still `314844`; advanced by +100 ms)

Different-size replacements also advanced generation (`314817->314822`,
`314848->314865`) and updated `SIZE`/`DATE_MODIFIED`.

**Consequence:** the first U2.3-C conclusion ("generation unchanged after same-size
replacement") was a **measurement-order artifact**: it sampled generation synchronously at
stream-close, before the provider posted the increment. The §14 decision rule therefore
fires its second branch:

> generation changed once actual mutation was proven → the previous Policy-F rejection
> was invalid and the design must be re-evaluated.

`DATE_MODIFIED` moved with every content/metadata mutation (1-second granularity visible:
`...501 -> ...509 -> ...511`), and `SIZE` tracked real byte length exactly
(`2758->1439`, `4515->1713`). Metadata rename and IS_PENDING transitions also advanced
generation. Unchanged rows were perfectly stable.

**Scope guard (SM-S921N OBSERVATION, not a universal Android guarantee):** on this
reference device, a supported same-URI content write advances row `GENERATION_MODIFIED`
within a bounded delay (observed ≤100 ms; settled observation window 1000 ms). This says
nothing about other OEM providers, database rebuilds, or restores — any future policy
must still fail closed on missing/mismatched generation and re-verify after reboot,
app upgrade, and provider-version change.

---

## 4. SAFETY MATRIX (corrected — no contradiction)

| Scenario | Format | URI/_ID stable | SIZE | DATE_MODIFIED | GENERATION_MODIFIED (settled) | Full verifier | Cheap gate detects? |
|----------|--------|---------------|------|--------------|-------------------------------|--------------|---------------------|
| Unchanged | JPEG/HEIF | YES | Same | Same | Same | Verified→Verified | N/A |
| Metadata rename | JPEG/HEIF | YES | Same | Changed | Changed | Verified→Verified | YES (settled) |
| IS_PENDING 0→1→0 | JPEG/HEIF | YES | Same | Changed | Changed | Verified→Verified | YES (settled) |
| Same-size replacement (proven bytes) | JPEG | YES | 2758→2758 | Changed | 314810→314817 | Verified→SIGNATURE_INVALID | YES (settled) |
| Same-size replacement (proven bytes) | HEIF | YES | 4515→4515 | Changed | 314844→314848 | Verified→SIGNATURE_INVALID | YES (settled) |
| Different-size replacement (proven bytes) | JPEG | YES | 2758→1439 | Changed | 314817→314822 | Verified→Verified* | YES (size+gen) |
| Different-size replacement (proven bytes) | HEIF | YES | 4515→1713 | Changed | 314848→314865 | Verified→Verified* | YES (size+gen) |
| Deletion | JPEG/HEIF | row absent in 3–5 ms | N/A | N/A | N/A | →ROW_MISSING path | YES |

\* Different-size payloads were valid production-faithful images verified against
matching expectations (JPEG 64×64, HEIF 64×64); VERIFIED is the correct outcome there.
When the stale 128×128 expectation was used at deletion time, the verifier correctly
reported `DIMENSION_MISMATCH` — further proof it tracks current content.

---

## 5. VERIFIER CORRECTNESS — NO BUG FOUND

Both deterministic signature kills yield exactly the expected production outcome:

- JPEG `FFD8FF→000000`: `PermanentFailure(reason=SIGNATURE_INVALID)`
- HEIF `ftyp→XXXX`: `PermanentFailure(reason=SIGNATURE_INVALID)`

The first run's "same-size corruption still VERIFIED" is explained: its write never
landed (nullable stream), so the verifier correctly verified unchanged bytes. The
STOP condition of §4 did not fire.

---

## 6. FULL-VERIFIER SEMANTICS (corrected per §15)

The experiment supports **B. CURRENT EXPORT VALIDITY**, not A. CONTENT IDENTITY:

- The verifier proves the row *currently* holds a structurally valid, decodable image
  consistent with MediaStore metadata (signature, size, MIME, extension, dimensions).
- It does NOT prove bytes are identical to the originally exported bytes: a valid
  different-size replacement verifies VERIFIED against a matching expectation, and a
  same-size replacement of one valid image by another valid image of equal length would
  likewise verify. Only the cryptographic readback proof (SHA-256) used in this
  characterization establishes byte identity.

Production contract (accurate): full verification is authoritative for **current-state
validity**; durable byte-identity claims additionally require a persisted content hash.

---

## 7. JPEG + HEIF SUBSTAGE TIMING (n=12 each, 128×128 production rows, ms)

| Stage | JPEG median | JPEG min/max/p90 | HEIF median | HEIF min/max/p90 |
|-------|------------|-----------------|------------|-----------------|
| provider query | 3.217 | 1.984 / 8.798 / 6.471 | 6.519 | 2.209 / 11.084 / 9.811 |
| content stream open+read | 3.544 | 2.470 / 11.667 / 7.190 | 6.673 | 2.632 / 10.761 / 9.270 |
| bounds decode | 4.025 | 2.982 / 11.600 / 8.280 | 7.640 | 2.992 / 11.303 / 10.970 |
| sampled pixel decode | 4.659 | 3.261 / 11.612 / 10.794 | **107.889** | 75.857 / 138.050 / 137.752 |
| **total verification** | **15.311** | 11.467 / 42.431 / 40.706 | **138.737** | 101.159 / 175.831 / 172.762 |

Structural/container parsing is not separately instrumented: residual
(total − query − stream − bounds − pixel) is reported as **unseparated**
(JPEG ≈ 0 ms, HEIF ≈ 10 ms) rather than invented.

---

## 8. HEIF R4 BOTTLENECK — ISOLATED (§13)

The HEIF excess comes primarily from **sampled pixel decode** (platform HEIF codec):
107.9 ms of 138.7 ms total (**≈78%**). Provider query (6.5 ms), stream I/O (6.7 ms),
and bounds decode (7.6 ms) together account for only ≈15%. JPEG is balanced across
stages (pixel ≈30% of a 15.3 ms total). No retry and no provider-query pathology was
observed. Absolute numbers here are for 128×128 thumbnails; production full-resolution
images scale these magnitudes (cf. R4 aggregates JPEG ~596 ms vs HEIF ~2873 ms per 23),
but the stage proportions identify codec decode — not I/O or queries — as the target
for any future optimization work. **No optimization implemented.**

---

## 9. SIGNALS COLLECTED (§10)

Per row snapshot: URI, `_ID`, volume (`external`), `IS_PENDING`, `SIZE`, `DATE_ADDED`,
`DATE_MODIFIED`, `MIME_TYPE`, `DISPLAY_NAME`, `WIDTH`, `HEIGHT`, `GENERATION_ADDED`,
`GENERATION_MODIFIED`. Provider/database: `MediaStore.getGeneration(external)=314781`
(start), `MediaStore.getVersion(external)=a5d0ddddf6b4c05e`. Row generation,
provider/volume generation, and database version identity are distinguished above and
were never conflated in the analysis.

---

## 10. CLEANUP PROOF (§17)

- `delete()` returned exactly 1 for every test row; exact URIs converged to absent in
  3 ms (JPEG) and 5 ms (HEIF); temp HEIF donor row deleted and converged before matrix end.
- Post-run provider check: zero rows with `display_name LIKE '%u23c2%'`; exact IDs
  1000061376/77/78 absent.
- App-private structured evidence (`u23c2-evidence.json`) removed from the device after
  pulling; raw evidence is preserved in §2 of this report and in logcat tag `U23C2`.
- No R3/R4 or user media touched.

---

## 11. FINAL CLASSIFICATION

### U2.3 DESIGN REOPEN — ADDITIONAL DEVICE CHARACTERIZATION REQUIRED

**Rationale:**

1. The prior **Policy-F rejection was invalid** (measurement-order artifact). Settled
   observation proves `GENERATION_MODIFIED` advances on real same-URI content writes on
   SM-S921N — but with a **delay** (immediate sample stale, settled ≤1000 ms). Any future
   cheap-signal gate must therefore compare against **settled** provider state, never
   against a synchronous post-close read.
2. No safe bounded re-verification policy is **defined or closed** by this report:
   reboot survival, database-rebuild/reset behavior, cross-OEM validity, full-resolution
   46×3 closure, and generation-delay bounds under load all remain uncharacterized.
3. The verifier is proven correct for signature kills and its contract is now accurately
   scoped to current-state validity (B), not byte identity (A).
4. The HEIF bottleneck is isolated to sampled codec decode; optimization is future work.

**Recommendation:** re-evaluate the design around a settled-generation cheap gate with
strict fail-closed invalidation (generation mismatch/missing, reboot, app upgrade,
provider-version change, cadence expiry), and close it with the remaining
characterization listed above. Production recovery/verifier policy is UNCHANGED
(full verify every cold start).

---

**Author:** opencode agent
**Status:** COMPLETE (supersedes U2.3-C)
