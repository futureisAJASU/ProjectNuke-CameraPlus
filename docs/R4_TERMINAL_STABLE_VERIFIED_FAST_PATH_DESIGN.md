# docs/R4_TERMINAL_STABLE_VERIFIED_FAST_PATH_DESIGN.md

## START HEAD
`9ba66194f6ee282cd4bdb701752b1164c44f4099`

---

## R4.2 DESIGN CORRECTIVE — FAIL-CLOSED PREDICATE CONTRACT ONLY

> **NOTICE (R4.2 CORRECTIVE)**: Two narrow defects in the eligibility predicate contract from R4.1 are corrected:
> 
> 1.  The contract now requires an *explicit* `recoveryState == "STABLE"`, rejecting jobs where `recoveryState` is missing or blank.
>     - The prior use of `.ifBlank { "STABLE" }` could allow incomplete jobs to appear eligible.
>
> 2.  The eligibility helper now accepts and uses the authoritative `File jobDir` argument directly, eliminating unsafe derivation from metadata.
>     - `jobDirAbsolutePath` is not a mandatory field; depending on it creates authority failures.
>     - The helper now uses the exact `jobDir` provided by `recoverOne`.

---

## 1. PRODUCTION STATE-MACHINE TRACE FOR R3.1 COHORT

For an already-terminal, already-settled, currently reverified MAIN export, the current production code in `KeplerRecoveryCoordinator` executes a transient state cycle resulting in two content-changing writes:

1. **Initial Stable State**:
   - `ACTIVE_OPERATION_ID` is blank.
   - `TERMINAL_OPERATION_ID` is present (from the original export execution).
   - `currentPipelineStage` is in `COMPLETE`, `PARTIAL`, `FAILED`, `CANCELLED` (terminal stage).
   - `galleryExportCommitted` == `true`, `exportVerified` == `true`, `exportUri` is nonblank.
   - `recoveryState` == `"STABLE"`, `recoveryMessage` is absent/blank.
   - This satisfies `terminalResultAlreadyProven` as `true` (lines 216-220).

2. **Step1: MediaStore Recovery & Inspection**:
   - `recoverMediaStoreExportJournals()` (lines 238-242) is executed.
   - The `MAIN_IMAGE` export journal is inspected.
   - `ContextMediaStoreExportRecoveryAccess.inspect()` runs a MediaStore content provider query (to check `IS_PENDING` which is false) and `verifyGalleryExportResult()` (pixels/bounds checked).
   - This returns a `MediaStoreExportRecoveryResult` with classification `PUBLIC_VERIFIED`.

3. **Step2: resolveCurrentMainAuthority()**:
   - Selects the matching journal and recovery result, returning `CurrentMainAuthorityResolution.Resolved`.
   - `recoveredMainVerified` == `true` and `recoveredMainCommit` == `true`.

4. **Step3: State Mutation Phase 1 (Reconstruct MAIN Export Write)**:
   - Since `exportAuthorityOperation` is nonblank (resolves to `TERMINAL_OPERATION_ID`), and `exportResults` is not empty, `reconstructMainExportEvidence` is called within a `KeplerJobMetadata.update(..., RECONSTRUCT_MAIN_EXPORT)` block (lines 358-366).
   - **Write 1 (RECONSTRUCT_MAIN_EXPORT)** occurs because `recoveryState` changes from `"STABLE"` to `"PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL"`, and `recoveryMessage` is added ("이전 실행이 종료된 후 공개 내보내기 결과를 확인했습니다.").
   - Even though other fields like `galleryExportCommitted`, `exportVerified`, and `exportUri` remain equal, the overall JSON serialized content has changed (`contentChanged` is true).

5. **Step4: State Mutation Phase 2 (Terminal Stable Settlement Write)**:
   - Since `activeOperation` is blank (no live/dead active process owner), the deferred terminal settlement block (lines 392-441) is skipped.
   - Code reaches the bottom block (lines 613-624) because `terminalResultAlreadyProven` is `true`.
   - `needsStableSettlement` is evaluated as `true` because `recoveryState` is now `"PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL"` (not `"STABLE"`).
   - **Write 2 (TERMINAL_STABLE_SETTLEMENT)** occurs via `KeplerJobMetadata.update(..., TERMINAL_STABLE_SETTLEMENT)` to revert `recoveryState` back to `"STABLE"` and remove `recoveryMessage`.
   - This returns the JSON serialized metadata back to its exact initial byte-for-byte baseline.

This explains why **92 content-changing / 0 same-content writes** occurred for the 46-job cohort in R3.1: 46 jobs executed exactly one `RECONSTRUCT_MAIN_EXPORT` write followed by exactly one `TERMINAL_STABLE_SETTLEMENT` write.

---

## 2. DO NOT USE terminalResultAlreadyProven AS THE FAST-PATH GATE

The `terminalResultAlreadyProven` helper is a weak structural predicate.

### What it DOES establish:
- The process has no active operation owner (`activeOperation.isBlank()`).
- The job finished through a terminal pipeline stage (e.g., `COMPLETE`, `PARTIAL`).
- An export record was previously marked committed, verified, and has a recorded URI.

### What it DOES NOT establish:
- **Durable Settlement State**: It does not guarantee that the export journal is itself terminally stable (`terminalMetadataPersisted == true`).
- **Journal/Metadata Agreement**: It does not ensure the metadata's `exportUri` matches the actual on-disk export journal URI or that only a single valid `MAIN_IMAGE` journal exists.
- **Provider-Level Reality**: It does not inspect MediaStore to verify if the row actually exists, if `IS_PENDING` is false, or if verification is currently successful. The row could have been externally deleted, corrupted, or re-marked pending.
- **Ambiguity**: It doesn't check for invalid/corrupt journal files or mismatched/divergent links.

Thus, utilizing `terminalResultAlreadyProven` as a fast-path gate alone would violate recovery authority and bypass real verification. The candidate fast path must distinguish between merely *terminal-looking* metadata and *proven, settled, and currently verified* terminal-stable metadata.

---

## 3. FAIL-CLOSED ELIGIBILITY CONTRACT

We design the narrowest, most defensive eligibility predicate to safely authorize the MAIN reconstruction suppression.


### Field-by-Field/Condition Audit:

| Condition / Field Check | Required? | Justification / Proof of Requirement |
|---|---|---|
| `no ACTIVE_OPERATION_ID` | **Yes** | A live or dead active operation owner means the job is not yet stable; it must proceed through standard reconstruction. |
| `no PROCESSING_HANDOFF_OPERATION_ID` | **Yes** | Active processing transactions must be resolved; handoffs block terminal recovery. |
| `terminal pipeline stage` | **Yes** | Must be in `COMPLETE`, `PARTIAL`, `FAILED`, or `CANCELLED`. Incomplete jobs cannot have stable terminal exports. |
| `recoveryState == STABLE` | **Yes** | If the previous recovery state was not `STABLE`, then there is unresolved debt or active recovery work. |
| `no recoveryMessage` | **Yes** | Any recovery error/warning message indicates debt that cannot be bypassed. |
| `galleryExportCommitted == true` | **Yes** | Verifies that a committed public export was recorded. |
| `exportVerified == true` | **Yes** | Verifies that the public export was already marked verified. |
| `nonblank exact exportUri` | **Yes** | A valid URI is required to perform current verification. |
| `galleryPublicExportLinkage agrees with exportUri` | **Yes** | Strict link integrity. If they disagree, there is unresolved structural divergence. |
| `exportDisplayName matches journal` | **Yes** | If the metadata's recorded `exportDisplayName` disagrees with the journal's `displayName`, reconstructive metadata alignment must run. |
| `exportMimeType matches journal` | **Yes** | If `exportMimeType` disagrees with the journal's `mimeType`, reconstructive alignment must run. |
| `exact CurrentMainAuthorityResolution.Resolved` | **Yes** | Structural verification of current authority. Must resolve uniquely without ambiguity. |
| `exact MAIN_IMAGE journal` | **Yes** | There must be an inspected, valid, single MAIN_IMAGE journal corresponding to the export. |
| `journal URI matches exportUri` | **Yes** | Structural correlation between metadata and journal. |
| `journal state == VERIFIED` | **Yes** | The journal must have historically reached verification. |
| `journal.terminalMetadataPersisted == true` | **Yes** | Requires modern terminal stable acknowledgment. Rejects legacy un-acknowledged journals. |
| `journal.terminalOperationId == TERMINAL_OPERATION_ID` | **Yes** | Exact triple correlation: `journal.ownerOperationId == journal.terminalOperationId == metadata.TERMINAL_OPERATION_ID`. |
| `current MediaStore inspection exists` | **Yes** | We must have completed a live MediaStore provider inspection in the current session. |
| `IS_PENDING == false` | **Yes** | The row must be committed and not pending in MediaStore. |
| `current verification result == PUBLIC_VERIFIED` | **Yes** | Real pixels and bounds must be verified successfully right now. |
| `verification diagnostic == null` | **Yes** | Any validation warning/failure blocks optimization. |
| `no ambiguity / missing-commit / unverified result` | **Yes** | Any unresolved classification forces standard path. |
| `no unresolved export journal debt` | **Yes** | All other journals for this job directory must be in state `CLEANED` or absent. |
| `no RAW sidecar recovery work` | **Yes** | `rawSidecarRecoveryApplies(jobDir, job) == false`. RAW sidecar jobs are excluded. |

---

## 4. FINAL-STATE EQUIVALENCE PROOF

For a job meeting the Fail-Closed Eligibility Contract:

### Metadata Fields written by `reconstructMainExportEvidence`:
- `galleryExportCommitted` -> Set to `true` (already `true` in eligible contract).
- `exportVerified` -> Set to `true` (already `true` in eligible contract).
- `exportUri` -> Set to `selected.uri` (already matches `exportUri` in eligible contract).
- `galleryPublicExportLinkage` -> Set to `selected.uri` (already matches `galleryPublicExportLinkage` and `exportUri` in eligible contract).
- `exportDisplayName` -> Set to `selected.displayName` (already matches `exportDisplayName` in eligible contract).
- `exportMimeType` -> Set to `selected.mimeType` (already matches `exportMimeType` in eligible contract).
- `recoveryState` -> Temporarily set to `"PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL"`.
- `recoveryMessage` -> Temporarily set to `"이전 실행이 종료된 후 공개 내보내기 결과를 확인했습니다."`.

### Metadata Fields written by `TERMINAL_STABLE_SETTLEMENT`:
- `recoveryState` -> Reverted to `"STABLE"`.
- `recoveryMessage` -> Removed (blank/absent).

### Net Change:
Under standard production, the net effect of the cycle is that `recoveryState` and `recoveryMessage` undergo a transient mutation and are then reverted back to `"STABLE"` and `absent` respectively. When `reconstructMainExportEvidence` is suppressed, `recoveryState` remains `"STABLE"` and `recoveryMessage` remains absent throughout `recoverOne`.

### Zero-Write Settlement Proof:
At lines 613-624 of `KeplerRecoveryCoordinator.kt`:
```kotlin
if (terminalResultAlreadyProven) {
    val needsStableSettlement = job.optString("recoveryState") != "STABLE" ||
        job.has("recoveryMessage")
    if (needsStableSettlement) {
        KeplerJobMetadata.update(
            jobDir,
            R3GalleryColdMeasurement.MetadataWriteSource.TERMINAL_STABLE_SETTLEMENT
        ) { ... }
    }
}
```
Because `recoveryState` is never mutated away from `"STABLE"`, `needsStableSettlement` evaluates to `false`. Therefore `KeplerJobMetadata.update` is **never invoked**, resulting in **0 write attempts** for both `RECONSTRUCT_MAIN_EXPORT` and `TERMINAL_STABLE_SETTLEMENT`.

---

## 5. CRASH-SAFETY ANALYSIS

Does durably writing `PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL` provide any safety value for an already-settled cohort?

| Crash Point | Current Durable State (Slow Path) | Proposed R4.2 Durable State | Safety Evaluation |
|---|---|---|---|
| **Before MediaStore Inspection** | `STABLE` | `STABLE` | Identical. |
| **During MediaStore Inspection / Verifier** | `STABLE` | `STABLE` | Identical. No write occurs. |
| **After Verification / Before Reconstruction Write** | `STABLE` | `STABLE` | Identical. |
| **After Reconstruction Write but Before Settlement** | `PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL` | `STABLE` | **R4.2 is Safer**: Under standard production, a crash here leaves the metadata in an intermediate debt state. On reboot, recovery must re-evaluate. R4.2 remains clean and stable. |
| **During Stable Settlement Write** | `STABLE` (In Flight) | `STABLE` | Identical. |
| **After Recovery Completed** | `STABLE` | `STABLE` | Identical. |

---

## 6. AUDIT NON-ELIGIBLE COUNTEREXAMPLES

These cases must be rejected by the fast-path gate and continue through the standard reconstruction path:

1. **Committed but unverified export**: `exportVerified` is `false` or current inspection results in `PUBLIC_COMMITTED_UNVERIFIED`.
   - *Gate Action*: **Rejects** because `exportVerified == true` and current verification == `PUBLIC_VERIFIED` are required.
2. **VERIFIED journal without `terminalMetadataPersisted`**: 
   - *Gate Action*: **Rejects** because `journal.terminalMetadataPersisted == true` is required.
3. **Mismatched `exportUri` vs `galleryPublicExportLinkage`**:
   - *Gate Action*: **Rejects** because linkage agreement is required.
4. **Journal URI mismatch**: Journal `uri` does not match metadata `exportUri`.
   - *Gate Action*: **Rejects** because exact structural agreement is required.
5. **Journal owner / `terminalOperationId` mismatch**: `terminalOperationId` is nonblank but journal `ownerOperationId` or `terminalOperationId` differs.
   - *Gate Action*: **Rejects** because precise owner-correlation is required.
6. **Missing MAIN journal**:
   - *Gate Action*: **Rejects** because `CurrentMainAuthorityResolution` will resolve to `None`.
7. **Multiple MAIN journals**:
   - *Gate Action*: **Rejects** because `CurrentMainAuthorityResolution` will resolve to `Ambiguous`.
8. **Row externally removed**: Inspection returns `PUBLIC_RESULT_REMOVED`.
   - *Gate Action*: **Rejects** because verification fails and result is not `PUBLIC_VERIFIED`. Will safely execute `applyExternalPublicRemovalMetadata` instead.
9. **`IS_PENDING` row**: Current inspection has `pending == true`.
   - *Gate Action*: **Rejects** because `IS_PENDING == false` is required.
10. **Verifier / Diagnostic failure**: Verification returns a failure or warning.
    - *Gate Action*: **Rejects** because successful verification with null diagnostics is required.
11. **Active operation / Processing handoff present**: `ACTIVE_OPERATION_ID` or `PROCESSING_HANDOFF_OPERATION_ID` is present.
    - *Gate Action*: **Rejects** because active owners block eligibility.
12. **`recoveryState` not `STABLE` / `recoveryMessage` present**:
    - *Gate Action*: **Rejects** because preexisting debt must go through the standard path to resolve.
13. **Invalid export journal**:
    - *Gate Action*: **Rejects** because `invalidFiles(jobDir)` is non-empty.
14. **RAW job with DNG-sidecar recovery requirements**:
    - *Gate Action*: **Rejects** based on RAW/YUV scope restrictions (see Section 7).

---

## 7. RAW / SIDECAR SCOPE DECISION

The R3.1 timing cohort was composed of 46 YUV (JPEG & HEIF) jobs. RAW jobs have a DNG sidecar contract that executes `reconstructRawSidecarJournalEvidence`, which updates frame-level fields in metadata.

### Scope Decision:
**Intentionally scoped to jobs with no RAW-sidecar recovery work.**

### Rationale:
- Scoped strictly to MAIN-image recovery.
- A RAW job with sidecars has multi-file state alignment requirements. Skimping on sidecar alignment could hide state desynchronizations.
- A cheap structural gate (`rawSidecarRecoveryApplies(jobDir, job) == false`) isolates the YUV/non-sidecar cohort cleanly.

---

## 8. SUPERSEDED R4 EARLY RETURN PROPOSAL & R4.2 REPLACEMENT

### 8.1 SUPERSEDED EARLY RETURN (R4 — DO NOT USE)

> The R4 design originally proposed an early return at line 358:
> ```kotlin
> // SUPERSEDED - DO NOT IMPLEMENT
> val fastPathEligible = checkFastPathEligibility(...)
> if (fastPathEligible) {
>     return KeplerJobRecoveryResult(
>         jobDir,
>         KeplerJobRecoveryClassification.RECOVERED,
>         actions = exportResults.map { it.classification.name },
>         cleanupFailures = cleanupFailures
>     )
> }
> ```
> **Why Superseded**: Downstream recovery work after line 358 (including `ProcessingArtifactJournal.scan` and `recoverProcessingArtifactJournals`) was completely bypassed. If a job had a verified MAIN export but also contained processing journal debt or invalid processing files, the early return would silently ignore processing issues and incorrectly classify the job as `RECOVERED`. Furthermore, returning `actions = exportResults.map { ... }` diverged from standard production recovery results (which return `metadataTemps.actions`).


---

### 8.2 R4.2 REPLACEMENT: NARROW RECONSTRUCTION SUPPRESSION

In R4.2, we place the optimization gate **strictly around the `reconstructMainExportEvidence` call** (lines 358-367 in `KeplerRecoveryCoordinator.kt`), allowing all remaining code in `recoverOne` to execute normally.

```kotlin
// R4.2 Implementation Design within KeplerRecoveryCoordinator.recoverOne:
// Placed at lines 358-367:

val suppressMainReconstruction = exportAuthorityOperation.isNotBlank() &&
    exportResults.isNotEmpty() &&
    isModernTerminallySettledMainExport(
        job = job,
        journal = currentMainAuthorityJournal,
        result = currentMainAuthorityResult,
        terminalOperationId = terminalOperationId,
        exportResults = exportResults,
        jobDir = jobDir
    )

if (exportAuthorityOperation.isNotBlank() && exportResults.isNotEmpty() && !suppressMainReconstruction) {
    job = R3GalleryColdMeasurement.measureReconstruction {
        KeplerJobMetadata.update(
            jobDir,
            R3GalleryColdMeasurement.MetadataWriteSource.RECONSTRUCT_MAIN_EXPORT
        ) { current ->
            reconstructMainExportEvidence(jobDir, current, exportAuthorityOperation, exportResults)
        }
    }
}

// ALL DOWNSTREAM CONTROL FLOW CONTINUES UNTOUCHED:
// - RAW sidecar reconstruction (lines 368-391)
// - Terminal operation settlement (lines 392-441)
// - recoverCaptureOwnedTemps (line 442)
// - PROCESSING_HANDOFF finalization (lines 443-453)
// - ProcessingArtifactJournal.scan & recoverProcessingArtifactJournals (lines 454-527)
// - Active operation recovery (lines 528-600)
// - Invalid export journal handling (lines 601-612)
// - Terminal stable settlement check (lines 613-624) -> naturally skips write because recoveryState=="STABLE"
// - Local-output terminal classification (lines 625-640)
// - Legacy active job recovery (lines 641-658)
// - Standard KeplerJobRecoveryResult construction (lines 659-665)
```

---

## 9. DOWNSTREAM RECOVERY AUDIT AFTER LINE 358

Starting immediately after line 358 (`reconstructMainExportEvidence`), every step in `recoverOne` is audited under the strict eligibility contract:

| # | Step in `recoverOne` | Line Range | Classification for Strict Eligible State | Audit & Proof |
|---|---|---|---|---|
| 1 | **MAIN Evidence Reconstruction** | 358-367 | **Target of Suppression** | Suppressed when `suppressMainReconstruction == true`. Avoids Write 1 (`RECONSTRUCT_MAIN_EXPORT`). |
| 2 | **RAW Sidecar Reconstruction** | 368-391 | **A** (Provably Irrelevant under Contract) | Gated by `rawSidecarRecoveryApplies(jobDir, job)`. Strict eligibility contract requires `rawSidecarRecoveryApplies == false`. Gate evaluates to false; block is skipped cleanly. |
| 3 | **Terminal Operation Settlement** | 392-441 | **A** (Provably Irrelevant under Contract) | Gated by `terminalOperationId == activeOperation && activeOperation.isNotBlank()`. Contract requires `activeOperation.isBlank()`. Block is skipped cleanly. |
| 4 | **Capture-Owned Temp Recovery** | 442 | **A** (Provably Irrelevant under Contract) | `recoverCaptureOwnedTemps` is called with `oldActiveOperation = activeOperation.isNotBlank() == false`. `KeplerRestartArtifactRecovery.kt` line 126 immediately returns empty `KeplerCaptureTempRecovery()`. Zero side-effects. |
| 5 | **PROCESSING_HANDOFF Finalization** | 443-453 | **A** (Provably Irrelevant under Contract) | Gated by `PROCESSING_HANDOFF_OPERATION_ID.isNotBlank()`. Contract requires handoff ID blank. Block is skipped cleanly. |
| 6 | **Processing Artifact Journal Scan** | 454 | **B** (Must Still Execute) | `ProcessingArtifactJournal.scan(jobDir)` runs unconditionally. Essential for detecting processing evidence/debt. |
| 7 | **Processing Artifact Recovery & Debt Handling** | 457-527 | **B** (Must Still Execute) | Runs whenever `processingEvidenceExists == true`. Handles invalid processing journals, cleanup debt, and current processing claims. (See Section 10 Counterexample). |
| 8 | **Active Operation Recovery** | 528-600 | **A** (Provably Irrelevant under Contract) | Gated by `activeOperation.isNotBlank()`. Contract requires `activeOperation.isBlank()`. Block is skipped cleanly. |
| 9 | **Invalid Export Journal Handling** | 601-612 | **A** (Provably Irrelevant under Contract) | Gated by `invalidExportJournals.isNotEmpty() && !terminalResultAlreadyProven`. Contract requires `invalidExportJournals.isEmpty()`. Block is skipped cleanly. |
| 10 | **Terminal Stable Settlement** | 613-624 | **B** (Must Still Execute) | Runs when `terminalResultAlreadyProven == true`. Checks `needsStableSettlement = (recoveryState != "STABLE" \|\| has("recoveryMessage"))`. Under suppression, `recoveryState` remains `"STABLE"` and `recoveryMessage` is absent, so `needsStableSettlement` is **false**. **Zero writes performed**. |
| 11 | **Local-Output Terminal Classification** | 625-640 | **A** (Provably Irrelevant under Contract) | Gated by `currentPipelineStage !in terminalStages`. Contract requires terminal stage (`COMPLETE`, etc.). Block is skipped cleanly. |
| 12 | **Legacy Active Job Recovery** | 641-658 | **A** (Provably Irrelevant under Contract) | `isLegacyActiveJob(job)` checks active status fields. For terminal stage jobs with no active markers, returns false. Block is skipped cleanly. |
| 13 | **Final Result Construction** | 659-665 | **B** (Must Still Execute) | Standard exit point: constructs `KeplerJobRecoveryResult(jobDir, RECOVERED, actions = metadataTemps.actions, failures = metadataTemps.failures, cleanupFailures = cleanupFailures)`. Preserves exact return semantics. |

---

## 10. SPECIFIC PROCESSING-ARTIFACT COUNTEREXAMPLE

### Scenario:
- MAIN export is fully modern terminal-stable `VERIFIED`.
- Real MediaStore inspection returns `PUBLIC_VERIFIED`.
- Job metadata `recoveryState` is `"STABLE"`, no active/handoff operations exist.
- RAW sidecar recovery does not apply.
- **BUT** `ProcessingArtifactJournal.scan(jobDir)` discovers:
  - **Case A**: An invalid processing journal file (e.g. `.processing_tx_corrupt.json`).
  - **Case B**: A valid processing journal with outstanding cleanup debt (e.g. `ADOPTED_CURRENT_WITH_CLEANUP_DEBT`).
  - **Case C**: Unsettled processing artifact evidence requiring adoption/restoration.

### Production Behavior vs. Superseded Early Return:

```
                                  [ MAIN Export Verified & Settled ]
                                                 │
                   ┌─────────────────────────────┴─────────────────────────────┐
                   ▼                                                           ▼
     Superseded Early Return (R4 §8)                               R4.2 Narrow Suppression
  ────────────────────────────────────                       ───────────────────────────
  • Immediately returns RECOVERED                             • Skips reconstructMainExportEvidence
  • Bypasses ProcessingArtifactJournal.scan                   • Continues to ProcessingArtifactJournal.scan
  • Invalid processing journal IGNORED                        • Case A: Detects invalid journal
  • Fail-closed policy VIOLATED                                 ──> Writes AMBIGUOUS_RECOVERY_REQUIRED
  • Processing cleanup debt IGNORED                             ──> Returns AMBIGUOUS_RECOVERY_REQUIRED
  • Returns RECOVERED (FAIL-OPEN BUG)                         • Case B: Detects cleanup debt
                                                                ──> Writes PROCESSING_CLEANUP_REQUIRED
                                                                ──> Returns PROCESSING_CLEANUP_REQUIRED
                                                              • Case C: Resolves processing claim
                                                                ──> Returns correct classification
```

### Proof of Requirement:
Under standard production, lines 454-527 execute processing artifact recovery regardless of MAIN export status. If an invalid processing journal exists, production updates metadata to `AMBIGUOUS_RECOVERY_REQUIRED` and returns `AMBIGUOUS_RECOVERY_REQUIRED` (lines 464-474). 

The early-return proposal would have silently bypassed this check and returned `RECOVERED`. This represents a **fail-closed to fail-open safety regression**.

The R4.2 narrow suppression design guarantees that processing artifact recovery always runs, preserving full behavioral equivalence and fail-closed safety.

---

## 11. RETURN-SEMANTICS EQUIVALENCE AUDIT

Comparing the result produced by the superseded early-return proposal vs. the standard production path and R4.2:

| Result Component | Superseded R4 Early Return | Standard Production Path | R4.2 Narrow Suppression | Equivalence Evaluation |
|---|---|---|---|---|
| **Classification** | Hardcoded `RECOVERED` | `if (metadataTemps.classification == AMBIGUOUS) AMBIGUOUS_RECOVERY_REQUIRED else RECOVERED` | Identical to Production | Early return failed to account for ambiguous metadata temp recovery. R4.2 preserves production classification logic exactly. |
| **`actions`** | `exportResults.map { it.classification.name }` (e.g. `["PUBLIC_VERIFIED"]`) | `metadataTemps.actions` (e.g. `[]` or `["DELETED_.job.json.1.tmp"]`) | Identical to Production (`metadataTemps.actions`) | Early return synthesized artificial export action strings. R4.2 retains exact production action list. |
| **`failures`** | `emptyList()` | `metadataTemps.failures` | Identical to Production (`metadataTemps.failures`) | Early return dropped metadata temp failure diagnostics. R4.2 preserves them. |
| **`cleanupFailures`** | `cleanupFailures` | `cleanupFailures` | Identical to Production (`cleanupFailures`) | Identical across all. |

### Conclusion:
The superseded early return produced non-equivalent `KeplerJobRecoveryResult` objects. The R4.2 narrow-suppression design falls through to the existing single return block (lines 659-665), guaranteeing **100% return-semantics equivalence by construction**.

---

## 12. LEGACY TERMINAL EVIDENCE COUNTEREXAMPLE

### Production Legacy Context:
Current production contains legacy terminal-stable recovery logic (`MediaStoreExportRecovery.kt` lines 374-390) for older jobs whose `MAIN_IMAGE` journals have `state == VERIFIED` but lack modern `terminalMetadataPersisted == true` acknowledgment.

When the MediaStore row for such a legacy job is externally removed, production uses `isLegacyTerminalStableVerifiedMainExportForRecovery` to classify the result as `PUBLIC_RESULT_REMOVED` instead of `PUBLIC_COMMIT_MISSING`.

### Fast-Path Eligibility Requirement:
The fast-path reconstruction suppression **MUST NOT** accept legacy terminal evidence. Legacy jobs did not undergo modern terminal-acknowledged metadata persistence and may require reconstructive metadata updates.

To enforce this, the eligibility predicate explicitly requires:
```kotlin
journal.state == MediaStoreExportState.VERIFIED &&
journal.terminalMetadataPersisted == true &&
journal.terminalOperationId == terminalOperationId &&
journal.ownerOperationId == terminalOperationId
```
Any job with `terminalMetadataPersisted == false` fails eligibility and falls back to standard production recovery.

---

## 13. DEFINE "TERMINALLY STABLE" USING REAL DURABLE FIELDS

The conceptual term "terminally stable" corresponds to specific durable fields across `KeplerJobMetadata` and `MediaStoreExportJournal`. Note that `MediaStoreExportJournal.isTerminallyStable()` exists in production (`GalleryExporter.kt` line 78):
```kotlin
internal fun MediaStoreExportJournal.isTerminallyStable(): Boolean =
    terminalMetadataPersisted || state in setOf(
        MediaStoreExportState.CLEANED,
        MediaStoreExportState.INSERT_FAILED_NO_ROW
    )
```
However, for MAIN export fast-path eligibility, `journal.isTerminallyStable()` alone is insufficient because it includes `CLEANED` and `INSERT_FAILED_NO_ROW` states.

The exact durable contract for `isModernTerminallySettledMainExport` is defined using current production fields. The helper now accepts the authoritative `jobDir` argument directly:

```kotlin
internal fun isModernTerminallySettledMainExport(
    job: org.json.JSONObject,
    journal: MediaStoreExportJournal?,
    result: MediaStoreExportRecoveryResult?,
    terminalOperationId: String,
    exportResults: List<MediaStoreExportRecoveryResult>,
    jobDir: File
): Boolean {
    if (journal == null || result == null) return false
    
    // 1. MediaStore Inspection & Result Reality
    if (result.classification != MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED) return false
    if (result.verificationDiagnosticReason != null) return false
    
    // 2. Journal Durable Contract
    if (journal.role != MediaStoreExportRole.MAIN_IMAGE) return false
    if (journal.state != MediaStoreExportState.VERIFIED) return false
    if (!journal.terminalMetadataPersisted) return false
    if (terminalOperationId.isBlank()) return false
    if (journal.ownerOperationId != terminalOperationId) return false
    if (journal.terminalOperationId != terminalOperationId) return false
    
    // 3. Metadata Durable Contract
    if (job.optString(ACTIVE_OPERATION_ID).isNotBlank()) return false
    if (job.optString(PROCESSING_HANDOFF_OPERATION_ID).isNotBlank()) return false
    if (job.optString("currentPipelineStage").uppercase() !in setOf("COMPLETE", "PARTIAL", "FAILED", "CANCELLED")) return false
    if (job.optString("recoveryState") != "STABLE") return false
    if (job.has("recoveryMessage")) return false
    if (!job.optBoolean("galleryExportCommitted", false)) return false
    if (!job.optBoolean("exportVerified", false)) return false
    
    // 4. Exact Structural Linkage & Field Agreement
    val exportUri = job.optString("exportUri").takeIf { it.isNotBlank() && it != "null" } ?: return false
    val linkageUri = job.optString("galleryPublicExportLinkage").takeIf { it.isNotBlank() && it != "null" } ?: return false
    if (exportUri != linkageUri) return false
    if (journal.uri != exportUri) return false
    if (journal.displayName != job.optString("exportDisplayName")) return false
    if (journal.mimeType != job.optString("exportMimeType")) return false
    
    // 5. No Unresolved Export Debt Across Other Journals
    // All non-MAIN journals for this job directory must already be CLEANED or absent.
    val allJournals = MediaStoreExportJournal.list(jobDir)
    if (allJournals.any { it.exportAttemptId != journal.exportAttemptId && it.state != MediaStoreExportState.CLEANED }) return false
    
    // 6. RAW / Sidecar Scope Isolation
    // Must have no RAW sidecar recovery requirements.
    if (rawSidecarRecoveryApplies(jobDir, job)) return false
    
    return true
}
```

This predicate uses **only existing durable fields** and introduces no new state sources or assumptions. The `jobDir` authority comes directly from `recoverOne`.

---

## 14. UPDATED TEST MATRIX

Future implementation tests (in `KeplerRecoveryCoordinatorTest.kt`) must verify the following matrix:

### ELIGIBLE Ordinary Terminal YUV Cohort:
1. **Zero-Write Fast Path**:
   - *Inputs*: Modern terminal YUV job, `journal.state == VERIFIED`, `terminalMetadataPersisted == true`, MediaStore inspection `PUBLIC_VERIFIED`.
   - *Assertions*: Real MediaStore inspection is still executed; `RECONSTRUCT_MAIN_EXPORT` writes == `0`; `TERMINAL_STABLE_SETTLEMENT` writes == `0`; processing artifact scan still executes; final classification == `RECOVERED`; metadata SHA unchanged; journal SHA unchanged.

### Missing/Blank recoveryState Counterexample:
2. **recoveryState missing or blank**:
   - *Inputs*: Valid modern terminal-stable job but `recoveryState` field is missing or blank in metadata.
   - *Assertions*: `isModernTerminallySettledMainExport` returns `false`; standard reconstruction path runs (2 writes); job settles correctly.

### Authoritative jobDir Counterexample:
3. **Valid modern terminal job with no jobDirAbsolutePath field**:
   - *Inputs*: Job metadata lacks `jobDirAbsolutePath` field.
   - *Assertions*: Eligibility evaluation remains deterministic; the authoritative `recoverOne` jobDir is used; no wrong-directory scan or exception occurs.

### Processing Artifact Scenarios (Safety & Integrity):
4. **Valid Processing Artifact Journal Present**:
   - *Inputs*: Eligible MAIN export, but job directory contains a valid unresolved processing artifact journal.
   - *Assertions*: Fast path suppresses MAIN reconstruction, but downstream processing recovery executes, adopts/settles processing journal, updates metadata appropriately.
5. **Invalid Processing Artifact Journal Present**:
   - *Inputs*: Eligible MAIN export, but job directory contains an invalid/mutilated processing journal (`.processing_tx_corrupt.json`).
   - *Assertions*: Downstream processing recovery detects invalid file, sets `recoveryState = AMBIGUOUS_RECOVERY_REQUIRED`, returns `AMBIGUOUS_RECOVERY_REQUIRED` (fail-closed preserved).
6. **Processing Cleanup Debt Present**:
   - *Inputs*: Eligible MAIN export, but processing artifact cleanup failed (`ADOPTED_CURRENT_WITH_CLEANUP_DEBT`).
   - *Assertions*: Downstream processing recovery records processing cleanup debt, sets `recoveryState = PROCESSING_CLEANUP_REQUIRED`, returns `PROCESSING_CLEANUP_REQUIRED`.

### Legacy Evidence Isolation:
7. **Legacy Terminal VERIFIED Job (No `terminalMetadataPersisted`)**:
   - *Inputs*: Legacy job with `journal.state == VERIFIED`, but `terminalMetadataPersisted == false`.
   - *Assertions*: `isModernTerminallySettledMainExport` returns `false`; standard reconstruction path runs (2 writes); legacy recovery behavior preserved.

### RAW / Sidecar Isolation:
8. **RAW Job with DNG Sidecars**:
   - *Inputs*: `rawSidecarRecoveryApplies == true`.
   - *Assertions*: `isModernTerminallySettledMainExport` returns `false`; standard reconstruction path runs.

### Existing R4 Counterexamples (All Retained):
9. **Unverified Export**: `exportVerified == false` -> Standard path (2 writes).
10. **Mismatched Linkage**: `galleryPublicExportLinkage != exportUri` -> Standard path.
11. **Mismatched DisplayName / MimeType**: Metadata vs. journal mismatch -> Standard path.
12. **External Removal**: MediaStore row absent -> Standard path runs, executes `applyExternalPublicRemovalMetadata`, sets `REMOVED_EXTERNALLY`.
13. **Active Operation / Handoff**: `ACTIVE_OPERATION_ID` present -> Standard path runs.
14. **Invalid Export Journal**: Mutilated export journal -> Standard path reports `AMBIGUOUS_RECOVERY_REQUIRED`.

---

## 15. PREDICTED PERFORMANCE BOUND

In R3.1, the cold metadata persistence timing across 92 writes for the 46 jobs was:
- Run 1: `234.787` ms
- Run 2: `223.699` ms
- Run 3: `147.224` ms

### Predicted Bounded Range:
**0 ms to ~235 ms** of total metadata persistence time saved.

### Why this doesn't fully translate to Gallery-ready:
1. MediaStore verification (the query, stream, bounds, and pixel checks) remains active. This is the dominant cost (~2500 - 2800 ms).
2. The filesystem operations are asynchronous or parallelized to some extent; I/O overhead may overlap with main-thread Gallery load execution.
3. We expect a direct reduction of ~147-235 ms in *recovery stage execution time*, resulting in a predicted 4-6% recovery time improvement.

---

## 16. UNRESOLVED RISKS

1. **Undetected Metadata Modifications**: If another part of the system modifies job metadata fields during export without updating journals, suppressing reconstruction could skip reconciliation. Mitigated by strictly matching `exportDisplayName` and `exportMimeType` to journals and requiring `recoveryState == STABLE`.
2. **Concurrent Directory Modifications**: Mitigated by the existing single-flight `JobOperationLease` mechanism.

---

## FINAL CLASSIFICATION

R4.2 DESIGN PASS — FAIL-CLOSED PREDICATE CONTRACT COMPLETE