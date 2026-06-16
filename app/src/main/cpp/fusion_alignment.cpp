#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>

namespace {

float madAt(
    const uint8_t* ref,
    const uint8_t* cand,
    int width,
    int height,
    int stride,
    int dx,
    int dy,
    int step
) {
    const int margin = std::abs(dx) + std::abs(dy) + 8;
    const int left = margin;
    const int top = margin;
    const int right = width - margin;
    const int bottom = height - margin;
    if (right <= left || bottom <= top) return std::numeric_limits<float>::max();
    int64_t diff = 0;
    int64_t count = 0;
    for (int y = top; y < bottom; y += step) {
        for (int x = left; x < right; x += step) {
            const int a = ref[static_cast<int64_t>(y) * stride + x];
            const int b = cand[static_cast<int64_t>(y + dy) * stride + x + dx];
            diff += std::abs(a - b);
            ++count;
        }
    }
    if (count <= 0) return std::numeric_limits<float>::max();
    return static_cast<float>(diff) / static_cast<float>(count) / 255.0f;
}

float parabolicOffset(float left, float center, float right) {
    const float denom = left - 2.0f * center + right;
    if (std::abs(denom) < 1e-6f) return 0.0f;
    return std::clamp(0.5f * (left - right) / denom, -0.5f, 0.5f);
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_projectnuke_keplernightlab_NativeFusionAlignment_nativeAlignLumaFrames(
    JNIEnv* env,
    jobject,
    jbyteArray reference,
    jbyteArray candidate,
    jint width,
    jint height,
    jint rowStride,
    jint searchRadius
) {
    if (reference == nullptr || candidate == nullptr ||
        width < 32 || height < 32 || rowStride < width ||
        searchRadius < 1 || searchRadius > 96) {
        return nullptr;
    }
    const int64_t required =
        static_cast<int64_t>(rowStride) * (height - 1LL) + width;
    if (required <= 0 || required > std::numeric_limits<jsize>::max()) return nullptr;
    if (env->GetArrayLength(reference) < required ||
        env->GetArrayLength(candidate) < required) {
        return nullptr;
    }

    jbyte* refBytes = env->GetByteArrayElements(reference, nullptr);
    jbyte* candBytes = env->GetByteArrayElements(candidate, nullptr);
    if (refBytes == nullptr || candBytes == nullptr) {
        if (refBytes != nullptr) env->ReleaseByteArrayElements(reference, refBytes, JNI_ABORT);
        if (candBytes != nullptr) env->ReleaseByteArrayElements(candidate, candBytes, JNI_ABORT);
        return nullptr;
    }
    const auto* ref = reinterpret_cast<const uint8_t*>(refBytes);
    const auto* cand = reinterpret_cast<const uint8_t*>(candBytes);

    int bestDx = 0;
    int bestDy = 0;
    float bestScore = std::numeric_limits<float>::max();
    for (int dy = -searchRadius; dy <= searchRadius; dy += 4) {
        for (int dx = -searchRadius; dx <= searchRadius; dx += 4) {
            const float score = madAt(ref, cand, width, height, rowStride, dx, dy, 4);
            if (score < bestScore) {
                bestScore = score;
                bestDx = dx;
                bestDy = dy;
            }
        }
    }
    for (int dy = std::max(-searchRadius, bestDy - 3);
         dy <= std::min(searchRadius, bestDy + 3);
         ++dy) {
        for (int dx = std::max(-searchRadius, bestDx - 3);
             dx <= std::min(searchRadius, bestDx + 3);
             ++dx) {
            const float score = madAt(ref, cand, width, height, rowStride, dx, dy, 3);
            if (score < bestScore) {
                bestScore = score;
                bestDx = dx;
                bestDy = dy;
            }
        }
    }

    const float left = madAt(ref, cand, width, height, rowStride, bestDx - 1, bestDy, 3);
    const float right = madAt(ref, cand, width, height, rowStride, bestDx + 1, bestDy, 3);
    const float up = madAt(ref, cand, width, height, rowStride, bestDx, bestDy - 1, 3);
    const float down = madAt(ref, cand, width, height, rowStride, bestDx, bestDy + 1, 3);
    const float subX = parabolicOffset(left, bestScore, right);
    const float subY = parabolicOffset(up, bestScore, down);
    const float confidence = std::clamp(1.0f - bestScore / 0.20f, 0.0f, 1.0f);
    const float usedSubpixel =
        (std::abs(subX) > 0.01f || std::abs(subY) > 0.01f) ? 1.0f : 0.0f;
    const float values[9] = {
        bestDx + subX,
        bestDy + subY,
        static_cast<float>(bestDx),
        static_cast<float>(bestDy),
        subX,
        subY,
        bestScore,
        confidence,
        usedSubpixel
    };

    env->ReleaseByteArrayElements(reference, refBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(candidate, candBytes, JNI_ABORT);
    jfloatArray result = env->NewFloatArray(9);
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, 9, values);
    return env->ExceptionCheck() ? nullptr : result;
}
