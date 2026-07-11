package com.projectnuke.keplernightlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProgressTest {
    @Test
    fun intermediateStatusesAreNonterminal() {
        listOf(
            "CAPTURE_COMPLETE: 캡처가 완료되었습니다.",
            "CAPTURE_COMPLETE_PARTIAL: saved 3/6",
            "캡처가 완료되었습니다.",
            "처리가 완료되었습니다.",
            "YUV Fusion V2 failed; falling back to classic V1",
            "Motion 저장 실패, 컬러 프레임은 유지",
            "RAW capture sequence done"
            ,"YUV Fusion V2 failed; falling back to classic V1"
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
            "EXPORT_FAILED: verification failed"
            ,"PIPELINE_CANCELLED: user cancelled"
        ).forEach { assertTrue(it, isTerminalStatus(it)) }
    }
}
