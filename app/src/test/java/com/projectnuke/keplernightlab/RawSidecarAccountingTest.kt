package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSidecarAccountingTest {
    @Test
    fun frameManifestKeepsNonPrefixFailuresAndLegacyLocalStatus() {
        val result = RawSidecarExportResult(
            success = true,
            exportedFiles = listOf("content://dng/2"),
            errorMessage = "frame 0: local DNG save failed; frame 1: public verification failed",
            kind = RawSidecarOutcomeKind.PARTIAL,
            expectedCount = 3,
            locallySavedCount = 2,
            publicExportedCount = 1,
            missingFilenames = listOf("frame_00.dng", "frame_01.dng"),
            localFailures = listOf("frame 0: local DNG save failed"),
            publicFailures = listOf("frame_01.dng: public verification failed"),
            requestedCount = 3,
            localFailedCount = 1,
            publicFailedCount = 1,
            frameResults = listOf(
                RawSidecarFrameResult(10, true, null, "LOCAL_SAVE_FAILED", "write failed", "NOT_ATTEMPTED", null, null),
                RawSidecarFrameResult(20, true, "frame_20.dng", "LOCAL_SAVED", null, "PUBLIC_EXPORT_FAILED", null, "hash mismatch"),
                RawSidecarFrameResult(40, true, "frame_40.dng", "LOCAL_SAVED", null, "PUBLIC_EXPORTED", "content://dng/2", null)
            )
        )
        assertEquals(3, result.requestedCount)
        assertEquals(1, result.localFailedCount)
        assertEquals(1, result.publicFailedCount)
        assertEquals(listOf("frame_00.dng", "frame_01.dng"), result.missingFilenames)
        assertTrue(result.frameResults.any { it.publicStatus == "PUBLIC_EXPORT_FAILED" })
    }
}
