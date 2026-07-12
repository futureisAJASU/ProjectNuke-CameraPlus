package com.projectnuke.keplernightlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProgressTest {
    @Test
    fun intermediateStatusesAreNonterminal() {
        listOf(
            "CAPTURE_COMPLETE: capture stage complete",
            "CAPTURE_COMPLETE_PARTIAL: saved 3/6",
            "캡처가 완료되었습니다.",
            "처리가 완료되었습니다.",
            "YUV Fusion V2 failed; falling back to classic V1",
            "YUV Fusion V2 dry-run failed; falling back to classic V1",
            "Motion 저장 실패, 컬러 프레임은 유지",
            "Motion 저장 실패, YUV는 유지",
            "RAW capture sequence done"
        ).forEach { assertFalse(it, isTerminalStatus(it)) }
    }

    @Test
    fun explicitFinalStatusesAreTerminal() {
        listOf(
            "PIPELINE_COMPLETE: Saved HEIF",
            "PIPELINE_COMPLETE_PARTIAL: Saved JPEG",
            "PIPELINE_FAILED: export failed",
            "CAPTURE_FAILED: camera error",
            "CAPTURE_TIMEOUT: Capture timeout",
            "EXPORT_FAILED: verification failed",
            "PIPELINE_CANCELLED: user cancelled",
            "PIPELINE_FAILED: RAW_SENSOR 출력 크기를 찾지 못함",
            "PIPELINE_FAILED: DNG 저장 실패: Pictures 폴더가 null임"
        ).forEach { assertTrue(it, isTerminalStatus(it)) }
    }

    @Test
    fun recoverableWarningsDoNotParseAsFailed() {
        val fallback = CaptureProgressState(stage = CaptureStage.PROCESSING)
        listOf(
            "YUV Fusion V2 failed; falling back to classic V1",
            "YUV Fusion V2 dry-run failed; falling back to classic V1",
            "Motion 저장 실패, 컬러 프레임은 유지",
            "Motion 저장 실패, YUV는 유지"
        ).forEach { assertTrue(it, parseCaptureProgress(it, fallback).stage != CaptureStage.FAILED) }
    }

    @Test
    fun explicitFailuresParseAsFailed() {
        val fallback = CaptureProgressState(stage = CaptureStage.PROCESSING)
        listOf(
            "PIPELINE_FAILED: export failed",
            "CAPTURE_FAILED: camera error",
            "PROCESS_FAILED: merge failed",
            "EXPORT_FAILED: verification failed"
        ).forEach { assertTrue(it, parseCaptureProgress(it, fallback).stage == CaptureStage.FAILED) }
    }

    @Test
    fun cancellationParsesAsTerminalCancelled() {
        val parsed = parseCaptureProgress(
            "PIPELINE_CANCELLED: user cancelled",
            CaptureProgressState(stage = CaptureStage.PROCESSING)
        )
        assertTrue(parsed.stage == CaptureStage.CANCELLED)
        assertTrue(parsed.progressPercent == 1f)
    }

    @Test
    fun timedOutJobAcceptsOnlyCommittedLateCompletion() {
        assertFalse(shouldIgnoreCancelledPipelineStatus(true, "PIPELINE_COMPLETE_PARTIAL: Image saved"))
        assertFalse(shouldIgnoreCancelledPipelineStatus(true, "PIPELINE_COMPLETE: Image saved"))
        assertTrue(shouldIgnoreCancelledPipelineStatus(true, "PROCESSING: still working"))
        assertTrue(shouldIgnoreCancelledPipelineStatus(true, "PIPELINE_FAILED: stale failure"))
    }

    @Test
    fun activeJobStatusesRemainAccepted() {
        assertFalse(shouldIgnoreCancelledPipelineStatus(false, "PROCESSING: still working"))
    }
}
