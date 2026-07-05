package com.projectnuke.keplernightlab

import java.io.File

internal const val YUV_FUSION_V2_SKELETON_VERSION = "YUV_FUSION_V2_SKELETON"

internal fun processYuvFusionJobV2(
    jobDir: File,
    onStatus: (String) -> Unit
): File {
    val finalFile = processClassicYuvFusionJob(jobDir, onStatus = onStatus)
    runCatching {
        val job = loadJobJson(jobDir)
        job.put("experimentalFusionVersion", YUV_FUSION_V2_SKELETON_VERSION)
            .put("v2SkeletonUsed", true)
        saveJobJson(jobDir, job)
    }
    return finalFile
}
