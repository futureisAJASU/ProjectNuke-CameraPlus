#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_projectnuke_keplernightlab_NativeFrameQuality_nativeScoreLumaFrame(
    JNIEnv* env,
    jobject,
    jbyteArray luma,
    jint width,
    jint height,
    jint rowStride
) {
    if (luma == nullptr || width <= 0 || height <= 0 || rowStride < width) {
        return nullptr;
    }

    const jsize bufferSize = env->GetArrayLength(luma);
    const int64_t requiredSize =
        static_cast<int64_t>(rowStride) * (height - 1LL) + width;
    if (requiredSize <= 0 ||
        requiredSize > std::numeric_limits<jsize>::max() ||
        bufferSize < requiredSize) {
        return nullptr;
    }

    jboolean isCopy = JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(luma, &isCopy);
    if (bytes == nullptr) {
        return nullptr;
    }

    const auto valueAt = [bytes, rowStride](int x, int y) -> float {
        const auto* data = reinterpret_cast<const uint8_t*>(bytes);
        return static_cast<float>(data[static_cast<int64_t>(y) * rowStride + x]);
    };

    double sum = 0.0;
    double sumSquares = 0.0;
    int64_t shadowCount = 0;
    int64_t highlightCount = 0;
    const int64_t pixelCount = static_cast<int64_t>(width) * height;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const float value = valueAt(x, y);
            sum += value;
            sumSquares += static_cast<double>(value) * value;
            if (value <= 5.0f) ++shadowCount;
            if (value >= 250.0f) ++highlightCount;
        }
    }

    double gradientSum = 0.0;
    int64_t gradientCount = 0;
    for (int y = 1; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            gradientSum += std::abs(valueAt(x + 1, y) - valueAt(x - 1, y));
            gradientSum += std::abs(valueAt(x, y + 1) - valueAt(x, y - 1));
            gradientCount += 2;
        }
    }

    env->ReleaseByteArrayElements(luma, bytes, JNI_ABORT);

    const double mean = sum / static_cast<double>(pixelCount);
    const double variance = std::max(
        0.0,
        sumSquares / static_cast<double>(pixelCount) - mean * mean
    );
    const float metrics[5] = {
        gradientCount > 0
            ? static_cast<float>(gradientSum / static_cast<double>(gradientCount))
            : 0.0f,
        static_cast<float>(mean),
        static_cast<float>(std::sqrt(variance)),
        static_cast<float>(shadowCount) / static_cast<float>(pixelCount),
        static_cast<float>(highlightCount) / static_cast<float>(pixelCount)
    };

    jfloatArray result = env->NewFloatArray(5);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, 5, metrics);
    return env->ExceptionCheck() ? nullptr : result;
}
