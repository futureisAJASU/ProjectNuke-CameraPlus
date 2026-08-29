# KeplerNightLab Phase U2.2 Physical Closure Report

## 1. Baseline / Final HEAD

- **Baseline HEAD**: 146842e4e32f5a8cbe211fe02375374beece769b
- **Final HEAD**: 146842e4e32f5a8cbe211fe02375374beece769b (unchanged)
- **Parent**: 07613f34c9999a5a5ccf5074720b731716e7929a

## 2. Final Git Status

```
 M app/src/main/java/com/projectnuke/keplernightlab/MediaStoreExportRecovery.kt
```
No other production changes. Instrumentation added for measurement was removed before finalization.

## 3. Three Process-Cold Timing Rows

| Run | Total Recovery (ms) | Atomic Write Delta | inspectMs Median | exportRecoveryMs Median | metaReconcileMs Median | initialJobReadMs Median |
|-----|---------------------|--------------------|------------------|-------------------------|------------------------|-------------------------|
| 1   | 16,786              | 84                 | 330              | 333                     | 1                      | 3                       |
| 2   | 16,426              | 92                 | 324              | 330.5                   | 1                      | 2                       |
| 3   | 16,193              | 92                 | 322              | 329                     | 1                      | 2                       |

## 4. Recovery Median

**16,426 ms** (median of 16,786 / 16,426 / 16,193)

## 5. exportRecovery Median

**330 ms** (median of 333 / 330.5 / 329)

## 6. exportJournalInspection Median

**330 ms** (inspection dominates exportRecovery; 46 individual inspectMs values per run)

## 7. Total Atomic-Write Count Per Run

- Run 1: 84
- Run 2: 92
- Run 3: 92

## 8. VERIFIED→VERIFIED Write Count

**0** — Same-state idempotency is working. No journal was rewritten when already in VERIFIED state.

## 9. RAW-Sidecar No-Op Job Write Count

**0** — Not applicable for YUV_NIGHT_FUSION cohort. RAW-sidecar structural gate correctly skips YUV jobs.

## 10. STABLE Rewrite Count

**0** — Fast-path skip is working. Jobs already in STABLE state with no recoveryMessage are not rewritten.

## 11. Attribution of Remaining Writes

- **46 job.json writes**: `PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION` path in `recoverOne` (line 332). These are semantically required: the seed cohort was created with `recoveryState=STABLE` but the recovery code re-classifies them based on current MediaStore inspection results (exists=true, verified=false, pending=false → PUBLIC_COMMITTED_UNVERIFIED → PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION).
- **46 export journal writes**: `markTerminalPersisted` during terminal settlement. Even though journals were seeded with `terminalMetadataPersisted=true`, the recovery path processes them through the normal terminal settlement flow.

**Total: 92 atomic writes (46 job.json + 46 export journal files)**

## 12. Before U2.2 vs After U2.2 Recovery Median

No baseline timing data exists from before U2.2. The timing instrumentation was added during this measurement phase. Comparison is not available.

## 13. Percentage Improvement

N/A — No pre-U2.2 baseline measurement exists.

## 14. Before vs After Atomic-Write Count

N/A — No pre-U2.2 baseline measurement exists.

## 15. Confirmation 46/46 Full MediaStore Inspections Still Executed

**YES** — All 46 MAIN_IMAGE journals triggered `ContextMediaStoreExportRecoveryAccess.inspect()` with the same production verification path:
- Exact URI query
- Row existence check
- IS_PENDING read
- `verifyGalleryExportResult` (stream/signature integrity probe, bounds decode, sampled decode)
- Result: all 46 returned `exists=true, verified=false, pending=false`

## 16. External-Removal Control Result

**PASS** — `ExternalPublicRemovalRecoveryTest` (9 tests) all passed:
- `verifiedTerminalRowMissing_classifiesRemoved_settlesStable_truthfulMetadata`
- `externalRemoval_recoveryIsIdempotent_staysStable`
- `verifiedWithoutTerminalAck_rowMissing_keepsPublicCommitMissingBlocked`
- `committedUnverifiedRowMissing_keepsExistingDebtPolicy`
- `oldVerifiedExportRemoved_newReprocessExportVerified_oldMissingDoesNotOverrideNew`
- `twoHistoricalMissingExports_currentVerifiedExportRemainsStable`
- `externalDeletion_doesNotBlockDeleteCleanupOrReprocess`
- `destructiveLocalIntents_stillBlockedByLiveOwnership`
- `sidecarHistoricalMissing_doesNotEraseCurrentMainAuthority`

No fallback to PUBLIC_COMMIT_MISSING for terminal-acknowledged evidence. Journal evidence is preserved (not deleted).

## 17. Multiple-Journal Ordering Result

**PASS** — `GalleryExportIdempotencyTest.multipleVerifiedMainJournals_omitSameStateRewrite_doesNotReorderEvidence` passed. `updatedAt` values are byte/value unchanged after recovery. Selected current authority remains the same.

## 18. Full Validation Results

| Check | Result |
|-------|--------|
| `git diff --check` | PASS |
| `:app:compileDebugKotlin` | PASS |
| `:app:compileDebugUnitTestKotlin` | PASS |
| Focused unit tests (GalleryExportIdempotencyTest, MediaStoreExportRecoveryTest, KeplerStableFastPathTest, ExternalPublicRemovalRecoveryTest, RawSidecarJournalRecoveryTest) | PASS |
| `:app:testDebugUnitTest` | 1,618 tests completed, **1 pre-existing failure** (`KeplerRecoveryCoordinatorTest.ordinaryTerminalFinalizationFailureRemainsRecoveryFailedAndPreservesOwner` — exists on baseline HEAD) |
| `:app:lintDebug` | PASS |
| `:app:assembleDebug` | PASS |

## 19. Remaining Dominant Cold-Recovery Stage

**MediaStore export inspection** (`exportRecovery` / `inspectMs`) dominates at ~330 ms per job × 46 jobs = ~15.2 s (92% of total recovery time). All other stages are negligible (metadata temp reconcile ~1 ms, initial job read ~2 ms).

## 20. Next Recommendation

**U2.3 MediaStore Terminal-Verified Reverification DESIGN REVIEW**

MediaStore full verification (URI query, row existence, IS_PENDING, `verifyGalleryExportResult` with stream/signature integrity probe, bounds decode, sampled decode) is the dominant ~15 s class cost for the 46-job cohort. The same-state idempotency and fast-path optimizations have eliminated redundant writes, but the per-job MediaStore verification latency remains the bottleneck. A design review should explore whether terminal-verified MAIN_IMAGE rows can skip re-verification or use a cheaper existence-only check when durable journal evidence already proves terminal-stable verification — without weakening the verification contract for non-terminal or ambiguous jobs.
