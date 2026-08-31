# R3 true process-cold Gallery recovery measurement

Status: `R3 CLOSED — TRUE TERMINAL-VERIFIED COLD RECOVERY MEASURED`

Device: `SM-S921N` / Galaxy S24 / Android 16
Application: `com.projectnuke.keplernightlab`
Cohort: `3b53bea4-9704-4909-926c-64351ec6a705`

## Source and harness

The three runs used one cohort created once under the production
`Pictures/KeplerYuvFusion` root: 46 jobs, 23 JPEG and 23 native HEIF. The
fixture used the production export, verification, journal, terminal metadata,
and Gallery storage-summary paths. The normal storage summary was settled
before the baseline hash was recorded, including the metadata-file-size
self-reference convergence.

The pre-run contract was 46/46 terminal, 46/46 `verified=true`, 46/46
diagnostic `null`, 46/46 journal `VERIFIED`, 46/46
`terminalMetadataPersisted=true`, zero pending, and zero recovery debt. The
same metadata and journal SHA-256 hashes were checked after every timed run.

Timing uses `SystemClock.elapsedRealtimeNanos()`:

- Process start: `MainActivity.onCreate` after `super.onCreate`.
- Recovery start: the first line of the production recovery scan.
- Recovery end: after production root recovery and cache cleanup complete.
- Gallery ready: after the production Gallery load returns and publishes its
  job list.
- Total cold Gallery-ready time: process start through Gallery ready.

Between runs the host used `adb shell am force-stop
com.projectnuke.keplernightlab`, verified no target process was listed where
practical, and launched `MainActivity` with `am start -W`. No app data,
MediaStore data, filesystem caches, or device reboot were used. OS page cache
was therefore allowed to remain warm.

## Primary runs

All times are milliseconds. Verification aggregates are the production
inspection calls, including their existing query, stream, bounds, and sampled
pixel reads. Counts are `attempted / verified=true / verified=false`.

| Run | Cold Gallery ready | Start→recovery | Recovery | Verification | JPEG verification | HEIF verification | Post-recovery→ready | Same-content rewrites | Rewrite time | Content-changing writes | Journal writes | Terminal metadata writes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| #1 | 3622.562 | 19.107 | 3363.721 | 2502.596 (46/46/0) | 409.090 / 23 | 2093.506 / 23 | 239.734 | 92 | 139.134 | 0 | 0 | 0 |
| #2 | 3968.991 | 16.558 | 3701.568 | 2709.373 (46/46/0) | 427.746 / 23 | 2281.627 / 23 | 250.865 | 212.681 | 212.681 | 0 | 0 | 0 |
| #3 | 3490.600 | 18.614 | 3235.001 | 2380.003 (46/46/0) | 399.459 / 23 | 1980.544 / 23 | 236.985 | 180.859 | 180.859 | 0 | 0 | 0 |

Each run ended with terminal 46/46, verified 46/46, pending 0, diagnostic
`null` for 46/46, journal `VERIFIED` 46/46, terminal metadata persisted
46/46, and recovery debt 0. Durable metadata and journal hashes remained
unchanged for the entire cohort.

MediaStore verification detail:

| Run | Verifier aggregate / median / p95 / max | Query aggregate | Content stream aggregate | Bounds aggregate | Sampled-pixel aggregate | Per-export median / p95 / max |
|---|---:|---:|---:|---:|---:|
| #1 | 2365.809 / 48.160 / 104.822 / 212.129 | 192.874 | 182.682 | 151.653 | 1928.735 | 52.808 / 107.579 / 215.295 |
| #2 | 2558.268 / 51.442 / 130.609 / 246.701 | 204.506 | 229.486 | 171.163 | 2047.333 | 56.273 / 136.026 / 251.334 |
| #3 | 2235.143 / 45.151 / 90.404 / 119.913 | 192.719 | 162.174 | 156.664 | 1815.500 | 56.622 / 93.357 / 122.903 |

The per-export JPEG median / p95 / max was respectively 15.080 / 34.260 /
38.709 ms, 16.341 / 30.016 / 40.315 ms, and 15.663 / 23.243 / 43.402 ms.
The HEIF values were 81.337 / 149.417 / 215.295 ms, 88.656 / 138.422 /
251.334 ms, and 83.444 / 119.564 / 122.903 ms.

## Metadata rewrite trace

Every run had 92 same-content metadata writes and zero content-changing
writes. Of the 92, 46 were the recovery `reconstructMainExportEvidence`
updates (`reconstructionWriteAttempts=46`), one per job. The other 46 were
the production Gallery-load `maybePersistStorageMetadata` updates reached by
`readKeplerGalleryJob`. They were included because they occur on the measured
production cold Gallery route; no write was suppressed or equality behavior
changed. Journal writes, terminal metadata writes, and reconstruction-triggered
content mutations were all zero.

The same-content rewrite aggregate was 139.134 ms, 212.681 ms, and 180.859
ms for runs #1/#2/#3. The corresponding reconstruction stage totals were
218.020 ms, 278.876 ms, and 246.985 ms. The rewrite timing is the actual
atomic metadata persistence time, not a synthetic extra write.

## Median and shares

| Metric | Median |
|---|---:|
| Total process-cold Gallery ready | 3622.562 ms |
| Recovery | 3363.721 ms |
| MediaStore verification | 2502.596 ms |
| Same-content metadata rewrite time | 180.859 ms |
| Remaining recovery (median of per-run residuals) | 721.991 ms |

Per-run shares of recovery (verification / same-content rewrite / remaining)
were:

- #1: `74.400% / 4.136% / 21.464%`
- #2: `73.195% / 5.746% / 21.059%`
- #3: `73.570% / 5.591% / 20.839%`

Using the independent median stage values, the shares are `74.400%`
verification, `5.377%` same-content rewrite, and `20.223%` other. The small
non-sum difference between median rows is expected because medians are taken
per metric; the per-run rows are the additive measurements.

## Historical comparison

The timing semantics preserve the historical process-cold Gallery route:
process entry through Gallery usability, with recovery measured as the
production recovery pass inside that route. The current source did not retain
the old U2.0/U2.1 harness as a directly runnable artifact, so comparison is
semantic rather than a claim of byte-for-byte harness identity.

Historical valid reference points were approximately 2.98 s total process-cold
Gallery time, 2.50 s recovery, and 2.087 s recovery after U2.1. R3 reports
measurements only and claims no speedup.

## Evidence classification

**C — BOTH ARE MATERIAL.** Full MediaStore verification is the dominant
measured recovery stage at a 73.195–74.400% share. Same-content metadata
rewrites are persistent across all three true cold starts and cost 139–213 ms
(4.136–5.746%), so they are not negligible even though they are not dominant.

The decision rule therefore recommends bounded redundant-write design/corrective
work first as the lower-risk follow-up, before any U2.3 read-only optimization
work. No such optimization is implemented in R3.

## Cleanup and repository result

The exact R3 MediaStore URIs, exact R3 job directories, and R3 temporary device
evidence were deleted after all assertions. No pre-existing user media or jobs
were touched.

R3 source/test changes are limited to opt-in timing counters, production
boundary hooks, the staged real-MediaStore cohort test, and this report. No
recovery policy, equality check, or write suppression changed.
