# docs/R4_TERMINAL_STABLE_VERIFIED_FAST_PATH_DESIGN.md

## START HEAD
`4aab900b2128d1214ce12d43a4766bcce3b84a43`

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

2. **Step 1: MediaStore Recovery & Inspection**:
   - `recoverMediaStoreExportJournals()` (lines 238-242) is executed.
   - The `MAIN_IMAGE` export journal is inspected.
   - `ContextMediaStoreExportRecoveryAccess.inspect()` runs a MediaStore content provider query (to check `IS_PENDING` which is false) and `verifyGalleryExportResult()` (pixels/bounds checked).
   - This returns a `MediaStoreExportRecoveryResult` with classification `PUBLIC_VERIFIED`.

3. **Step 2: resolveCurrentMainAuthority()**:
   - Selects the matching journal and recovery result, returning `CurrentMainAuthorityResolution.Resolved`.
   - `recoveredMainVerified` == `true` and `recoveredMainCommit` == `true`.

4. **Step 3: State Mutation Phase 1 (Reconstruct MAIN Export Write)**:
   - Since `exportAuthorityOperation` is nonblank (resolves to `TERMINAL_OPERATION_ID`), and `exportResults` is not empty, `reconstructMainExportEvidence` is called within a `KeplerJobMetadata.update(..., RECONSTRUCT_MAIN_EXPORT)` block (lines 358-366).
   - **Write 1 (RECONSTRUCT_MAIN_EXPORT)** occurs because `recoveryState` changes from `"STABLE"` to `"PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL"`, and `recoveryMessage` is added ("이전 실행이 종료된 후 공개 내보내기 결과를 확인했습니다.").
   - Even though other fields like `galleryExportCommitted`, `exportVerified`, and `exportUri` remain equal, the overall JSON serialized content has changed (`contentChanged` is true).

5. **Step 4: State Mutation Phase 2 (Terminal Stable Settlement Write)**:
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

We design the narrowest, most defensive eligibility predicate to safely authorize the fast-path bypass. 

### Field-by-Field/Condition Audit:

| Condition / Field Check | Required? | Justification / Proof of Requirement |
|---|---|---|
| `no ACTIVE_OPERATION_ID` | **Yes** | A live or dead active operation owner means the job is not yet stable; it must proceed through the slow/reconstruction path. |
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
| `journal.isTerminallyStable() == true` | **Yes** | Re-verifies terminal stable acknowledgment. Requires `terminalMetadataPersisted == true`. |
| `terminal operation / journal correlation is exact` | **Yes** | If `terminalOperationId` exists, the journal's `ownerOperationId` must match it exactly. |
| `current MediaStore inspection exists` | **Yes** | We must have completed a live MediaStore provider inspection in the current session. |
| `IS_PENDING == false` | **Yes** | The row must be committed and not pending in MediaStore. |
| `current verification result == PUBLIC_VERIFIED` | **Yes** | Real pixels and bounds must be verified successfully right now. |
| `verification diagnostic == null` | **Yes** | Any validation warning/failure blocks fast-path. |
| `no ambiguity / missing-commit / unverified result` | **Yes** | Any unresolved classification forces slow path. |
| `no unresolved current export journal debt` | **Yes** | No other journals for this job (including sidecars) can be terminally unstable. |

### Conclusion:
Every single item above is **strictly required**. Any uncertainty or mismatch automatically fails closed, reverting the job to the standard recovery state-machine path.

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
The net effect of the slow path is a cycle where `recoveryState` and `recoveryMessage` undergo a transient mutation and are then reverted back to `"STABLE"` and `absent` respectively. No other fields are altered because they already equal the values retrieved from the current resolved authority journal.

### Equivalence:
Byte-for-byte equivalence of the JSON payload is **guaranteed** because:
1. All static/factual fields match the current journal fields by contract.
2. The transient fields (`recoveryState` / `recoveryMessage`) return to their baseline.
3. The schema and formatting output of `JSONObject.toString(2)` are fully deterministic.

---

## 5. CRASH-SAFETY ANALYSIS

Does durably writing `PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL` provide any safety value for an already-settled cohort?

### Crash Points comparison:

| Crash Point | Current Durable State (Slow Path) | Proposed Fast Path Durable State | Safety Evaluation |
|---|---|---|---|
| **Before MediaStore Inspection** | `STABLE` | `STABLE` | Identical. |
| **During MediaStore Inspection / Verifier** | `STABLE` | `STABLE` | Identical. No write occurs. |
| **After Verification / Before Reconstruction Write** | `STABLE` | `STABLE` | Identical. |
| **After Reconstruction Write but Before Settlement** | `PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL` | `STABLE` | **Fast Path is Safer**: Under the current slow path, a crash here leaves the metadata in an intermediate debt state. On reboot, recovery must re-evaluate. Fast path remains clean and stable. |
| **During Stable Settlement Write** | `STABLE` (In Flight) | `STABLE` | Identical. |
| **After Recovery Completed** | `STABLE` | `STABLE` | Identical. |

### Conclusion:
For an already terminal-stable and terminal-acknowledged job, writing `PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL` introduces *risk* rather than *safety*. A crash during the transient cycle leaves the metadata modified. The proposed fast-path bypass keeps the metadata durably `STABLE` at all times, making it **equal or safer** than the current path.

---

## 6. AUDIT NON-ELIGIBLE COUNTEREXAMPLES

These cases must be rejected by the fast-path gate and continue through the existing slow/recovery paths:

1. **Committed but unverified export**: `exportVerified` is `false` or current inspection results in `PUBLIC_COMMITTED_UNVERIFIED`.
   - *Gate Action*: **Rejects** because `exportVerified == true` and current verification == `PUBLIC_VERIFIED` are required.
2. **VERIFIED journal without `terminalMetadataPersisted`**: 
   - *Gate Action*: **Rejects** because `journal.isTerminallyStable() == true` is required.
3. **Mismatched `exportUri` vs `galleryPublicExportLinkage`**:
   - *Gate Action*: **Rejects** because linkage agreement is required.
4. **Journal URI mismatch**: Journal `uri` does not match metadata `exportUri`.
   - *Gate Action*: **Rejects** because exact structural agreement is required.
5. **Journal owner / `terminalOperationId` mismatch**: `terminalOperationId` is nonblank but journal `ownerOperationId` differs.
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
    - *Gate Action*: **Rejects** because preexisting debt must go through the slow path to resolve.
13. **Invalid export journal**:
    - *Gate Action*: **Rejects** because `invalidFiles(jobDir)` is non-empty.
14. **RAW job with DNG-sidecar recovery requirements**:
    - *Gate Action*: **Rejects** based on RAW/YUV scope restrictions (see Section 7).

---

## 7. RAW / SIDECAR SCOPE DECISION

The R3.1 timing cohort was composed of 46 YUV (JPEG & HEIF) jobs. RAW jobs have a DNG sidecar contract that executes `reconstructRawSidecarJournalEvidence`, which updates frame-level fields in metadata.

### Scope Decision:
**Option B: Intentionally scoped to jobs with no RAW-sidecar recovery work.**

### Rationale:
- The fast path is safest and most predictable when focused strictly on MAIN-image recovery.
- A RAW job with sidecars has multi-file state alignment requirements. Skimping on sidecar alignment could hide state desynchronizations.
- A cheap structural gate (`rawSidecarRecoveryApplies(...) == false`) can be used to isolate the YUV/non-sidecar cohort cleanly.

### Broadening Evidence Required:
To broaden this to RAW jobs in the future, we would need to:
1. Prove that `reconstructRawSidecarJournalEvidence` results in zero content-changing writes for already-terminal RAW jobs.
2. Verify that sidecar journals are also fully terminally stable (`terminalMetadataPersisted == true`) and align byte-for-byte.

---

## 8. FUTURE IMPLEMENTATION LOCATION

The fast path logic should be placed in `KeplerRecoveryCoordinator.recoverOne` right before the first reconstruction gate check:

```kotlin
// Proposed location within KeplerRecoveryCoordinator.recoverOne:
// After: CurrentMainAuthorityResolution.Resolved has verified the MAIN export, and
// MediaStore verification has completed successfully with PUBLIC_VERIFIED.
// Before: reconstructMainExportEvidence is executed.

val fastPathEligible = checkFastPathEligibility(
    job,
    currentMainAuthorityJournal,
    currentMainAuthorityResult,
    exportResults,
    invalidExportJournals
)

if (fastPathEligible) {
    // Skip reconstructMainExportEvidence and terminal stable settlement.
    // Settle RECOVERED directly with zero writes.
    return KeplerJobRecoveryResult(
        jobDir,
        KeplerJobRecoveryClassification.RECOVERED,
        actions = exportResults.map { it.classification.name },
        cleanupFailures = cleanupFailures
    )
}
```

---

## 9. FUTURE DESIGN TEST MATRIX

Deterministic host tests (in `KeplerRecoveryCoordinatorTest.kt`) must verify the following properties:

1. **Eligible Job**:
   - Inputs: Already-terminal stable YUV job, verified journal, terminal acked, present in MediaStore, passes verification.
   - Assertions: `0` `RECONSTRUCT_MAIN_EXPORT` writes, `0` `TERMINAL_STABLE_SETTLEMENT` writes, metadata SHA unchanged, journal SHA unchanged, classification == `RECOVERED`.
2. **Current Verification Enforcement**:
   - Verify that real MediaStore query and bounds verification are *still executed* even on the fast path.
3. **Mismatched Linkage**:
   - `galleryPublicExportLinkage != exportUri` -> Runs standard path (2 writes).
4. **Mismatched DisplayName / MimeType**:
   - Metadata display name != Journal display name -> Runs standard path to align fields.
5. **External Removal**:
   - Row missing in MediaStore -> Standard path runs, executes `applyExternalPublicRemovalMetadata`, sets `REMOVED_EXTERNALLY`.
6. **Committed Unverified**:
   - Journal state is verified but current verification result is unverified -> Standard path runs, records debt.
7. **Active Operation / Processing Handoff**:
   - Presence of `ACTIVE_OPERATION_ID` -> Standard path runs to resolve owner.
8. **Invalid Journal**:
   - Mutilated export journal json -> Standard path runs, reports `AMBIGUOUS_RECOVERY_REQUIRED`.

*To strengthen the polarity regression from R3.1:* Ensure tests assert the specific `R3GalleryColdMeasurement` write-source counters (`RECONSTRUCT_MAIN_EXPORT`, `TERMINAL_STABLE_SETTLEMENT`) are exactly 0 for eligible runs.

---

## 10. PREDICTED PERFORMANCE BOUND

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

## 11. UNRESOLVED RISKS

1. **Undetected Metadata Modifications**: If another part of the system modifies job metadata fields (e.g., custom user tags, gallery categories) during export without updating journals, a fast-path bypass could skip reconciliation. This is mitigated by restricting eligibility strictly to `recoveryState == STABLE` and matching `exportDisplayName`/`exportMimeType` to journals.
2. **Concurrent Directory Modifications**: If a concurrent background process modifies the job directory during the recovery scan, a race could occur. This is mitigated by the existing single-flight `JobOperationLease` mechanism.

---

## R4 DESIGN PASS — SAFE BOUNDED IMPLEMENTATION DEFINED
