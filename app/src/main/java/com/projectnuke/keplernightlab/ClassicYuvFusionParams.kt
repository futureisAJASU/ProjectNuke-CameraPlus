package com.projectnuke.keplernightlab

import org.json.JSONObject
import java.util.Locale

const val CLASSIC_YUV_FUSION_PARAMS_VERSION = "classic_yuv_v1_2_native_algorithms"

data class ClassicYuvFusionParams(
    val presetName: String,
    val referenceWeight: Float,
    val ghostThreshold: Float,
    val ghostWeight: Float,
    val alignmentRejectThreshold: Float,
    val denoiseStrength: Float,
    val sharpenAmount: Float,
    val localContrastAmount: Float,
    val saturationBoost: Float,
    val shadowLift: Float,
    val highlightRollOff: Float,
    val denoiseAlgorithm: DenoiseAlgorithm = DenoiseAlgorithm.GUIDED,
    val fusionAlgorithm: FusionAlgorithm = FusionAlgorithm.ROBUST_REFERENCE,
    val toneAlgorithm: NativeToneAlgorithm = NativeToneAlgorithm.NATURAL
) {
    fun clamped(): ClassicYuvFusionParams {
        val safePresetName = presetName.uppercase(Locale.US).takeIf {
            it in setOf("NATURAL", "CLEAN", "SHARP", "NIGHT_BRIGHT")
        } ?: "NATURAL"
        val defaults = ClassicYuvFusionPreset.fromName(safePresetName).params
        return copy(
            presetName = safePresetName,
            referenceWeight = referenceWeight
                .finiteOr(defaults.referenceWeight)
                .coerceIn(1.0f, 3.0f),
            ghostThreshold = ghostThreshold
                .finiteOr(defaults.ghostThreshold)
                .coerceIn(12f, 80f),
            ghostWeight = ghostWeight
                .finiteOr(defaults.ghostWeight)
                .coerceIn(0.01f, 0.35f),
            alignmentRejectThreshold = alignmentRejectThreshold
                .finiteOr(defaults.alignmentRejectThreshold)
                .coerceIn(0.08f, 0.40f),
            denoiseStrength = denoiseStrength
                .finiteOr(defaults.denoiseStrength)
                .coerceIn(0f, 0.55f),
            sharpenAmount = sharpenAmount
                .finiteOr(defaults.sharpenAmount)
                .coerceIn(0f, 0.55f),
            localContrastAmount = localContrastAmount
                .finiteOr(defaults.localContrastAmount)
                .coerceIn(0f, 0.18f),
            saturationBoost = saturationBoost
                .finiteOr(defaults.saturationBoost)
                .coerceIn(0.90f, 1.18f),
            shadowLift = shadowLift
                .finiteOr(defaults.shadowLift)
                .coerceIn(0f, 0.12f),
            highlightRollOff = highlightRollOff
                .finiteOr(defaults.highlightRollOff)
                .coerceIn(0f, 0.35f)
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("presetName", presetName)
        .put("referenceWeight", referenceWeight.toDouble())
        .put("ghostThreshold", ghostThreshold.toDouble())
        .put("ghostWeight", ghostWeight.toDouble())
        .put("alignmentRejectThreshold", alignmentRejectThreshold.toDouble())
        .put("denoiseStrength", denoiseStrength.toDouble())
        .put("sharpenAmount", sharpenAmount.toDouble())
        .put("localContrastAmount", localContrastAmount.toDouble())
        .put("saturationBoost", saturationBoost.toDouble())
        .put("shadowLift", shadowLift.toDouble())
        .put("highlightRollOff", highlightRollOff.toDouble())
        .put("denoiseAlgorithm", denoiseAlgorithm.name)
        .put("fusionAlgorithm", fusionAlgorithm.name)
        .put("toneAlgorithm", toneAlgorithm.name)
}

enum class ClassicYuvFusionPreset(
    val displayName: String,
    val params: ClassicYuvFusionParams
) {
    NATURAL(
        "Natural",
        ClassicYuvFusionParams("NATURAL", 1.5f, 34f, 0.04f, 0.20f, 0.14f, 0.10f, 0.020f, 1.01f, 0.014f, 0.08f,
            DenoiseAlgorithm.GUIDED, FusionAlgorithm.ROBUST_REFERENCE, NativeToneAlgorithm.NATURAL)
    ),
    CLEAN(
        "Clean",
        ClassicYuvFusionParams("CLEAN", 1.65f, 27f, 0.025f, 0.17f, 0.38f, 0.20f, 0.025f, 1.01f, 0.022f, 0.14f,
            DenoiseAlgorithm.WAVELET, FusionAlgorithm.NOISE_AWARE, NativeToneAlgorithm.LOCAL_COMPRESSION)
    ),
    SHARP(
        "Sharp",
        ClassicYuvFusionParams("SHARP", 1.4f, 36f, 0.06f, 0.22f, 0.14f, 0.42f, 0.10f, 1.06f, 0.012f, 0.08f,
            DenoiseAlgorithm.BILATERAL, FusionAlgorithm.ROBUST_REFERENCE, NativeToneAlgorithm.NATURAL)
    ),
    NIGHT_BRIGHT(
        "Night Bright",
        ClassicYuvFusionParams("NIGHT_BRIGHT", 1.55f, 31f, 0.04f, 0.19f, 0.30f, 0.24f, 0.055f, 1.04f, 0.075f, 0.24f,
            DenoiseAlgorithm.WAVELET, FusionAlgorithm.MOTION_SAFE, NativeToneAlgorithm.NIGHT)
    );

    companion object {
        fun fromName(name: String?): ClassicYuvFusionPreset =
            entries.firstOrNull { it.name == name?.uppercase(Locale.US) } ?: NATURAL
    }
}

fun loadClassicYuvFusionParams(job: JSONObject): ClassicYuvFusionParams {
    val json = job.optJSONObject("fusionParams")
        ?: job.optJSONObject("processingParams")
        ?: return ClassicYuvFusionPreset.fromName(
            job.optString("fusionPresetName", job.optString("processingPresetName", "NATURAL"))
        ).params
    return runCatching {
        val preset = ClassicYuvFusionPreset.fromName(
            json.optString("presetName", job.optString("fusionPresetName", "NATURAL"))
        )
        ClassicYuvFusionParams(
            presetName = json.optString("presetName", preset.name),
            referenceWeight = json.requireFiniteFloat("referenceWeight"),
            ghostThreshold = json.requireFiniteFloat("ghostThreshold"),
            ghostWeight = json.requireFiniteFloat("ghostWeight"),
            alignmentRejectThreshold = json.requireFiniteFloat("alignmentRejectThreshold"),
            denoiseStrength = json.requireFiniteFloat("denoiseStrength"),
            sharpenAmount = json.requireFiniteFloat("sharpenAmount"),
            localContrastAmount = json.requireFiniteFloat("localContrastAmount"),
            saturationBoost = json.requireFiniteFloat("saturationBoost"),
            shadowLift = json.requireFiniteFloat("shadowLift"),
            highlightRollOff = json.requireFiniteFloat("highlightRollOff"),
            denoiseAlgorithm = runCatching {
                    DenoiseAlgorithm.valueOf(json.optString("denoiseAlgorithm", DenoiseAlgorithm.GUIDED.name))
                }.getOrDefault(DenoiseAlgorithm.GUIDED),
            fusionAlgorithm = runCatching {
                FusionAlgorithm.valueOf(json.optString("fusionAlgorithm", FusionAlgorithm.ROBUST_REFERENCE.name))
            }.getOrDefault(FusionAlgorithm.ROBUST_REFERENCE),
            toneAlgorithm = runCatching {
                NativeToneAlgorithm.valueOf(json.optString("toneAlgorithm", NativeToneAlgorithm.NATURAL.name))
            }.getOrDefault(NativeToneAlgorithm.NATURAL)
        ).clamped()
    }.getOrElse { ClassicYuvFusionPreset.NATURAL.params }
}

private fun JSONObject.requireFiniteFloat(key: String): Float {
    require(has(key) && !isNull(key)) { "Missing $key" }
    return optDouble(key, Double.NaN).also { require(it.isFinite()) }.toFloat()
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
