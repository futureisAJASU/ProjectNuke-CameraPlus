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
- complete payload marker: JPEG EOI, PNG IEND, or a structurally sufficient HEIF ftyp box;
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

Journal state, terminal metadata, exact owner/operation linkage, and terminal acknowledgement are
separate durability predicates. Recovery releases an export owner only after the matching terminal
metadata and journal acknowledgement protocol is settled.

## Diagnostic repair and fixtures

`GalleryExportVerificationReason` is a bounded enum attached to each retryable/permanent
verification failure. `MediaStoreExportInspection` carries the same read-only code for a main-image
inspection. Exception messages are not part of the code and verifier-generated failure strings use
exception class names only, so local paths are not exposed through export failure text.

`GalleryExportVerificationTest` exercises real verifier layers with deterministic in-memory
encoded data: valid JPEG/PNG, a structurally accepted HEIF ftyp fixture, bad signature, truncated
JPEG/PNG, a valid PNG signature with an invalid body, row absence, unavailable/open failures,
empty content, size mismatch, bounds/pixel failures, MIME/extension/dimension mismatches, and
retry recovery. The test source uses platform bounds/pixel decoding for JPEG/PNG; HEIF remains an
explicit injectable host fixture because the host decoder does not provide a production HEIF
decode.

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
