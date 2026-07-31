#include "kepler_image_engine.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>

namespace {
inline bool finite(float v) { return std::isfinite(v); }
inline float clamp01(float v) { return finite(v) ? std::clamp(v, 0.0f, 1.0f) : 0.0f; }
inline float luminance(jint p) {
    return 0.2126f * static_cast<float>((p >> 16) & 255) +
           0.7152f * static_cast<float>((p >> 8) & 255) +
           0.0722f * static_cast<float>(p & 255);
}
inline int channel(float v) {
    if (!finite(v)) return 0;
    return std::clamp(static_cast<int>(std::lround(v)), 0, 255);
}

float sample(const jint* pixels, int width, int height, int x, int y) {
    x = std::clamp(x, 0, width - 1);
    y = std::clamp(y, 0, height - 1);
    return luminance(pixels[y * width + x]);
}

float denoise_at(const jint* pixels, int width, int height, int x, int y,
                int algorithm, float strength) {
    const float center = sample(pixels, width, height, x, y);
    if (strength <= 0.0f) return center;
    if (algorithm == 2) { // bilateral, spatial and radiometric kernels
        float sum = 0.0f, weight_sum = 0.0f;
        const float sigmaRange = 18.0f + 52.0f * strength;
        for (int dy = -2; dy <= 2; ++dy) for (int dx = -2; dx <= 2; ++dx) {
            const float value = sample(pixels, width, height, x + dx, y + dy);
            const float spatial = std::exp(-(dx * dx + dy * dy) / 5.0f);
            const float delta = value - center;
            const float range = std::exp(-(delta * delta) /
                                         (2.0f * sigmaRange * sigmaRange));
            const float w = spatial * range;
            sum += value * w; weight_sum += w;
        }
        return weight_sum > 0.0f && finite(sum) ? sum / weight_sum : center;
    }
    float mean = 0.0f, mean_sq = 0.0f;
    int count = 0;
    const int radius = algorithm == 1 ? 2 : 1;
    for (int dy = -radius; dy <= radius; ++dy) for (int dx = -radius; dx <= radius; ++dx) {
        const float value = sample(pixels, width, height, x + dx, y + dy);
        mean += value; mean_sq += value * value; ++count;
    }
    mean /= static_cast<float>(count);
    const float variance = std::max(0.0f, mean_sq / static_cast<float>(count) - mean * mean);
    if (algorithm == 1) { // two-scale wavelet-like shrinkage
        const float detail = center - mean;
        const float threshold = 2.0f + 0.16f * std::sqrt(variance);
        const float shrunk = std::copysign(std::max(0.0f, std::fabs(detail) - threshold), detail);
        return mean + detail * (1.0f - strength) + shrunk * strength;
    }
    // Guided-like local linear estimate: covariance/variance preserves edges.
    const float linear = mean + (center - mean) * variance / (variance + 18.0f);
    return center * (1.0f - strength) + linear * strength;
}

float low_pass_at(const jint* pixels, int width, int height, int x, int y,
                  int algorithm, float* variance_out) {
    const int radius = algorithm == 1 ? 2 : 1;
    float sum = 0.0f;
    float sum_sq = 0.0f;
    int count = 0;
    for (int dy = -radius; dy <= radius; ++dy) {
        for (int dx = -radius; dx <= radius; ++dx) {
            const float value = sample(pixels, width, height, x + dx, y + dy);
            sum += value;
            sum_sq += value * value;
            ++count;
        }
    }
    const float mean = sum / static_cast<float>(count);
    const float variance = std::max(
        0.0f, sum_sq / static_cast<float>(count) - mean * mean);
    if (variance_out) *variance_out = finite(variance) ? variance : 0.0f;
    return finite(mean) ? mean : sample(pixels, width, height, x, y);
}

float tone_map(float value, int tone, float shadow_lift, float highlight_rolloff) {
    if (tone < 0) return finite(value) ? std::clamp(value, 0.0f, 255.0f) : 0.0f;
    float x = clamp01(value / 255.0f);
    if (tone == 1) { // local compression: shoulder plus gentle midtone lift
        x = (x * (1.0f + 0.35f * (1.0f - x))) /
            (1.0f + 0.35f * x);
    } else if (tone == 2) { // night: retain black point and protect highlights
        x = std::max(0.0f, x - 0.012f) / 0.988f;
        x = x / (x + 0.22f * (1.0f - x));
        x = std::min(x, 0.985f);
    } else {
        x = x * (0.92f + 0.08f * x) + 0.015f * (1.0f - x);
    }
    const float lifted = x + shadow_lift * (1.0f - x) * (1.0f - x);
    const float rolled = lifted - highlight_rolloff * lifted * lifted * (1.0f - lifted);
    return clamp01(rolled) * 255.0f;
}
}

namespace kepler_image {
void process_argb(const jint* source, jint* output, int width, int height,
                  int denoise, int tone, float denoise_strength,
                  float sharpen, float local_contrast, float shadow_lift,
                  float highlight_rolloff, float saturation, int tile_rows) {
    if (!source || !output || width <= 0 || height <= 0) return;
    denoise = std::clamp(denoise, 0, 2);
    tone = std::clamp(tone, -1, 2);
    denoise_strength = clamp01(denoise_strength);
    sharpen = clamp01(sharpen);
    local_contrast = clamp01(local_contrast);
    shadow_lift = clamp01(shadow_lift);
    highlight_rolloff = clamp01(highlight_rolloff);
    saturation = finite(saturation) ? std::clamp(saturation, 0.0f, 2.0f) : 1.0f;
    if (denoise_strength == 0.0f && sharpen == 0.0f &&
        local_contrast == 0.0f && shadow_lift == 0.0f &&
        highlight_rolloff == 0.0f && saturation == 1.0f && tone == 0) {
        std::copy(source, source + static_cast<size_t>(width) * height, output);
        return;
    }
    const int rows = std::max(1, tile_rows);
    for (int top = 0; top < height; top += rows) {
        const int bottom = std::min(height, top + rows);
        for (int y = top; y < bottom; ++y) for (int x = 0; x < width; ++x) {
            const jint original = source[y * width + x];
            const float center = luminance(original);
            float variance = 0.0f;
            const float filtered = denoise_at(
                source, width, height, x, y, denoise, 1.0f);
            const float denoised = center * (1.0f - denoise_strength) +
                filtered * denoise_strength;
            const float low_pass = low_pass_at(
                source, width, height, x, y, denoise, &variance);
            const float raw_detail = center - low_pass;
            const float noise_floor = 1.5f + 0.22f * std::sqrt(variance);
            const float residual = std::copysign(
                std::max(0.0f, std::fabs(raw_detail) - noise_floor), raw_detail);
            // Detail enhancement is deliberately attenuated after restoration;
            // it cannot algebraically restore the removed center-to-filter gap.
            const float detailGain = std::min(
                0.45f, sharpen * 0.5f * (1.0f - denoise_strength));
            const float contrastResidual = denoised - low_pass;
            const float shaped = denoised + residual * detailGain +
                contrastResidual * std::min(0.35f, local_contrast);
            const float mapped = tone_map(shaped, tone, shadow_lift, highlight_rolloff);
            const float redChroma = static_cast<float>((original >> 16) & 255) - center;
            const float greenChroma = static_cast<float>((original >> 8) & 255) - center;
            const float blueChroma = static_cast<float>(original & 255) - center;
            output[y * width + x] = (0xFF << 24) |
                (channel(mapped + redChroma * saturation) << 16) |
                (channel(mapped + greenChroma * saturation) << 8) |
                channel(mapped + blueChroma * saturation);
        }
    }
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_projectnuke_keplernightlab_NativeImageEngine_nativeProcessArgb(
    JNIEnv* env, jclass, jintArray source, jintArray output, jint width, jint height,
    jint denoise, jint tone, jfloat denoise_strength, jfloat sharpen,
    jfloat local_contrast, jfloat shadow_lift, jfloat highlight_rolloff,
    jfloat saturation, jint tile_rows) {
    if (!source || !output || width <= 0 || height <= 0 ||
        width > 100000 || height > 100000) return;
    const int64_t pixel_count = static_cast<int64_t>(width) *
        static_cast<int64_t>(height);
    if (pixel_count <= 0 || pixel_count > static_cast<int64_t>(std::numeric_limits<jsize>::max()) ||
        pixel_count > static_cast<int64_t>(std::numeric_limits<int>::max())) return;
    const jsize expected = static_cast<jsize>(pixel_count);
    if (expected <= 0 || env->GetArrayLength(source) < expected || env->GetArrayLength(output) < expected) return;
    jint* in = env->GetIntArrayElements(source, nullptr);
    jint* out = env->GetIntArrayElements(output, nullptr);
    if (!in || !out) { if (in) env->ReleaseIntArrayElements(source, in, JNI_ABORT); if (out) env->ReleaseIntArrayElements(output, out, 0); return; }
    kepler_image::process_argb(
        in, out, width, height, denoise, tone, denoise_strength, sharpen,
        local_contrast, shadow_lift, highlight_rolloff, saturation, tile_rows);
    env->ReleaseIntArrayElements(source, in, JNI_ABORT);
    env->ReleaseIntArrayElements(output, out, 0);
}
