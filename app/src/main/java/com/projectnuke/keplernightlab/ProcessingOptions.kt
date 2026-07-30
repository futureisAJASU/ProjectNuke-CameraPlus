package com.projectnuke.keplernightlab

enum class CaptureMode(
    val label: String,
    val description: String
) {
    MULTI_FRAME(
        label = "멀티프레임",
        description = "여러 장을 정렬·합성해 노이즈를 줄이고 디테일을 복원합니다."
    ),
    SINGLE_FRAME(
        label = "일반 사진",
        description = "12M YUV 한 장만 촬영하고 같은 ISP 후처리 프로필을 적용해 저장합니다."
    )
}

data class ProcessingSettings(
    val presetName: String,
    val denoiseStrength: Float,
    val sharpenAmount: Float,
    val localContrastAmount: Float
) {
    fun normalized(): ProcessingSettings {
        val preset = ClassicYuvFusionPreset.fromName(presetName)
        val base = preset.params
        return copy(
            presetName = preset.name,
            denoiseStrength = denoiseStrength.finiteOr(base.denoiseStrength).coerceIn(0f, 0.55f),
            sharpenAmount = sharpenAmount.finiteOr(base.sharpenAmount).coerceIn(0f, 0.55f),
            localContrastAmount = localContrastAmount
                .finiteOr(base.localContrastAmount)
                .coerceIn(0f, 0.18f)
        )
    }

    fun resolvedParams(): ClassicYuvFusionParams {
        val normalized = normalized()
        val base = ClassicYuvFusionPreset.fromName(normalized.presetName).params
        return base.copy(
            presetName = normalized.presetName,
            denoiseStrength = normalized.denoiseStrength,
            sharpenAmount = normalized.sharpenAmount,
            localContrastAmount = normalized.localContrastAmount
        ).clamped()
    }

    companion object {
        fun fromPreset(preset: ClassicYuvFusionPreset): ProcessingSettings = ProcessingSettings(
            presetName = preset.name,
            denoiseStrength = preset.params.denoiseStrength,
            sharpenAmount = preset.params.sharpenAmount,
            localContrastAmount = preset.params.localContrastAmount
        )

        fun default(): ProcessingSettings = fromPreset(ClassicYuvFusionPreset.NATURAL)
    }
}

internal fun effectiveFramePlan(captureMode: CaptureMode, estimated: FramePlan): FramePlan =
    if (captureMode == CaptureMode.SINGLE_FRAME) {
        FramePlan(
            framesToCapture = 1,
            maxFrames = 1,
            reason = "Single-frame capture"
        )
    } else {
        estimated
    }
internal fun isSingleFrameJob(job: org.json.JSONObject): Boolean =
    job.optString("captureMode").equals(CaptureMode.SINGLE_FRAME.name, ignoreCase = true) ||
        job.optString("jobType").equals("YUV_SINGLE_FRAME", ignoreCase = true) ||
        job.optInt("requestedFrames", 0) == 1 &&
        job.optInt("savedFrames", 0) == 1 &&
        job.optString("fusionEngine").startsWith("single_yuv_isp")

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
