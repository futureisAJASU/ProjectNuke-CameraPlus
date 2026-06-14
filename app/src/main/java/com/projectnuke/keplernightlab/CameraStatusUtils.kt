package com.projectnuke.keplernightlab

internal fun isTerminalStatus(text: String): Boolean {
    return text.contains("PIPELINE_COMPLETE", ignoreCase = true) ||
        text.contains("CAPTURE_COMPLETE", ignoreCase = true) ||
        text.contains("CAPTURE_COMPLETE_PARTIAL", ignoreCase = true) ||
        text.contains("CAPTURE_TIMEOUT", ignoreCase = true) ||
        text.contains("PROCESS_TIMEOUT", ignoreCase = true) ||
        text.contains("EXPORT_TIMEOUT", ignoreCase = true) ||
        text.contains("PIPELINE_FAILED", ignoreCase = true) ||
        text.contains("complete", ignoreCase = true) ||
        text.contains("failed", ignoreCase = true) ||
        text.contains("timeout", ignoreCase = true) ||
        text.contains("saved to gallery", ignoreCase = true) ||
        text.contains("완료") ||
        text.contains("실패") ||
        text.contains("오류") ||
        text.contains("연결 해제")
}

internal fun shortStatus(text: String): String {
    return text
        .lineSequence()
        .firstOrNull()
        ?.trim()
        ?.take(42)
        ?.ifBlank { null }
        ?: "대기 중"
}
