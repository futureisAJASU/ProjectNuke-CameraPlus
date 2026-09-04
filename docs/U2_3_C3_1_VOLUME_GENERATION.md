# U2.3-C3.1 VOLUME GENERATION AUTHORITY GATE

**Repository:** futureisAJASU/ProjectNuke-CameraPlus
**Baseline:** 7f84c0d4f71e8f6e893bc796da224355f6747e68
**Reference Device:** SM-S921N (Samsung Galaxy S24)
**adb serial:** R3CX40A15GB (serial ≠ model)
**Software:** Android 17 / API 37, user 0 (owner)
**Package:** com.projectnuke.keplernightlab
**Date:** 2026-09-04
**Test:** U23VolumeGenerationTest.characterizeVolumeGenerationC31
**Screen:** UI-independent (ContentResolver only)

C3 row-generation evidence is ACCEPTED and NOT re-litigated. This phase tests the one
remaining candidate: volume-level `MediaStore.getGeneration(context, exactVolume)` with
the version-first contract. NO production code changed. NO optimization implemented.

---

## 1. OFFICIAL CONTRACT (current developer.android.com, verified 2026-09-04)

- `getGeneration(Context, String)` (API 30+): "Return the latest generation value for
  the given volume. Generation numbers are **monotonically increasing over time**, and
  can be safely arithmetically compared." Recommended over `DATE_ADDED`/`DATE_MODIFIED`
  for change detection.
- **Version-first (mandatory):** "before comparing these detailed generation values, you
  should first confirm that the overall version hasn't changed by checking
  `MediaStore.getVersion(Context, String)`... **If the overall version changes, you
  should assume that generation numbers have been reset and perform a full
  synchronization pass.**"
- `getVersion` is **opaque** ("No other assumptions should be made"); only
  equality/inequality continuity is used — never string-format inference. (On newer
  Android the version may be app-specific; continuity per app is what matters.)
- AOSP MediaProvider: `GENERATION_ADDED`/`GENERATION_MODIFIED` exist for "quickly and
  reliably detecting changes... since a previous synchronization point" — a design goal
  for the sync use-case, not a per-write promise. The docs promise monotonicity, not
  one increment per write.

---

## 2. METHOD

- Volumes resolved authoritatively via `MediaStore.getVolumeName(uri)` (compile SDK 36;
  present in `android.jar`), never by assuming path segments. Both test URIs resolve to
  volume `external`; `getVersion`/`getGeneration` queried per exact volume.
- 30 JPEG + 30 HEIF SHA-proven same-size writes (non-null PFD, flush, fsync, close).
  Per-write explicit asserts: `preSHA != postSHA`, readback == written payload, equal
  length (§10 — no construction-only claims).
- Per write: row gen + volume gen + version BEFORE/AFTER, settled independently over
  0/10/25/50/100/200/500/1000/5000 ms (each signal stops at its own first change).
- Tri-state reads VALUE/QUERY_FAILED/UNAVAILABLE for both generations; failures never
  count as changed or unchanged (would force FULL VERIFY).
- Race probe: reader captures row gen + volume gen + version + SHA in one stream
  (~5 ms cadence, ~860/801 samples); writer 20 proven writes/format; windows counted
  separately for stale-row / stale-volume / stale-both + new bytes.
- False-positive probe: modify an unrelated owned row only; observe target + volume.

---

## 3. RAW EVIDENCE (logcat tag U23C31)

```
VOL-C31 jpeg volume=external ver=a5d0ddddf6b4c05e gen=315646
VOL-C31 heif volume=external ver=a5d0ddddf6b4c05e gen=315646
MATRIX-C31 jpeg fmt=JPEG n=30 A=15 B=15 C=0 D=0 rowDelayN=15 volDelayN=30
MATRIX-C31 heif fmt=HEIF n=30 A=16 B=14 C=0 D=0 rowDelayN=16 volDelayN=30
RACE-C31 jpeg windows=20 staleRow=20 staleVol=0 staleBoth=0
RACE-C31 heif windows=20 staleRow=20 staleVol=0 staleBoth=0
FALSEPOS-C31 targetUnchanged=true volumeAdvanced=true
QUERYFAIL-C31 row=0 vol=0
```

Row delays (valid, sampled first): JPEG n=15 min 12 / p50 31 / p90 58 / max 106 ms;
HEIF n=16 min 31 / p50 57 / p90 106 / max 208 ms — consistent with C3.

**Honest caveat on volume-delay numbers:** volume sampling ran AFTER row settlement per
write, so volume first-sighting times (JPEG p50 ≈ 5004 ms when row-missed) are UPPER
bounds contaminated by ordering, not a true distribution (min 13/33 ms shows fast
landing). The tight freshness evidence is the race probe: **zero stale-volume samples
in ~1660 post-close polls at ~5 ms cadence** — the volume signal is fresh within
milliseconds of close on this device. Version was stable (`a5d0ddddf6b4c05e`)
across all 60 writes (asserted per write).

---

## 4. CRITICAL MATRIX VERDICT (§4)

| Cell | JPEG (n=30) | HEIF (n=30) |
|------|------------|------------|
| A. row changed + volume changed | 15 | 16 |
| B. row unchanged + volume changed | 15 | 14 |
| C. row changed + volume unchanged | 0 | 0 |
| **D. both unchanged (decisive failure)** | **0** | **0** |

**D = 0/60.** No SHA-proven write under stable version left both signals unchanged.
Row generation misses (B = 29/60, reproducing C3) while **volume generation advanced on
60/60 writes** within the 5 s bound. Per the §4 rule, the coarse volume-generation
candidate **remains viable**.

---

## 5. RACE VERDICT (§7)

- Stale ROW + new bytes: 20/20 windows both formats (C3 reproduced with version pinned).
- **Stale VOLUME + new bytes: 0/20 both formats**, same version throughout.
- Stale BOTH + new bytes: 0/20.

A volume-generation cheap gate does NOT exhibit the concurrent-truth hazard that killed
the row gate: any post-close reader observes the advanced volume generation with the
new bytes. Combined with version pinning, the volume signal is a SOUND coarse
invalidation signal on this device.

---

## 6. FALSE-POSITIVE SCOPE (§8)

Modifying an unrelated owned row advanced volume generation (315885 → 315897) while the
target row's SHA stayed identical. EXPECTED coarse behavior — not a safety failure. It
prices the tradeoff honestly: **any media change on the volume conservatively forces
full verification** of all journal rows. On a busy shared volume this erodes savings;
it never erodes safety.

---

## 7. REBOOT / RESET POSTURE (§9 — NO REBOOT, NO DESTRUCTIVE TEST)

- First cold start after reboot → FULL VERIFY (boot-ID boundary; generation continuity
  across reboot irrelevant to fast-path safety by construction).
- `getVersion` mismatch (per exact volume) → generation evidence void → FULL VERIFY.
- No MediaStore rebuild/clear/reset performed or required; the version-first contract
  covers provider resets fail-closed by documentation.

---

## 8. POLICY DECISION (§13/§14)

**Chosen: B. VERSION + VOLUME-GENERATION COARSE INVALIDATION** — DEFINED (not
implemented) as the §20C bounded predicate in the design doc. Row-generation-only
Policy F is NOT resurrected (C3 stands). No narrower policy is taken: omitting nothing
beyond content/decode stages, and only under the full predicate.

Why the pixel-probe question now resolves: C3 rejected pixel-skip because
row-generation-match could not prove "unchanged". The volume gate repairs exactly that
premise — version-stable + volume-unchanged ⟹ no volume change since the durable full
verification ⟹ current-state validity evidence still holds, so the expensive
content/decode stages (stream/signature/bounds/pixel) may be omitted ONLY while every
predicate leg holds. Every leg failure → full verifier. This is coarse invalidation,
not content trusting: row existence, pending, identity, MIME/size/dimensions, schema,
and boot-boundary checks still run every cold start.

---

## 9. FINAL CLASSIFICATION

### U2.3 DESIGN PASS — SAFE VERSION/VOLUME-GENERATION BOUNDED POLICY DEFINED

Scope: SM-S921N / Android 17 observed behavior + official monotonicity/version-first
contract; 100 observed volume bumps (60 matrix + 40 race windows), zero misses, zero
query failures, zero version flaps. Residual bounds stated honestly: single device,
single volume, ≤100-write scale, no reboot-continuity claim (fail-closed instead),
volume-delay distribution unmeasured beyond the 5 s bound (race proves ms-freshness).
DO NOT IMPLEMENT U2.3 in this phase — predicate defined, implementation is future work.

---

## 10. CLEANUP PROOF (§16)

- Zero rows matching `%u23c31%` (provider check post-run); owned IDs absent (asserted
  in-test); on-device `u23c31-evidence.json` pulled then removed.
- No pending rows; no user media touched. `adb shell svc power stayon false` at end.

**Author:** opencode agent
**Status:** COMPLETE
