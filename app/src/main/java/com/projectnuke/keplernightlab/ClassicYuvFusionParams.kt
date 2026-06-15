package com.projectnuke.keplernightlab

import org.json.JSONObject

const val CLASSIC_YUV_FUSION_PARAMS_VERSION = "classic_yuv_v1_1"

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
    val highlightRollOff: Float
) {
    fun clamped(): ClassicYuvFusionParams {
        val safePresetName = presetName.uppercase().takeIf {
            it in setOf("NATURAL", "CLEAN", "SHARP", "NIGHT_BRIGHT")
        } ?: "NATURAL"
        return copy(
        presetName = safePresetName,
        referenceWeight = referenceWeight.coerceIn(1.0f, 3.0f),
        ghostThreshold = ghostThreshold.coerceIn(12f, 80f),
        ghostWeight = ghostWeight.coerceIn(0.01f, 0.35f),
        alignmentRejectThreshold = alignmentRejectThreshold.coerceIn(0.08f, 0.40f),
        denoiseStrength = denoiseStrength.coerceIn(0f, 0.55f),
        sharpenAmount = sharpenAmount.coerceIn(0f, 0.55f),
        localContrastAmount = localContrastAmount.coerceIn(0f, 0.18f),
        saturationBoost = saturationBoost.coerceIn(0.90f, 1.18f),
        shadowLift = shadowLift.coerceIn(0f, 0.12f),
        highlightRollOff = highlightRollOff.coerceIn(0f, 0.35f)
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
}

enum class ClassicYuvFusionPreset(
    val displayName: String,
    val params: ClassicYuvFusionParams
) {
    NATURAL(
        "Natural",
        ClassicYuvFusionParams("NATURAL", 1.5f, 34f, 0.05f, 0.20f, 0.24f, 0.24f, 0.035f, 1.02f, 0.018f, 0.10f)
    ),
    CLEAN(
        "Clean",
        ClassicYuvFusionParams("CLEAN", 1.65f, 27f, 0.025f, 0.17f, 0.38f, 0.20f, 0.025f, 1.01f, 0.022f, 0.14f)
    ),
    SHARP(
        "Sharp",
        ClassicYuvFusionParams("SHARP", 1.4f, 36f, 0.06f, 0.22f, 0.14f, 0.42f, 0.10f, 1.06f, 0.012f, 0.08f)
    ),
    NIGHT_BRIGHT(
        "Night Bright",
        ClassicYuvFusionParams("NIGHT_BRIGHT", 1.55f, 31f, 0.04f, 0.19f, 0.30f, 0.24f, 0.055f, 1.04f, 0.075f, 0.24f)
    );

    companion object {
        fun fromName(name: String?): ClassicYuvFusionPreset =
            entries.firstOrNull { it.name == name?.uppercase() } ?: NATURAL
    }
}

fun loadClassicYuvFusionParams(job: JSONObject): ClassicYuvFusionParams {
    val json = job.optJSONObject("fusionParams")
        ?: return ClassicYuvFusionPreset.NATURAL.params
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
            highlightRollOff = json.requireFiniteFloat("highlightRollOff")
        ).clamped()
    }.getOrElse { ClassicYuvFusionPreset.NATURAL.params }
}

private fun JSONObject.requireFiniteFloat(key: String): Float {
    require(has(key) && !isNull(key)) { "Missing $key" }
    return optDouble(key, Double.NaN).also { require(it.isFinite()) }.toFloat()
}
