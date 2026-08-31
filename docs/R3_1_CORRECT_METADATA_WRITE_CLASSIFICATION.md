# R3.1 corrected metadata-write classification

Status: `R3.1 CLOSED - CORRECTED TERMINAL-VERIFIED COLD RECOVERY MEASURED`

Baseline: `31a7140c9d31db2d8d45e27af0e95af70bfeaedb`
Device: `SM-S921N` / Galaxy S24 / Android 16
Application: `com.projectnuke.keplernightlab`
Cohort: `7108da30-de03-4f5a-b5d2-9470767dd4b1`

## Correction

The original R3 metadata classification had a Boolean-polarity defect:
`originalText == serialized` was passed to a parameter interpreted as
`contentChanging`. Consequently, the original R3 same-content/content-changing
labels and rewrite-time attribution were invalid. The original R3 timing,
MediaStore verification, and terminal/durability evidence remain valid. Its
primary table also displayed rewrite timing in the run #2/#3 count cells; this
report supplies the corrected table.

The corrected implementation derives `contentChanged` from
`originalText != serialized`, uses an explicit source tag, and has a host
regression test covering both equal and unequal text. No equality check, write
suppression, recovery policy, terminal semantics, MediaStore policy, or Gallery
behavior was changed.

## Cohort and timing contract

One production cohort was created once and reused for all three runs: 46 jobs,
23 JPEG and 23 native HEIF, using the real MediaStore provider and production
export/verifier path. Before timing: terminal 46/46, `exportVerified` 46/46,
diagnostic null 46/46, MAIN journal `VERIFIED` 46/46,
`terminalMetadataPersisted` 46/46, `IS_PENDING=0`, and recovery debt 0.

Durations use `SystemClock.elapsedRealtimeNanos()`. Process start is
`MainActivity.onCreate` after `super`; recovery starts at the production scan;
recovery ends after root recovery and cache cleanup; Gallery-ready is after the
production Gallery load publishes its jobs. The total is process start through
Gallery-ready. Each run used host `adb shell am force-stop` and the same launch
route; app data, MediaStore rows, filesystem caches, and reboot were not used.

## Corrected cold runs

All values are milliseconds. Each run ended terminal 46/46, verified 46/46,
diagnostic null 46/46, pending 0, debt 0, and unchanged baseline metadata and
journal SHA-256 values.

| Run | Cold Gallery ready | Start to recovery | Recovery | MediaStore inspection | JPEG verification | HEIF verification | Post-recovery to ready | Metadata writes | Content-changing | Same-content | Metadata persistence | Other | Journal | Terminal metadata |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| #1 | 4148.925 | 25.543 | 3845.873 | 2818.870 (46/46/0) | 497.993 / 23 | 2320.877 / 23 | 277.509 | 92 | 92 | 0 | 234.787 | 0 | 0 | 0 |
| #2 | 4020.564 | 23.013 | 3747.233 | 2767.660 (46/46/0) | 489.826 / 23 | 2277.834 / 23 | 250.317 | 92 | 92 | 0 | 223.699 | 0 | 0 | 0 |
| #3 | 3593.052 | 15.735 | 3343.638 | 2519.242 (46/46/0) | 367.626 / 23 | 2151.616 / 23 | 233.680 | 92 | 92 | 0 | 147.224 | 0 | 0 | 0 |

MediaStore per-export inspection median/p95/max was #1 `67.214/132.467/165.110`,
#2 `66.305/124.684/177.210`, and #3 `48.428/103.335/183.236`. The verifier
aggregate was respectively `2666.260`, `2626.844`, and `2355.519` ms. Query,
stream, bounds, and sampled-pixel aggregate timings remain available in the
device result records and preserve the R3 measurement boundary.

## Direct write-source attribution

| Source | Run #1 | Run #2 | Run #3 |
|---|---:|---:|---:|
| `RECONSTRUCT_MAIN_EXPORT` attempts / changing / same / ms | 46 / 46 / 0 / 98.902 | 46 / 46 / 0 / 133.444 | 46 / 46 / 0 / 79.089 |
| `TERMINAL_STABLE_SETTLEMENT` attempts / changing / same / ms | 46 / 46 / 0 / 135.885 | 46 / 46 / 0 / 90.255 | 46 / 46 / 0 / 68.135 |
| `GALLERY_STORAGE_SUMMARY` attempts / changing / same / ms | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| `OTHER` attempts / changing / same / ms | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |

The exact reconciliation is 92 total attempts = 46 reconstruction + 46
terminal settlement for every run; 92 content-changing = 46 + 46; and zero
same-content = 0 + 0. `reconstructionWriteAttempts=46` each run. Journal writes
and separately measured terminal metadata writes were both zero.

This directly verifies the suspected 46 x 2 cycle for the already-terminal
cohort: every job performed A, reconstruct metadata write, and B, terminal
stable-settlement metadata write. No job performed C, Gallery storage-summary
metadata write. Final SHA equality proves only the final durable state; it is
not used to classify either intermediate write as a no-op. The 92 writes are
transient content-changing recovery-state churn whose final durable state
returns to the prepared baseline.

## Median and classification

Median cold Gallery-ready: `4020.564 ms`.
Median recovery: `3747.233 ms`.
Median MediaStore inspection: `2767.660 ms`.
Median content-changing metadata persistence: `223.699 ms`.
Median true same-content persistence: `0 ms`.
Median residual recovery after verification and content-changing persistence:
`755.874 ms`.

Per-run recovery shares (MediaStore / content-changing persistence / other)
were approximately #1 `73.29% / 6.10% / 20.61%`, #2 `73.86% / 5.97% /
20.17%`, and #3 `75.35% / 4.40% / 20.25%`.

Corrected conclusion:

- No true same-content persistence was observed; no-op write suppression is not
  justified by this measurement.
- The 92 writes were persistent across all three cold starts, but were
  transient content-changing recovery-state writes, not byte-identical writes.
- MediaStore reverification is the largest measured cost.
- The lower-risk next design is a bounded terminal-stable recovery semantic
  fast-path/design review for already-terminal VERIFIED jobs, before U2.3.
  This pass implements neither that fast path nor any optimization.

Historical comparison remains semantic: prior valid references were about
2.98 s process-cold total, 2.50 s recovery, and 2.087 s post-U2.1 recovery.
R3.1 claims no speedup.

## Validation and cleanup

`compileDebugKotlin`, `compileDebugUnitTestKotlin`, full host unit tests,
rerun full host unit tests, lint, assemble, and the real-device instrumentation
setup all passed; the first offline connected-task attempt was blocked before
execution by an uncached UTP artifact and the online retry passed. The three
device runs and exact cleanup passed. Cleanup removed only this cohort's 46
MediaStore rows, 46 job directories, and R3/R3.1 control/result evidence.
No unrelated user media, jobs, or pre-existing bundles were touched.

Source/test changes are measurement-only: corrected polarity, explicit source
attribution, a deterministic polarity regression test, and this report.
