# R1-S gallery verification source preparation

This note records the production contract traced on 2026-08-31. It is source-side preparation
only; it does not claim a real MediaStore `verified=true` device result.

## Effective production contract

`insertPublicFile` in `GalleryExporter.kt` is the public-row authority:

1. Create a journal before insertion with the requested display name, MIME, collection, and any
   expected byte count/digest/dimensions.
2. Insert into a MediaStore collection with `IS_PENDING=1`.
3. Write the encoded bytes and persist the journal's `CONTENT_WRITTEN` state.
4. Clear `IS_PENDING` and require exactly one updated row. A zero-row result is not a public
   commit; an observed or unknown provider state is preserved as evidence.
5. Persist `PUBLIC_COMMITTED` before content verification.
6. Run `verifyGalleryExportResult` against the exact returned URI and the requested format and
   dimensions.
7. Persist journal state `VERIFIED`. Only then does the export result use the verified-success
   compatibility state.

For restart recovery, `ContextMediaStoreExportRecoveryAccess.inspect` queries the exact URI,
requires a row, reads `IS_PENDING`, and runs the same main-image verifier with the journal MIME
and expected dimensions. A pending row that verifies is committed by recovery before it can be
classified as `PENDING_VERIFIED_AND_COMMITTED`. A non-pending row that does not verify remains
`PUBLIC_COMMITTED_UNVERIFIED`.

The verifier's predicates, in order, are:

- nonblank URI and successful URI parsing;
- a provider query returning a row;
- opening the exact content stream;
- nonempty readable bytes and, when reported, MediaStore size equal to the readable size;
- supported encoded signature: JPEG, PNG, or supported HEIF brands (AVIF is rejected);
- JPEG and PNG require their explicit terminal markers (EOI and IEND respectively). HEIF only
  requires the bounded low-level probe to find a supported `ftyp`/brand declaration and a
  minimum 16-byte stream; that probe does not prove full ISO-BMFF container completeness. For
  HEIF, practical validity is strengthened later by positive bounds decode and sampled pixel
  decode;
- bounds decode with positive width and height;
- sampled pixel decode that returns a nonempty bitmap;
- when an expectation is supplied, matching encoded format and MediaStore MIME;
- nonblank display name with a format-compatible extension, with duplicated generated HEIF
  extensions rejected;
- matching expected dimensions when both expected dimensions are supplied.

The verifier does not independently query `IS_PENDING`, enforce a `content://` scheme/authority,
or inspect journal/terminal metadata. Those are enforced by the surrounding MediaStore insert and
recovery protocol described above. In the Android production path the URI is the URI returned by
MediaStore insertion; the pure verifier also retains its existing `file:` test/source support.
Thus `content://` authority, exact-row existence, and pending-state predicates are provider /
recovery predicates, not encoded-content predicates implemented by the pure verifier.

Journal state, terminal metadata, exact owner/operation linkage, and terminal acknowledgement are
separate durability predicates. Recovery releases an export owner only after the matching terminal
metadata and journal acknowledgement protocol is settled.

## Diagnostic repair and fixtures

`GalleryExportVerificationReason` is a bounded enum attached to each retryable/permanent
verification failure. The first retryable human-readable string remains compatibility-stable, but
the typed reason after retries is the last failed retry predicate, so an early transient reason
cannot mask the final failure. `MediaStoreExportInspection` carries the same read-only code for a
main-image inspection, and `MediaStoreExportRecoveryResult` propagates it for
`PUBLIC_COMMITTED_UNVERIFIED`. It is diagnostic only: it does not participate in classification,
journal transitions, or owner/recovery authority. Exception messages are not part of the code;
verifier and recovery failure text uses bounded categories/class names rather than raw provider
messages or local paths.

`GalleryExportVerificationTest` exercises real verifier layers with deterministic in-memory
encoded data: valid JPEG/PNG, a bounded structurally accepted HEIF ftyp fixture, insufficient HEIF,
bad signature, truncated JPEG/PNG, a valid PNG signature with an invalid body, row absence,
unavailable/open failures, empty content, size mismatch, bounds/pixel failures,
MIME/extension/dimension mismatches, and retry progression whose final typed reason is asserted.
The test source uses platform bounds/pixel decoding for JPEG/PNG; HEIF remains an explicit
injectable host fixture because the host decoder does not provide a production HEIF decode. These
tests do not stand in for a real MediaStore provider.

## Previous U2.2 cohort

`FINAL_REPORT.md` proves the observed classification: all 46 seeded MAIN_IMAGE rows were
`exists=true, pending=false, verified=false`, so recovery correctly entered
`PUBLIC_COMMITTED_PENDING_VERIFICATION`. The report does not contain the bytes/metadata needed to
identify which verifier predicate failed for each row. Therefore the exact payload mismatch is
not proven here. The bounded hypothesis is that the seed represented provider row state but did
not contain a fully valid export satisfying the complete stream/signature/decode/metadata
contract. The new diagnostic code makes the next equivalent source-side run predicate-specific.

Remaining proof requires a later physical-device run over the exact production MediaStore rows,
recording the URI/authority, row existence, `IS_PENDING`, MIME/display name/size, verification
diagnostic code, decoded dimensions/pixel probe, journal state, and terminal acknowledgement. That
device evidence is intentionally pending.
