#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct Alignment {
    int proxyDx = 0;
    int proxyDy = 0;
    int rawDx = 0;
    int rawDy = 0;
    float confidence = 1.0f;
};

std::string getString(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

bool readRaw16(const std::string& path, int width, int height, std::vector<uint16_t>& out, std::string& error) {
    const int64_t count = static_cast<int64_t>(width) * height;
    const int64_t bytes = count * 2;
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) {
        error = "open failed: " + path;
        return false;
    }
    const auto size = input.tellg();
    if (size < bytes) {
        error = "raw16 too small: " + path;
        return false;
    }
    input.seekg(0, std::ios::beg);
    out.assign(static_cast<size_t>(count), 0);
    std::vector<uint8_t> buffer(1024 * 1024);
    size_t pixel = 0;
    int carry = -1;
    while (pixel < out.size() && input) {
        input.read(reinterpret_cast<char*>(buffer.data()), static_cast<std::streamsize>(buffer.size()));
        const std::streamsize got = input.gcount();
        if (got <= 0) break;
        size_t i = 0;
        if (carry >= 0 && i < static_cast<size_t>(got)) {
            out[pixel++] = static_cast<uint16_t>((static_cast<int>(buffer[i++]) << 8) | carry);
            carry = -1;
        }
        while (i + 1 < static_cast<size_t>(got) && pixel < out.size()) {
            const int lo = buffer[i++];
            const int hi = buffer[i++];
            out[pixel++] = static_cast<uint16_t>((hi << 8) | lo);
        }
        if (i < static_cast<size_t>(got) && pixel < out.size()) {
            carry = buffer[i];
        }
    }
    if (pixel < out.size()) {
        error = "short read: " + path;
        return false;
    }
    return true;
}

bool writeRaw16(const std::string& path, const std::vector<uint16_t>& raw, std::string& error) {
    std::ofstream output(path, std::ios::binary);
    if (!output) {
        error = "output open failed: " + path;
        return false;
    }
    std::vector<uint8_t> row(1024 * 1024);
    size_t offset = 0;
    while (offset < raw.size()) {
        const size_t count = std::min(raw.size() - offset, row.size() / 2);
        size_t out = 0;
        for (size_t i = 0; i < count; ++i) {
            const uint16_t v = raw[offset + i];
            row[out++] = static_cast<uint8_t>(v & 0xFF);
            row[out++] = static_cast<uint8_t>((v >> 8) & 0xFF);
        }
        output.write(reinterpret_cast<const char*>(row.data()), static_cast<std::streamsize>(out));
        offset += count;
    }
    return output.good();
}

std::vector<float> buildLumaProxy(
    const std::vector<uint16_t>& raw,
    int width,
    int height,
    int downscale,
    int blackLevel,
    float exposureScale
) {
    const int proxyW = width / downscale;
    const int proxyH = height / downscale;
    std::vector<float> proxy(static_cast<size_t>(proxyW) * proxyH, 0.0f);
    for (int py = 0; py < proxyH; ++py) {
        for (int px = 0; px < proxyW; ++px) {
            double sum = 0.0;
            int n = 0;
            for (int yy = 0; yy < downscale; ++yy) {
                const int y = py * downscale + yy;
                for (int xx = 0; xx < downscale; ++xx) {
                    const int x = px * downscale + xx;
                    const int v = std::max(0, static_cast<int>(raw[static_cast<size_t>(y) * width + x]) - blackLevel);
                    sum += static_cast<double>(v) * exposureScale;
                    ++n;
                }
            }
            proxy[static_cast<size_t>(py) * proxyW + px] = n > 0 ? static_cast<float>(sum / n) : 0.0f;
        }
    }
    return proxy;
}

Alignment alignProxy(const std::vector<float>& ref, const std::vector<float>& cur, int w, int h, int radius, int downscale) {
    double bestSad = std::numeric_limits<double>::max();
    double bestNorm = 1.0;
    int bestDx = 0;
    int bestDy = 0;
    for (int dy = -radius; dy <= radius; ++dy) {
        const int y0 = std::max(0, -dy);
        const int y1 = std::min(h, h - dy);
        for (int dx = -radius; dx <= radius; ++dx) {
            const int x0 = std::max(0, -dx);
            const int x1 = std::min(w, w - dx);
            if (x1 <= x0 || y1 <= y0) continue;
            double sad = 0.0;
            double norm = 0.0;
            int count = 0;
            for (int y = y0; y < y1; ++y) {
                const size_t refRow = static_cast<size_t>(y) * w;
                const size_t curRow = static_cast<size_t>(y + dy) * w;
                for (int x = x0; x < x1; ++x) {
                    const float a = ref[refRow + x];
                    const float b = cur[curRow + x + dx];
                    sad += std::abs(static_cast<double>(a - b));
                    norm += std::abs(static_cast<double>(a)) + 1.0;
                    ++count;
                }
            }
            if (count > 0 && sad < bestSad) {
                bestSad = sad;
                bestNorm = norm;
                bestDx = dx;
                bestDy = dy;
            }
        }
    }
    Alignment out;
    out.proxyDx = bestDx;
    out.proxyDy = bestDy;
    out.rawDx = bestDx * downscale;
    out.rawDy = bestDy * downscale;
    if ((out.rawDx & 1) != 0) out.rawDx += out.rawDx > 0 ? -1 : 1;
    if ((out.rawDy & 1) != 0) out.rawDy += out.rawDy > 0 ? -1 : 1;
    out.confidence = static_cast<float>(1.0 / (1.0 + bestSad / std::max(1.0, bestNorm)));
    return out;
}

bool writeAlignmentJson(
    const std::string& path,
    const std::vector<Alignment>& alignments,
    int downscale,
    int searchRadius,
    int referenceIndex,
    std::string& error
) {
    std::ofstream output(path);
    if (!output) {
        error = "alignment json open failed: " + path;
        return false;
    }
    output << "{\n";
    output << "  \"type\": \"NATIVE_GLOBAL_SHIFT_V0_1\",\n";
    output << "  \"downscale\": " << downscale << ",\n";
    output << "  \"searchRadius\": " << searchRadius << ",\n";
    output << "  \"referenceIndex\": " << referenceIndex << ",\n";
    output << "  \"cfaPhasePreserved\": true,\n";
    output << "  \"mergedRawFormat\": \"black_level_subtracted_compact_raw16\",\n";
    output << "  \"frames\": [\n";
    for (size_t i = 0; i < alignments.size(); ++i) {
        const auto& a = alignments[i];
        output << "    {\"index\": " << i
               << ", \"proxyDx\": " << a.proxyDx
               << ", \"proxyDy\": " << a.proxyDy
               << ", \"rawDx\": " << a.rawDx
               << ", \"rawDy\": " << a.rawDy
               << ", \"confidence\": " << a.confidence << "}";
        if (i + 1 < alignments.size()) output << ",";
        output << "\n";
    }
    output << "  ]\n";
    output << "}\n";
    return output.good();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_projectnuke_keplernightlab_NativeRawEngine_alignAndMergeRaw16(
    JNIEnv* env,
    jobject,
    jobjectArray framePaths,
    jfloatArray exposureScalesArray,
    jfloatArray frameWeightsArray,
    jint width,
    jint height,
    jint,
    jint blackLevel,
    jint whiteLevel,
    jint referenceIndex,
    jint downscale,
    jint searchRadius,
    jstring outputMergedRawPath,
    jstring outputAlignmentJsonPath
) {
    try {
        const int frameCount = env->GetArrayLength(framePaths);
        if (frameCount < 2) return env->NewStringUTF("ERROR: need at least 2 frames");
        if (width <= 0 || height <= 0) return env->NewStringUTF("ERROR: invalid dimensions");
        if (downscale < 2) downscale = 8;
        const int proxyW = width / downscale;
        const int proxyH = height / downscale;
        if (proxyW < 16 || proxyH < 16) return env->NewStringUTF("ERROR: proxy too small");
        if (referenceIndex < 0 || referenceIndex >= frameCount) referenceIndex = 0;

        std::vector<std::string> paths;
        paths.reserve(frameCount);
        for (int i = 0; i < frameCount; ++i) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(framePaths, i));
            paths.push_back(getString(env, value));
            env->DeleteLocalRef(value);
        }

        std::vector<float> exposureScales(frameCount, 1.0f);
        std::vector<float> frameWeights(frameCount, 1.0f);
        env->GetFloatArrayRegion(exposureScalesArray, 0, std::min(frameCount, env->GetArrayLength(exposureScalesArray)), exposureScales.data());
        env->GetFloatArrayRegion(frameWeightsArray, 0, std::min(frameCount, env->GetArrayLength(frameWeightsArray)), frameWeights.data());

        const int64_t pixels64 = static_cast<int64_t>(width) * height;
        if (pixels64 <= 0 || pixels64 > 120000000LL) return env->NewStringUTF("ERROR: image too large for native v0.1");
        const size_t pixels = static_cast<size_t>(pixels64);
        std::vector<std::vector<float>> proxies;
        proxies.reserve(frameCount);
        std::vector<uint16_t> raw;
        std::string error;
        for (int i = 0; i < frameCount; ++i) {
            if (!readRaw16(paths[i], width, height, raw, error)) {
                return env->NewStringUTF(("ERROR: " + error).c_str());
            }
            proxies.push_back(buildLumaProxy(raw, width, height, downscale, blackLevel, exposureScales[i]));
        }

        std::vector<Alignment> alignments(static_cast<size_t>(frameCount));
        for (int i = 0; i < frameCount; ++i) {
            if (i == referenceIndex) {
                alignments[i] = Alignment{};
            } else {
                alignments[i] = alignProxy(proxies[referenceIndex], proxies[i], proxyW, proxyH, searchRadius, downscale);
            }
        }

        std::vector<float> acc(pixels, 0.0f);
        std::vector<float> weightAcc(pixels, 0.0f);
        for (int i = 0; i < frameCount; ++i) {
            if (!readRaw16(paths[i], width, height, raw, error)) {
                return env->NewStringUTF(("ERROR: " + error).c_str());
            }
            const auto& a = alignments[i];
            const float weight = std::max(0.0f, frameWeights[i]) * std::max(0.05f, a.confidence);
            for (int y = 0; y < height; ++y) {
                const int sy = y + a.rawDy;
                if (sy < 0 || sy >= height) continue;
                const size_t outRow = static_cast<size_t>(y) * width;
                const size_t inRow = static_cast<size_t>(sy) * width;
                for (int x = 0; x < width; ++x) {
                    const int sx = x + a.rawDx;
                    if (sx < 0 || sx >= width) continue;
                    const int v = std::max(0, static_cast<int>(raw[inRow + sx]) - blackLevel);
                    const float normalized = static_cast<float>(v) * exposureScales[i];
                    const size_t p = outRow + x;
                    acc[p] += normalized * weight;
                    weightAcc[p] += weight;
                }
            }
        }

        const int maxOut = std::max(1, static_cast<int>(whiteLevel) - static_cast<int>(blackLevel));
        std::vector<uint16_t> merged(pixels, 0);
        for (size_t p = 0; p < pixels; ++p) {
            if (weightAcc[p] <= 0.0f) {
                merged[p] = 0;
            } else {
                const int v = static_cast<int>(std::lround(acc[p] / weightAcc[p]));
                merged[p] = static_cast<uint16_t>(std::clamp(v, 0, maxOut));
            }
        }

        const std::string mergedPath = getString(env, outputMergedRawPath);
        const std::string alignmentPath = getString(env, outputAlignmentJsonPath);
        if (!writeRaw16(mergedPath, merged, error)) return env->NewStringUTF(("ERROR: " + error).c_str());
        if (!writeAlignmentJson(alignmentPath, alignments, downscale, searchRadius, referenceIndex, error)) {
            return env->NewStringUTF(("ERROR: " + error).c_str());
        }
        std::ostringstream status;
        status << "OK: native alignment+merge complete, frames=" << frameCount;
        return env->NewStringUTF(status.str().c_str());
    } catch (const std::bad_alloc&) {
        return env->NewStringUTF("ERROR: native out of memory");
    } catch (const std::exception& e) {
        std::string message = std::string("ERROR: ") + e.what();
        return env->NewStringUTF(message.c_str());
    } catch (...) {
        return env->NewStringUTF("ERROR: unknown native failure");
    }
}
