package com.projectnuke.keplernightlab

import java.util.UUID

/** Immutable identity for one app process. It is deliberately not derived from PID. */
internal object KeplerRuntimeSession {
    val id: String = "${UUID.randomUUID()}-${System.nanoTime()}"
    val startedAt: Long = System.currentTimeMillis()
}

internal enum class KeplerActiveOperationKind {
    CAPTURE_YUV,
    CAPTURE_RAW,
    PROCESSING_YUV,
    PROCESSING_RAW,
    SUPER_RESOLUTION,
    PUBLIC_EXPORT,
    REPROCESS
}

internal const val ACTIVE_RUNTIME_SESSION_ID = "activeRuntimeSessionId"
internal const val ACTIVE_OPERATION_ID = "activeOperationId"
internal const val ACTIVE_OPERATION_KIND = "activeOperationKind"
internal const val ACTIVE_OPERATION_STARTED_AT = "activeOperationStartedAt"
internal const val ACTIVE_OPERATION_UPDATED_AT = "activeOperationUpdatedAt"

internal fun processingOperationKind(mode: String): KeplerActiveOperationKind = when {
    mode.contains("SUPER", ignoreCase = true) -> KeplerActiveOperationKind.SUPER_RESOLUTION
    mode.contains("RAW", ignoreCase = true) -> KeplerActiveOperationKind.PROCESSING_RAW
    else -> KeplerActiveOperationKind.PROCESSING_YUV
}
