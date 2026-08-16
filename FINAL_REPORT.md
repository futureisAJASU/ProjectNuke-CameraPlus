# FINAL REPORT — KeplerNightLab MediaStore Debt Convergence & Terminal Settlement Closure

## 1. Starting HEAD
```
1edd2675c80b467a09cb5fb1b57daf21a32bf286
```

## 2. Ending HEAD
```
f7990e1 (HEAD -> main)
```

## 3. Commits Created
```
f7990e1 fix(mediastore): resolve all BLOCKERs in MediaStore debt convergence and terminal settlement
```

## 4. Exact MediaStore Debt Convergence Model

**Single Settlement Protocol for PUBLIC_EXPORT Operation E:**
1. **Resolve external MediaStore commit/verification authority** — provider inspection via `MediaStoreExportRecoveryAccess`
2. **Reconcile exact owner-correlated journals** — `recoverMediaStoreExportJournals` classifies each journal against provider truth
3. **Persist matching terminal metadata** — `exportCommitState`, `galleryExportCommitted`, `exportVerified`, `exportUri` written atomically
4. **Persist terminalMetadataPersisted acknowledgement** — per-journal `markTerminalPersisted` only when `terminalAckEligible` passes
5. **Retain explicit debt for ineligible journals** — UNKNOWN, lagging pre-commit, unresolved sidecar, divergent URI journals block ACTIVE clear
6. **Clear ACTIVE E only when no unresolved owner debt remains** — `markMediaStoreExportJournalsTerminalPersisted` returns `SETTLED` iff all owner journals `isTerminallyStable()`
7. **Release lease only after step 6** — `releaseIfProcessingSettled` gated on `pendingPublicExportSettlement == null`

**Invariant:** Never `ACK rejected → ACTIVE cleared anyway`. Never `journal unresolved → owner released anyway`. Never `UNKNOWN provider state → generic terminal failure → owner cleared`.

## 5. Exact Terminal ACK / ACTIVE Owner Invariant

**`terminalAckEligible(metadata, journal)` contract:**
- UNKNOWN record: **never** eligible → journals deferred for authoritative settlement
- VERIFIED metadata: requires journal.state == VERIFIED + exact URI match (MAIN_IMAGE)
- PUBLIC_COMMITTED_UNVERIFIED metadata: requires journal.state in {PUBLIC_COMMITTED, VERIFIED} + exact URI match
- NOT_COMMITTED metadata: requires journal.state in {CLEANED, INSERT_FAILED_NO_ROW}
- RAW_DNG_SIDECAR: acknowledged from **own per-frame evidence only** — never inherits MAIN state

**`markMediaStoreExportJournalsTerminalPersisted` behavior:**
- Returns `MediaStoreExportTerminalSettlementStatus` (SETTLED | DEFERRED)
- Acknowledges only `terminalAckEligible` journals
- **Clears ACTIVE PUBLIC_EXPORT only when zero pending owner journals** (`!isTerminallyStable()`)
- DEFERRED preserves ACTIVE + lease for next acquisition/settlement retry

**`settleOwnedPublicExportInterruption` behavior:**
- Returns `Boolean` (settled = true only when all owner journals resolved)
- With journals requiring external resolution and **no provider access**: returns false immediately
- With provider access: reconciles, cleans conclusive pre-commit journals, returns false if any remain unresolved
- Never classifies lagging CONTENT_WRITTEN/PUBLIC_COMMITTED as definite PRE_COMMIT without provider authority

## 6. Mutation Entry Resolver Callsite Table

| Mutation Intent | Entry Point | Debt Convergence Called | Handoff Debt Handled | PUBLIC_EXPORT UNKNOWN/Lag Handled | Final Gate Result |
|----------------|-------------|------------------------|---------------------|-----------------------------------|-------------------|
| PROCESSING_START | `NightFusionPipeline.captureProcessExportNightFusion` | ❌ (consumes handoff) | ✅ `consumesProcessingHandoff=true` | ❌ (handoff consumer bypass) | ALLOWED if handoff matches |
| PROCESSING_START | `RawFusionExport.captureProcessExportRawFusion` | ❌ (consumes handoff) | ✅ `consumesProcessingHandoff=true` | ❌ (handoff consumer bypass) | ALLOWED if handoff matches |
| PROCESSING_START | `ColorFusion.captureProcessExportColorBurst` | ❌ (new capture) | ❌ | ❌ | ALLOWED if empty job |
| REPROCESS | `KeplerGalleryReprocess.reprocessKeplerGalleryJob` | ✅ `settleMediaStoreExportDebt` | ❌ | ✅ all journals by role | BLOCKED_DEAD_OPERATION if unresolved |
| FRAME_SELECTION | `KeplerJobGallery.setFrameExcluded` | ✅ `settleMediaStoreExportDebt` | ❌ | ✅ all journals by role | BLOCKED_DEAD_OPERATION if unresolved |
| METADATA_EDIT | `KeplerJobGallery.saveJobJson` | ✅ `settleMediaStoreExportDebt` | ❌ | ✅ all journals by role | BLOCKED_DEAD_OPERATION if unresolved |
| JOB_DELETE | `KeplerJobGallery.deleteKeplerGalleryJob` | ❌ (destructive) | ❌ | ❌ | BLOCKED_DEAD_OPERATION if unresolved |
| JOB_CLEANUP | `KeplerJobGallery.cleanupKeplerGalleryJob` | ❌ (destructive) | ❌ | ❌ | BLOCKED_DEAD_OPERATION if unresolved |

**Key:** Non-destructive mutations (REPROCESS, FRAME_SELECTION, METADATA_EDIT) now attempt `settleMediaStoreExportDebt` before gate. Destructive mutations (JOB_DELETE, JOB_CLEANUP) remain blocked while unresolved evidence exists — safe policy.

## 7. Sidecar Role-Specific Settlement Model

**`reconstructRawSidecarJournalEvidence` drives per-frame convergence from OWN classification:**
- PUBLIC_VERIFIED / PENDING_VERIFIED_AND_COMMITTED → `PUBLIC_EXPORTED` + URI preserved
- PUBLIC_COMMITTED_UNVERIFIED → `PUBLIC_COMMITTED_UNVERIFIED` + URI preserved
- AMBIGUOUS / INSERT_RESULT_UNKNOWN / DELETE_FAILED → `PUBLIC_COMMIT_UNKNOWN` + URI preserved (never missing)
- CLEANED / PUBLIC_COMMIT_MISSING / PENDING_DELETED → `PUBLIC_EXPORT_FAILED` (proven precommit)

**`terminalAckEligible` for sidecars:**
- Reads `dngSidecarPublicStatus` + `publicDngUri` from frame record
- PUBLIC_EXPORTED: requires journal VERIFIED + URI match
- PUBLIC_COMMITTED_UNVERIFIED: requires journal in {PUBLIC_COMMITTED, VERIFIED} + URI match
- PUBLIC_COMMIT_UNKNOWN / PUBLIC_EXPORT_FAILED: **never** acknowledges

**Verified MAIN image never forces sidecar VERIFIED.** Sidecar debt has reachable same-process resolution (`settleMediaStoreExportDebt` inspects sidecar journals by role) and restart recovery path. No app restart required.

## 8. Reprocess UNKNOWN Transaction Model

**`hasUnknownPublicExport` derived independently of local result:**
```kotlin
val hasUnknownPublicExport = outcome.export?.publicCommitState == GalleryExportCommitState.UNKNOWN &&
    outcome.publicExportCommitted == false &&
    outcome.exportVerified == false
```

**Transaction disposition for UNKNOWN:**
- **NEVER rollback** based on `publicCommitted == false` alone
- Quarantine/defer: `quarantineWithPersistence` retains lease + exact URI evidence
- On subsequent resolution:
  - NOT_COMMITTED: rollback eligible
  - PUBLIC_COMMITTED_UNVERIFIED / VERIFIED: transaction MUST NOT rollback external commit
  - UNKNOWN: preserve backups/evidence/debt

**YUV reprocess now preserves UNKNOWN export evidence** (matching RAW behavior):
- `exportCommitState != NOT_COMMITTED` → `committedExport = export`, `publicExportCommitted = export.publicCommitted`
- Terminal disposition: COMMITTED_PARTIAL with exact URI preserved
- No rollback when `publicCommitted == false` but `publicCommitState == UNKNOWN`

## 9. YUV/RAW Four-State Metadata Model

**Single mapper used by all reprocess writers:**
```kotlin
reprocessExportStatus(export):
  NOT_COMMITTED → "EXPORT_FAILED" / "Local result preserved; public export failed."
  UNKNOWN       → "EXPORT_COMMIT_UNKNOWN" / "Public export commit state could not yet be resolved."
  PUBLIC_COMMITTED_UNVERIFIED → "COMMITTED_UNVERIFIED" / "Public export committed; verification incomplete."
  VERIFIED      → "EXPORTED" / null (no debt warning)
```

**Applied in:**
- `writeReprocessSuccess`
- `writeReprocessPartial`
- `writeReprocessPartialPublicOnly`
- `reprocessVerificationDebtWarning` (user-facing)

**No boolean collapse:** `non-null export ≠ EXPORTED`, `!verified ≠ committed-unverified`.

## 10. Processing Handoff Conservation Results

**`KeplerJobMetadata.consumeProcessingHandoff` returns `Boolean`:**
- `true` = already absent / legitimately consumed
- `false` = exact current handoff exists but persistence failed / mismatched

**SuperResolutionFusion:**
- `consumeSourceHandoffIfStillPresent(): Boolean` at every terminal boundary
- On `false`: logs error, publishes FAILED terminal, **does not proceed** to successful terminal
- Source job no longer BLOCKED_HANDOFF after retry/convergence

**Handoff conservation invariant:** Successful SR terminal cannot coexist with stale source handoff. Deterministic test: inject one atomic metadata write failure during consume → no successful SR terminal with stale handoff.

## 11. Additional HIGH/MEDIUM Found During Same-Family Audit

| Issue | Location | Fix |
|-------|----------|-----|
| `performTerminalCleanupDebt` generic-cleared PUBLIC_EXPORT via `clearActiveOperation` | `KeplerGalleryReprocess.kt:1940` | Uses `settleReprocessTerminalOwner` → `markMediaStoreExportJournalsTerminalPersisted` |
| `settleReprocessTerminalOwner` returned `Boolean` losing DEFERRED detail | `KeplerGalleryReprocess.kt:1913` | Returns `MediaStoreExportTerminalSettlementStatus` |
| YUV reprocess threw on UNKNOWN export instead of preserving evidence | `NightFusionPipeline.kt:922` | Matches RAW: preserves UNKNOWN URI, sets COMMITTED_PARTIAL |
| Reprocess writers used boolean `exportVerified` for status text | `KeplerGalleryReprocess.kt:4056+` | Explicit `reprocessExportStatus`/`reprocessExportWarning` mapper |
| FRAME_SELECTION/METADATA_EDIT bypassed debt convergence | `KeplerJobGallery.kt:97,111` | Added `settleMediaStoreExportDebt(context, jobDir)` |
| UNKNOWN rollback eligibility gated on `!currentAttemptHasLocalResult` | `KeplerGalleryReprocess.kt:1669` | `hasUnknownPublicExport` independent of local result |

## 12. Deterministic Tests Added / Updated

All 18 counterexample scenarios covered in `KeplerJobMetadataTest` and `MediaStoreExportTerminalAckTest`:

1. ✅ ACTIVE PUBLIC_EXPORT E, metadata UNKNOWN, journal CONTENT_WRITTEN → DEFERRED, ACTIVE retained
2. ✅ ACTIVE E, metadata PUBLIC_COMMITTED_UNVERIFIED, journal CONTENT_WRITTEN → DEFERRED
3. ✅ ACTIVE E, metadata VERIFIED, journal PUBLIC_COMMITTED → DEFERRED
4. ✅ Journal catches up → terminal ACK → ACTIVE clear → lease release (ordered)
5. ✅ MAIN VERIFIED + SIDECAR UNKNOWN → primary usable, sidecar debt retained, ACTIVE not ownerlessly cleared
6. ✅ Next REPROCESS resolves sidecar → mutation proceeds
7. ✅ Direct FRAME_SELECTION → safe debt resolution attempted before gate
8. ✅ MAIN UNKNOWN + SIDECAR UNKNOWN → MAIN resolves VERIFIED → sidecar still UNKNOWN → next mutation resolves sidecar
9. ✅ Interruption: provider commit may have happened, journal CONTENT_WRITTEN → not definite PRE_COMMIT, debt retained
10. ✅ YUV reprocess: UNKNOWN export with exact NEW_URI → worker outcome preserves evidence, no rollback
11. ✅ RAW reprocess: UNKNOWN + local result → terminal metadata failure → NO rollback
12. ✅ Reprocess terminal cleanup: lease.currentDurableOperationKind == PUBLIC_EXPORT → generic clear path blocked
13. ✅ PUBLIC_COMMITTED_UNVERIFIED reprocess → journal terminal ACK completes → next mutation not BLOCKED_DEAD_OPERATION
14. ✅ SR source handoff consume persistence failure → no successful SR terminal with stale handoff
15. ✅ SR handoff retry/convergence → source no longer BLOCKED_HANDOFF
16. ✅ MAIN VERIFIED + SIDECAR PUBLIC_COMMITTED_UNVERIFIED → role-specific sidecar authority preserved
17. ✅ Sidecar UNKNOWN survives first resolver while MAIN resolves → second resolver invocation based on journal debt
18. ✅ UNKNOWN warning never claims "Public export committed"

## 13. Commands Actually Run and Exact Results

```
.\gradlew.bat compileDebugKotlin          → BUILD SUCCESSFUL
.\gradlew.bat compileDebugUnitTestKotlin  → BUILD SUCCESSFUL
.\gradlew.bat testDebugUnitTest           → 1029 tests completed, 0 failed
.\gradlew.bat assembleDebug               → BUILD SUCCESSFUL
git diff --check HEAD~1                   → clean (no trailing whitespace, no conflict markers)
```

## 14. Intentionally Retained Limitations

- JOB_DELETE / JOB_CLEANUP remain blocked by unresolved MediaStore debt (safe destructive policy)
- PROCESSING_START handoff consumer path bypasses debt convergence (by design — consumes handoff)
- Provider access (`MediaStoreExportRecoveryAccess`) required for conclusive settlement; same-process convergence defers without it
- Sidecar `PUBLIC_COMMIT_UNKNOWN` frames remain gate-blocking until provider confirms or restart recovery
- No automatic background debt resolution — requires explicit mutation entry or restart recovery

---

## SECTIONS EXACTLY NAMED

### MEDIASTORE DEBT CONVERGENCE
Single settlement protocol with 7 invariant steps. Bounded same-process convergence via `settleMediaStoreExportDebt` inspecting ALL journals by role, not just MAIN UNKNOWN.

### TERMINAL ACK / OWNER SETTLEMENT
`markMediaStoreExportJournalsTerminalPersisted` returns explicit `SETTLED|DEFERRED`. ACTIVE cleared only when zero pending owner journals. `settleOwnedPublicExportInterruption` defers on lagging journals without provider access.

### SIDECAR ROLE-SPECIFIC AUTHORITY
`reconstructRawSidecarJournalEvidence` + `terminalAckEligible` use per-frame `dngSidecarPublicStatus`. Verified MAIN never forces sidecar. Unresolved sidecar debt has same-process + restart convergence.

### REPROCESS UNKNOWN AUTHORITY
`hasUnknownPublicExport` independent of local result. UNKNOWN → quarantine/defer, never rollback. YUV preserves UNKNOWN evidence matching RAW.

### PROCESSING HANDOFF CONSERVATION
`consumeProcessingHandoff` returns Boolean. SuperResolution checks at every terminal boundary. Failure blocks successful terminal.

### SAME-FAMILY COUNTEREXAMPLE PASS
All 18 deterministic scenarios tested. Updated existing tests to match new invariant. No probabilistic timing, no Thread.sleep.

---

**END-TO-END PRODUCTION INTEGRATION AUDIT: CLOSED**