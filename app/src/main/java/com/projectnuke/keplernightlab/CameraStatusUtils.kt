package com.projectnuke.keplernightlab

internal fun shortStatus(text: String): String {
    return text
        .lineSequence()
        .firstOrNull()
        ?.trim()
        ?.take(42)
        ?.ifBlank { null }
        ?: "대기 중"
}
