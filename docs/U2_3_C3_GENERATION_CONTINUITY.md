# U2.3-C3 GENERATION CONTINUITY / RACE CHARACTERIZATION

**Repository:** futureisAJASU/ProjectNuke-CameraPlus
**Baseline:** 8b9b495e5277c2e2acd654bb433232f7f7f401f3
**Reference Device:** SM-S921N (Samsung Galaxy S24)
**adb serial:** R3CX40A15GB (serial ≠ model; model via `getprop ro.product.model`)
**Software:** Android 17 / API 37, user 0 (owner)
**Package:** com.projectnuke.keplernightlab
**Date:** 2026-09-04
**Test:** U23GenerationContinuityTest.characterizeGenerationContinuityC3
**Screen:** UI-independent (ContentResolver only; no Camera/Gallery/UiAutomator)

C2 evidence is ACCEPTED and not re-litigated here. C3 answers the three questions C2
left open: delay distribution under repetition, behavior under load, and the
concurrent-modification race. NO production code was changed. NO optimization implemented.

---

## 1. METHOD

- Test-owned JPEG + native HEIF rows via production `exportNightFusionBitmapToGallery`.
- Every write: non-null PFD `rwt` handle, full payload, flush, fsync, close; **readback
  SHA-256 asserted equal to the written payload** (80/80 delay writes + 40/40 race writes
  proven, 0 write failures, 0 null handles).
- Same-size alternating payloads (single byte at `size/2` toggled `A5/5A`), so every
  consecutive write provably changes bytes; length preserved exactly throughout.
- Delay sampling per write at 0/10/25/50/100/200/500/1000/2000 ms post-close; censored
  iterations get one confirmatory sample at +5000 ms (distinguishes very-late landing
  from a write the generation missed entirely).
- Race probe: writer mutates one URI (20 writes/format, proven each); an independent
  reader thread polls (generation, full-content SHA) continuously (~5 ms/poll, ~1000
  samples/probe); post-hoc window analysis over [t_close, t_close+250 ms].
- Volume identity per observation: exact volume from the URI (`external`), plus
  `MediaStore.getGeneration(exactVolume)` and `MediaStore.getVersion(exactVolume)`.
- Deletion tri-state PRESENT/ABSENT/QUERY_FAILED; query exceptions NEVER count as
  absence; final cleanup ASSERTS absence of every owned URI (6/6 absent).

---

## 2. RAW EVIDENCE (logcat tag U23C3)

```
VOL-C3  jpeg volume=external provGen=314889 provVer=a5d0ddddf6b4c05e
VOL-C3  heif volume=external provGen=314889 provVer=a5d0ddddf6b4c05e
DELAY-C3 jpeg idle   n=30 proven=30 censored2000=14 late5000=0 never5000=14 min=27 p50=46 p90=57 p95=60 max=60 ms
DELAY-C3 heif idle   n=30 proven=30 censored2000=14 late5000=0 never5000=14 min=30 p50=59 p90=116 p95=116 max=116 ms
DELAY-C3 jpeg loaded n=10 proven=10 censored2000=4  late5000=0 never5000=4  min=23 p50=39 p90=42 p95=42 max=42 ms
DELAY-C3 heif loaded n=10 proven=10 censored2000=4  late5000=0 never5000=4  min=28 p50=58 p90=111 p95=111 max=111 ms
RACE-C3 jpeg writes=20 staleGenNewBytesWindows=20/20 changedWindows=7/20  distinctGens=8  singleQuerySafe=false (968 reader samples)
RACE-C3 heif writes=20 staleGenNewBytesWindows=20/20 changedWindows=6/20  distinctGens=7  singleQuerySafe=false (998 reader samples)
REBOOTPREP-C3 jpeg uri=.../1000061390 sha=70fe86aed75d provVer=a5d0ddddf6b4c05e provGen=315203
REBOOTPREP-C3 heif uri=.../1000061391 sha=76ee034ff0ab provVer=a5d0ddddf6b4c05e provGen=315211
REBOOT-C3 PERMISSION REQUESTED (reboot NOT executed; prep rows captured then removed)
CLEANUP-C3 all 6 owned URIs absent=true (asserted)
```

Percentile base: non-censored observations only (16/30 idle per format, 6/10 loaded).
"Observed maximum is device evidence, NOT a platform guarantee" — no safe bound is
claimed from these maxima.

---

## 3. DELAY DISTRIBUTION (§4)

| Cohort | n | proven writes | censored >2000 ms | landed by 5000 ms | never by 5000 ms | min | p50 | p90 | p95 | max |
|--------|---|--------------|-------------------|-------------------|------------------|-----|-----|-----|-----|-----|
| JPEG idle | 30 | 30 | 14 | 0 | **14** | 27 | 46 | 57 | 60 | 60 |
| HEIF idle | 30 | 30 | 14 | 0 | **14** | 30 | 59 | 116 | 116 | 116 |
| JPEG loaded | 10 | 10 | 4 | 0 | **4** | 23 | 39 | 42 | 42 | 42 |
| HEIF loaded | 10 | 10 | 4 | 0 | **4** | 28 | 58 | 111 | 111 | 111 |

(times in ms; percentiles over landed observations only)

Findings:

1. When generation lands, it lands fast (JPEG p50 ≈ 46 ms, HEIF p50 ≈ 59 ms) —
   consistent with C2's ≤100 ms observation for isolated writes.
2. **~40–47% of SHA-proven writes NEVER advance row generation within 5000 ms**
   (14/30, 14/30, 4/10, 4/10). This is not delay — it is coalescing: the increment is
   missing entirely, so NO bounded settlement wait can repair a generation gate.
3. Observed pattern (every-other-write landing; 8 distinct values across 20 race writes)
   is consistent with per-row coalescing of rapid successive modifications (plausibly
   coupled to `DATE_MODIFIED` 1-second granularity), but the mechanism is NOT proven —
   recorded here as hypothesis only. Inter-write spacing in this test was ~0.1–2 s;
   C2's isolated writes (seconds apart with verifier work between) always landed.

---

## 4. LOAD COMPARISON (§5)

Bounded realistic pressure: 2 extra owned rows per run + full-verifier runs interleaved
before every measured write. Miss rate idle (≈47%) vs loaded (40%) and landed
percentiles are materially identical — load does NOT explain the misses, and does not
rescue the gate. No destructive system load was created; user media untouched.

---

## 5. CONCURRENT-MODIFICATION RACE (§6) — THE SAFETY ANSWER

**Can a cheap gate see old matching generation while current content has changed? YES.**

- JPEG: 20/20 post-close windows contain reader samples with **stale generation AND new
  bytes**; only 7/20 windows show ANY generation change; 8 distinct generation values
  across 20 proven writes.
- HEIF: 20/20 stale; only 6/20 windows change; 7 distinct values across 20 writes.

A single immediate generation query is therefore UNSAFE — and because missed increments
never arrive, a settled re-query is unsafe too. Any predicate of the form
"row generation == last verified generation ⟹ skip verification" is REJECTED on this
reference device. Production recovery was not modified; this is characterization only.

---

## 6. REBOOT CONTINUITY (§7) — NOT AUTHORIZED, PREP ONLY

Exact JPEG + HEIF prep rows were created and captured (URI, volume, provider version
`a5d0ddddf6b4c05e`, row generations, SHA-256, verifier result), then removed
(final cleanup asserted absent). **Reboot was NOT executed — permission not granted.**

Continuity across reboot is UNKNOWN in every U2.3 pass. Standing rule (independent of
any future evidence): **full verification after every reboot**, detected via boot-ID
change; generation evidence never survives a reboot boundary by assumption.

**REQUEST:** explicit user permission for a future reboot pass that re-creates
`u23c3-reboot` rows, captures the same fields, reboots, and compares version /
generations / SHA / verifier under the same user.

---

## 7. DATABASE REBUILD / RESET (§8) — NO DESTRUCTIVE TEST

No provider data was cleared, no database touched, no reset performed. The design relies
on the official version-first contract (§4.5 of the design doc): persist exact volume +
`getVersion` alongside any generation evidence; on ANY version mismatch, generation
evidence is void → full verification / full synchronization. This fallback is proven by
documentation and needs no destructive experiment.

---

## 8. DELETION HARNESS (§9)

Fixed in C2 (`awaitRowAbsentMs`) and reused in C3: PRESENT/ABSENT/QUERY_FAILED
tri-state; provider/query exceptions keep waiting until the bound and NEVER count as
deletion success; only an authoritative empty/missing row converges. All 6 C3 URIs
converged and final cleanup ASSERTED (not logged) absence.

---

## 9. HEIF BOTTLENECK WORDING (§10)

Kept fixture-bounded: **on the bounded 128×128 production-faithful fixture, sampled
HEIF pixel decode is the dominant measured standalone stage (~108 ms median vs ~139 ms
total).** No claim is made that exactly 78% of full-resolution R4 recovery is pixel
decode. Supporting direction: R4 per-row HEIF aggregate ≈ 2873/23 ≈ 125 ms, consistent
with codec-dominated cost rather than I/O- or query-dominated cost.

---

## 10. POLICY COMPARISON (§11)

| Candidate | Verdict | Guarantees kept |
|-----------|---------|-----------------|
| A. Full verify every cold start (current) | **RETAIN** (only safe option) | All: deletion, pending, truncation, signature, MIME/name, dimensions, same-URI replacement |
| B. Generation/version-gated full verifier | **REJECTED** (C3) | Loses same-URI replacement detection: ~40–47% of rapid writes never bump; 20/20 race windows stale |
| C. Gated expensive PIXEL PROBE only (keep row+stream/signature/bounds) | **REJECTED** (C3) | Premise is B's gate (fails); plus pixel-skip safety unproven (§11 below) |
| D. Cadence + generation/version hybrid | **REJECTED** | Generation leg fails per C3; pure cadence opens a corruption window (deletion/replacement undetected between full verifies) |
| E. Format-specific (any generation-gated variant) | **REJECTED** | Both formats exhibit identical coalescing; JPEG-only economics cap at ≈14.6% anyway |

---

## 11. PIXEL-PROBE SAFETY QUESTION (§12)

What does sampled pixel decode detect that earlier stages do not?
Per `verifyOnce` order (query → stream/signature/completeness → bounds → pixel →
metadata): the pixel probe is the backstop for **header-valid but body-undecodable**
payloads — valid signature, complete stream framing, parseable bounds, yet undecodable
pixels (corrupt media data, truncated HEIF `mdat`/boxes past the weak `size>=16`
completeness check, unsupported codec features). This backstop matters MOST for HEIF,
exactly the format where it dominates cost.

Skipping it is rejected on two independent grounds: (1) the only proposed trigger
(unchanged generation) does not prove unchanged content (C3); (2) even on truly
unchanged rows, the probe's marginal detection value is unquantified — no
corrupt-but-header-valid corpus was tested. **Not proven safe. Not implemented.**

---

## 12. PERFORMANCE MODEL (§13, corrected aggregates)

- Baseline: recovery median 4093.030 ms; verification aggregate 3469.124 ms;
  JPEG aggregate ≈596 ms / 23 rows (≈26 ms/row); HEIF aggregate ≈2873 ms / 23
  (≈125 ms/row). 596 + 2873 = 3469 ✓.
- JPEG-only skip ceiling: ≈596 ms (≈14.6% of recovery) before overhead — limited upside.
- HEIF holds ≈83% of verification cost; its lever is pixel decode, which §11 rejects
  touching without a safety proof that does not exist.
- Cost lines a future model must carry explicitly: provider query + row checks +
  stream/signature + bounds + pixel + any settlement wait (100–1000 ms+, which C3 shows
  buys nothing for coalesced writes).

---

## 13. DESIGN PASS REQUIREMENT (§14) — NOT MET

A PASS requires stating exactly which CURRENT evidence permits omitting which expensive
stages without weakening the truth contract (row existence, `IS_PENDING==0`, row
identity, version match, generation match, size/MIME/dimensions, schema version,
legacy→full-verify, race handling, reboot handling, provider-reset handling). The
generation-match leg — load-bearing for every gating candidate — is disproven by C3.
**Remain REOPEN.**

---

## 14. FINAL CLASSIFICATION

### U2.3 DESIGN REOPEN — CONCURRENT GENERATION RACE UNSAFE

Reboot continuity is additionally UNKNOWN (permission pending). Production
recovery/verifier behavior UNCHANGED. DO NOT IMPLEMENT U2.3.

---

## 15. CLEANUP PROOF (§16)

- adb serial R3CX40A15GB; model SM-S921N; Android 17 / API 37; user 0.
- Zero rows matching `%u23c3%`; exact IDs 1000061386–1000061391 absent (provider check
  post-run); on-device `u23c3-evidence.json` pulled then removed.
- No R3/R4/user media touched. `adb shell svc power stayon false` at end.

**Author:** opencode agent
**Status:** COMPLETE
