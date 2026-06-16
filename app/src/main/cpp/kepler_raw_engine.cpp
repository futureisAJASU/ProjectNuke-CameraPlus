#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace {

struct Alignment {
    int proxyDx = 0;
    int proxyDy = 0;
    int rawDx = 0;
    int rawDy = 0;
    int64_t validProxyPixels = 0;
    float validProxyFraction = 1.0f;
    double sad = 0.0;
    float meanAbsDiff = 0.0f;
    float normalizedError = 0.0f;
    float confidence = 1.0f;
    bool acceptedForMerge = true;
    bool searchBoundaryHit = false;
    std::string rejectReason;
    float finalFrameWeightScale = 1.0f;
};

struct MergeStats {
    int acceptedFrameCount = 0;
    int rejectedFrameCount = 0;
    int lowConfidenceFrameCount = 0;
    int searchBoundaryHitCount = 0;
    uint64_t totalCandidateSamples = 0;
    uint64_t rejectedGhostSamples = 0;
    uint64_t downweightedGhostSamples = 0;
    uint64_t referencePreservedPixelCount = 0;
    uint64_t totalOutputPixels = 0;
    bool tileBasedMerge = true;
    int tileRows = 0;
    int tileCount = 0;
    bool fullFrameAccumulatorsUsed = false;
    bool fullFrameMergedBufferUsed = false;
    uint64_t estimatedTileMergeWorkingSetBytes = 0;
    std::string mergeWarning;
};

struct RawImageGeometry {
    int width;
    int height;
    int blackLevel;
    int whiteLevel;
};

struct MergeOptions {
    int referenceIndex;
};

struct TileMergeContext {
    const std::vector<std::string>& paths;
    const std::vector<float>& exposureScales;
    const std::vector<Alignment>& alignments;
    RawImageGeometry geometry;
    MergeOptions options;
    const std::string& mergedPath;
};

struct PostprocessOptions {
    RawImageGeometry input;
    int cfaPattern;
    int outputWidth;
    int outputHeight;
};

struct TileReadRequest {
    int width;
    int height;
    int startY;
    int rowCount;
};

constexpr int kMergeTileRows = 512;
constexpr int kMergeTileRowsHighRes = 256;
constexpr int64_t kHighResRawMinPixels = 40000000LL;
constexpr float kGhostBaseNoise = 64.0f;
constexpr float kGhostRelativeNoise = 0.12f;
constexpr float kGhostHardRejectMultiplier = 2.5f;
constexpr float kGhostModerateMinWeightScale = 0.15f;
constexpr float kGhostHighlightStart = 0.85f;
constexpr float kGhostHighlightAllowanceScale = 0.35f;

float computeGhostWeight(
    float reference,
    float candidate,
    int maxOut,
    bool& downweighted
) {
    const float signal = std::max(reference, candidate);
    const float highlightAllowance = signal > maxOut * kGhostHighlightStart
        ? (signal - maxOut * kGhostHighlightStart) * kGhostHighlightAllowanceScale
        : 0.0f;
    const float threshold =
        kGhostBaseNoise + kGhostRelativeNoise * signal + highlightAllowance;
    const float difference = std::abs(candidate - reference);
    if (difference <= threshold) return 1.0f;
    if (difference > threshold * kGhostHardRejectMultiplier) return 0.0f;
    const float transition = (difference - threshold) /
        std::max(1.0f, threshold * (kGhostHardRejectMultiplier - 1.0f));
    downweighted = true;
    return std::max(kGhostModerateMinWeightScale, 0.5f * (1.0f - transition));
}

uint16_t finalizeMergedPixel(
    float accumulated,
    float accumulatedWeight,
    uint16_t reference,
    float referenceWeight,
    int maxOut,
    bool& referencePreserved
) {
    referencePreserved = accumulatedWeight <= referenceWeight + 0.0001f;
    if (referencePreserved) return reference;
    const int value = static_cast<int>(
        std::lround(accumulated / accumulatedWeight)
    );
    return static_cast<uint16_t>(std::clamp(value, 0, maxOut));
}

uint8_t clampToByte(float value) {
    return static_cast<uint8_t>(std::clamp(std::lround(value), 0L, 255L));
}

float normalizeRawSample(
    uint16_t sample,
    int blackLevel,
    float exposureScale,
    int maxOut
) {
    const int corrected = std::max(0, static_cast<int>(sample) - blackLevel);
    return std::clamp(
        corrected * exposureScale,
        0.0f,
        static_cast<float>(maxOut)
    );
}

std::string getString(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::string getOptionalString(JNIEnv* env, jstring value) {
    if (!value) return {};
    return getString(env, value);
}

bool fileExistsAndNonEmpty(const std::string& path) {
    if (path.empty()) return false;
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    return input && input.tellg() > 0;
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

bool validateRaw16Stream(
    std::ifstream& input,
    const std::string& path,
    int width,
    int height,
    std::string& error
) {
    input.open(path, std::ios::binary | std::ios::ate);
    if (!input) {
        error = "open failed: " + path;
        return false;
    }
    const int64_t expectedBytes = static_cast<int64_t>(width) * height * 2;
    const auto size = input.tellg();
    if (size < expectedBytes) {
        error = "raw16 too small: " + path;
        return false;
    }
    input.seekg(0, std::ios::beg);
    return true;
}

bool readRaw16Rows(
    std::ifstream& input,
    const std::string& path,
    int width,
    int height,
    int startY,
    int rowCount,
    std::vector<uint16_t>& out,
    std::vector<uint8_t>& ioBytes,
    std::string& error
) {
    if (startY < 0 || rowCount < 0 || startY + rowCount > height) {
        error = "invalid raw16 row range: " + path;
        return false;
    }
    const size_t pixelCount = static_cast<size_t>(width) * rowCount;
    const size_t byteCount = pixelCount * 2;
    out.resize(pixelCount);
    ioBytes.resize(byteCount);
    input.clear();
    input.seekg(static_cast<std::streamoff>(startY) * width * 2, std::ios::beg);
    if (!input) {
        error = "raw16 seek failed: " + path;
        return false;
    }
    input.read(
        reinterpret_cast<char*>(ioBytes.data()),
        static_cast<std::streamsize>(byteCount)
    );
    if (input.gcount() != static_cast<std::streamsize>(byteCount)) {
        error = "raw16 row short read: " + path;
        return false;
    }
    for (size_t i = 0, b = 0; i < pixelCount; ++i, b += 2) {
        out[i] = static_cast<uint16_t>(
            static_cast<uint16_t>(ioBytes[b]) |
            (static_cast<uint16_t>(ioBytes[b + 1]) << 8)
        );
    }
    return true;
}

bool readTileForFrame(
    std::ifstream& input,
    const std::string& path,
    const TileReadRequest& request,
    std::vector<uint16_t>& out,
    std::vector<uint8_t>& ioBytes,
    std::string& error
) {
    return readRaw16Rows(
        input,
        path,
        request.width,
        request.height,
        request.startY,
        request.rowCount,
        out,
        ioBytes,
        error
    );
}

bool writeMergedTile(
    std::ofstream& output,
    const std::vector<uint16_t>& values,
    std::vector<uint8_t>& ioBytes,
    std::string& error
) {
    ioBytes.resize(values.size() * 2);
    for (size_t i = 0, b = 0; i < values.size(); ++i) {
        const uint16_t value = values[i];
        ioBytes[b++] = static_cast<uint8_t>(value & 0xFF);
        ioBytes[b++] = static_cast<uint8_t>((value >> 8) & 0xFF);
    }
    output.write(
        reinterpret_cast<const char*>(ioBytes.data()),
        static_cast<std::streamsize>(ioBytes.size())
    );
    if (!output.good()) {
        error = "merged raw16 tile write failed";
        return false;
    }
    return true;
}

bool mergeRaw16Tiles(
    const TileMergeContext& context,
    MergeStats& stats,
    std::string& error
) {
    const auto& paths = context.paths;
    const auto& exposureScales = context.exposureScales;
    const auto& alignments = context.alignments;
    const int width = context.geometry.width;
    const int height = context.geometry.height;
    const int blackLevel = context.geometry.blackLevel;
    const int whiteLevel = context.geometry.whiteLevel;
    const int referenceIndex = context.options.referenceIndex;
    const std::string& mergedPath = context.mergedPath;
    const int frameCount = static_cast<int>(paths.size());
    std::vector<std::ifstream> streams(static_cast<size_t>(frameCount));
    for (int i = 0; i < frameCount; ++i) {
        if (!validateRaw16Stream(streams[i], paths[i], width, height, error)) {
            return false;
        }
    }

    const int64_t pixels = static_cast<int64_t>(width) * height;
    const int tileRows = pixels >= kHighResRawMinPixels
        ? kMergeTileRowsHighRes
        : kMergeTileRows;
    const int tileCount = (height + tileRows - 1) / tileRows;
    const size_t maxTilePixels =
        static_cast<size_t>(width) * std::min(tileRows, height);
    // reference ushort + acc/weight floats + source ushort + little-endian I/O bytes.
    constexpr uint64_t kBytesPerTilePixel = 2 + 4 + 4 + 2 + 2;
    stats.tileRows = tileRows;
    stats.tileCount = tileCount;
    stats.estimatedTileMergeWorkingSetBytes =
        static_cast<uint64_t>(maxTilePixels) * kBytesPerTilePixel;

    const std::string tempPath = mergedPath + ".tmp";
    std::remove(tempPath.c_str());
    struct TempFileCleanup {
        explicit TempFileCleanup(std::string pathValue) : path(std::move(pathValue)) {}
        TempFileCleanup(const TempFileCleanup&) = delete;
        TempFileCleanup& operator=(const TempFileCleanup&) = delete;
        ~TempFileCleanup() {
            std::remove(path.c_str());
        }
        std::string path;
    } tempCleanup{tempPath};
    std::ofstream output(tempPath, std::ios::binary | std::ios::trunc);
    if (!output) {
        error = "temporary merged raw16 open failed: " + tempPath;
        return false;
    }
    auto fail = [&](const std::string& message) {
        error = message;
        output.close();
        std::remove(tempPath.c_str());
        return false;
    };

    std::vector<uint16_t> referenceTile;
    std::vector<uint16_t> sourceRows;
    std::vector<float> accTile;
    std::vector<float> weightTile;
    std::vector<uint8_t> ioBytes;
    referenceTile.reserve(maxTilePixels);
    sourceRows.reserve(maxTilePixels);
    accTile.reserve(maxTilePixels);
    weightTile.reserve(maxTilePixels);
    ioBytes.reserve(maxTilePixels * 2);

    const int maxOut = std::max(1, whiteLevel - blackLevel);
    const float referenceWeight =
        std::max(1.0f, alignments[referenceIndex].finalFrameWeightScale);
    for (int tileY0 = 0; tileY0 < height; tileY0 += tileRows) {
        const int tileY1 = std::min(height, tileY0 + tileRows);
        const int tileHeight = tileY1 - tileY0;
        const size_t tilePixels = static_cast<size_t>(width) * tileHeight;
        if (!readTileForFrame(
                streams[referenceIndex],
                paths[referenceIndex],
                TileReadRequest{width, height, tileY0, tileHeight},
                sourceRows,
                ioBytes,
                error
            )) {
            return fail(error);
        }
        referenceTile.resize(tilePixels);
        accTile.resize(tilePixels);
        weightTile.resize(tilePixels);
        for (size_t p = 0; p < tilePixels; ++p) {
            const int normalized = static_cast<int>(std::lround(
                normalizeRawSample(
                    sourceRows[p],
                    blackLevel,
                    exposureScales[referenceIndex],
                    maxOut
                )
            ));
            const uint16_t reference = static_cast<uint16_t>(
                std::clamp(normalized, 0, maxOut)
            );
            referenceTile[p] = reference;
            accTile[p] = static_cast<float>(reference) * referenceWeight;
            weightTile[p] = referenceWeight;
        }

        for (int i = 0; i < frameCount; ++i) {
            if (i == referenceIndex || !alignments[i].acceptedForMerge) continue;
            const auto& alignment = alignments[i];
            const float frameWeight = alignment.finalFrameWeightScale;
            if (frameWeight <= 0.0f) continue;
            const int sourceY0 = std::max(0, tileY0 + alignment.rawDy);
            const int sourceY1 = std::min(height, tileY1 + alignment.rawDy);
            if (sourceY1 <= sourceY0) continue;
            if (!readTileForFrame(
                    streams[i],
                    paths[i],
                    TileReadRequest{width, height, sourceY0, sourceY1 - sourceY0},
                    sourceRows,
                    ioBytes,
                    error
                )) {
                return fail(error);
            }
            for (int y = tileY0; y < tileY1; ++y) {
                const int sy = y + alignment.rawDy;
                if (sy < sourceY0 || sy >= sourceY1) continue;
                const size_t tileRow = static_cast<size_t>(y - tileY0) * width;
                const size_t sourceRow = static_cast<size_t>(sy - sourceY0) * width;
                for (int x = 0; x < width; ++x) {
                    const int sx = x + alignment.rawDx;
                    if (sx < 0 || sx >= width) continue;
                    const size_t p = tileRow + x;
                    const float normalized = normalizeRawSample(
                        sourceRows[sourceRow + sx],
                        blackLevel,
                        exposureScales[i],
                        maxOut
                    );
                    const float reference = static_cast<float>(referenceTile[p]);
                    bool downweighted = false;
                    const float ghostWeight = computeGhostWeight(
                        reference,
                        normalized,
                        maxOut,
                        downweighted
                    );
                    ++stats.totalCandidateSamples;
                    if (ghostWeight > 0.0f) {
                        accTile[p] += normalized * frameWeight * ghostWeight;
                        weightTile[p] += frameWeight * ghostWeight;
                        if (downweighted) {
                            ++stats.downweightedGhostSamples;
                        }
                    } else {
                        ++stats.rejectedGhostSamples;
                    }
                }
            }
        }

        for (size_t p = 0; p < tilePixels; ++p) {
            bool referencePreserved = false;
            referenceTile[p] = finalizeMergedPixel(
                accTile[p],
                weightTile[p],
                referenceTile[p],
                referenceWeight,
                maxOut,
                referencePreserved
            );
            if (referencePreserved) {
                ++stats.referencePreservedPixelCount;
            }
        }
        if (!writeMergedTile(output, referenceTile, ioBytes, error)) {
            return fail(error);
        }
    }

    output.close();
    if (!output.good()) return fail("temporary merged raw16 close failed");
    std::ifstream verify(tempPath, std::ios::binary | std::ios::ate);
    const int64_t expectedBytes = pixels * 2;
    if (!verify || verify.tellg() != expectedBytes) {
        verify.close();
        return fail("temporary merged raw16 size verification failed");
    }
    verify.close();
    if (std::rename(tempPath.c_str(), mergedPath.c_str()) != 0) {
        std::remove(mergedPath.c_str());
        if (std::rename(tempPath.c_str(), mergedPath.c_str()) != 0) {
            return fail("merged raw16 rename failed");
        }
    }
    std::ifstream finalVerify(mergedPath, std::ios::binary | std::ios::ate);
    if (!finalVerify || finalVerify.tellg() != expectedBytes) {
        error = "merged raw16 final size verification failed";
        finalVerify.close();
        std::remove(mergedPath.c_str());
        return false;
    }
    return true;
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

Alignment alignProxy(
    const std::vector<float>& ref,
    const std::vector<float>& cur,
    int w,
    int h,
    int radius,
    int downscale,
    float proxyDynamicRange
) {
    double bestSad = std::numeric_limits<double>::max();
    double bestMeanAbsDiff = std::numeric_limits<double>::max();
    int64_t bestCount = 0;
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
            int64_t count = 0;
            for (int y = y0; y < y1; ++y) {
                const size_t refRow = static_cast<size_t>(y) * w;
                const size_t curRow = static_cast<size_t>(y + dy) * w;
                for (int x = x0; x < x1; ++x) {
                    const float a = ref[refRow + x];
                    const float b = cur[curRow + x + dx];
                    sad += std::abs(static_cast<double>(a - b));
                    ++count;
                }
            }
            const double meanAbsDiff = count > 0 ? sad / count : std::numeric_limits<double>::max();
            if (meanAbsDiff < bestMeanAbsDiff) {
                bestSad = sad;
                bestMeanAbsDiff = meanAbsDiff;
                bestCount = count;
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
    out.validProxyPixels = bestCount;
    out.validProxyFraction = static_cast<float>(
        static_cast<double>(bestCount) / std::max<int64_t>(1, static_cast<int64_t>(w) * h)
    );
    out.sad = std::isfinite(bestSad) ? bestSad : 0.0;
    out.meanAbsDiff = bestCount > 0 ? static_cast<float>(out.sad / bestCount) : proxyDynamicRange;
    out.normalizedError = out.meanAbsDiff / std::max(1.0f, proxyDynamicRange);
    constexpr float kConfidenceScale = 3.0f;
    out.confidence = std::clamp(1.0f - out.normalizedError * kConfidenceScale, 0.0f, 1.0f);
    out.searchBoundaryHit = std::abs(bestDx) == radius || std::abs(bestDy) == radius;
    if (out.validProxyFraction < 0.55f) {
        out.acceptedForMerge = false;
        out.rejectReason = "LOW_VALID_PROXY_FRACTION";
    } else if (out.normalizedError > 0.35f) {
        out.acceptedForMerge = false;
        out.rejectReason = "EXTREME_NORMALIZED_ERROR";
    } else if (out.confidence < 0.20f) {
        out.acceptedForMerge = false;
        out.rejectReason = "LOW_ALIGNMENT_CONFIDENCE";
    } else if (out.searchBoundaryHit && out.confidence < 0.45f) {
        out.acceptedForMerge = false;
        out.rejectReason = "SEARCH_BOUNDARY_WEAK_CONFIDENCE";
    }
    out.finalFrameWeightScale = out.acceptedForMerge ? out.confidence : 0.0f;
    return out;
}

bool writeAlignmentJson(
    const std::string& path,
    const std::vector<Alignment>& alignments,
    const MergeStats& stats,
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
    const double ghostRejectedSampleRatio = stats.totalCandidateSamples > 0
        ? static_cast<double>(stats.rejectedGhostSamples) / stats.totalCandidateSamples
        : 0.0;
    const double referencePreservedPixelRatio = stats.totalOutputPixels > 0
        ? static_cast<double>(stats.referencePreservedPixelCount) /
            stats.totalOutputPixels
        : 0.0;
    output << "  \"type\": \"NATIVE_GLOBAL_SHIFT_TILE_V0_3\",\n";
    output << "  \"nativeMergeVersion\": \"NATIVE_RAW_FUSION_V0_3_TILE_CONFIDENCE_GHOST\",\n";
    output << "  \"ghostSuppressionEnabled\": true,\n";
    output << "  \"frameRejectEnabled\": true,\n";
    output << "  \"tileBasedMerge\": " << (stats.tileBasedMerge ? "true" : "false") << ",\n";
    output << "  \"tileRows\": " << stats.tileRows << ",\n";
    output << "  \"tileCount\": " << stats.tileCount << ",\n";
    output << "  \"fullFrameAccumulatorsUsed\": "
           << (stats.fullFrameAccumulatorsUsed ? "true" : "false") << ",\n";
    output << "  \"fullFrameMergedBufferUsed\": "
           << (stats.fullFrameMergedBufferUsed ? "true" : "false") << ",\n";
    output << "  \"estimatedTileMergeWorkingSetBytes\": "
           << stats.estimatedTileMergeWorkingSetBytes << ",\n";
    output << "  \"estimatedTileMergeWorkingSetMb\": "
           << static_cast<double>(stats.estimatedTileMergeWorkingSetBytes) /
                (1024.0 * 1024.0) << ",\n";
    output << "  \"downscale\": " << downscale << ",\n";
    output << "  \"searchRadius\": " << searchRadius << ",\n";
    output << "  \"referenceIndex\": " << referenceIndex << ",\n";
    output << "  \"cfaPhasePreserved\": true,\n";
    output << "  \"mergedRawFormat\": \"black_level_subtracted_compact_raw16\",\n";
    output << "  \"acceptedFrameCount\": " << stats.acceptedFrameCount << ",\n";
    output << "  \"rejectedFrameCount\": " << stats.rejectedFrameCount << ",\n";
    output << "  \"totalCandidateSamples\": " << stats.totalCandidateSamples << ",\n";
    output << "  \"rejectedGhostSamples\": " << stats.rejectedGhostSamples << ",\n";
    output << "  \"downweightedGhostSamples\": " << stats.downweightedGhostSamples << ",\n";
    output << "  \"ghostRejectedSampleRatio\": " << ghostRejectedSampleRatio << ",\n";
    output << "  \"referencePreservedPixelCount\": " << stats.referencePreservedPixelCount << ",\n";
    output << "  \"referencePreservedPixelRatio\": " << referencePreservedPixelRatio << ",\n";
    output << "  \"lowConfidenceFrameCount\": " << stats.lowConfidenceFrameCount << ",\n";
    output << "  \"searchBoundaryHitCount\": " << stats.searchBoundaryHitCount << ",\n";
    output << "  \"mergeWarning\": ";
    if (stats.mergeWarning.empty()) output << "null,\n";
    else output << "\"" << stats.mergeWarning << "\",\n";
    output << "  \"frames\": [\n";
    for (size_t i = 0; i < alignments.size(); ++i) {
        const auto& a = alignments[i];
        output << "    {\"index\": " << i
               << ", \"frameIndex\": " << i
               << ", \"dx\": " << a.proxyDx
               << ", \"dy\": " << a.proxyDy
               << ", \"proxyDx\": " << a.proxyDx
               << ", \"proxyDy\": " << a.proxyDy
               << ", \"rawDx\": " << a.rawDx
               << ", \"rawDy\": " << a.rawDy
               << ", \"validProxyPixels\": " << a.validProxyPixels
               << ", \"validProxyFraction\": " << a.validProxyFraction
               << ", \"sad\": " << a.sad
               << ", \"meanAbsDiff\": " << a.meanAbsDiff
               << ", \"normalizedError\": " << a.normalizedError
               << ", \"confidence\": " << a.confidence
               << ", \"acceptedForMerge\": " << (a.acceptedForMerge ? "true" : "false")
               << ", \"rejectReason\": ";
        if (a.rejectReason.empty()) output << "null";
        else output << "\"" << a.rejectReason << "\"";
        output << ", \"searchBoundaryHit\": " << (a.searchBoundaryHit ? "true" : "false")
               << ", \"finalFrameWeightScale\": " << a.finalFrameWeightScale << "}";
        if (i + 1 < alignments.size()) output << ",";
        output << "\n";
    }
    output << "  ]\n";
    output << "}\n";
    return output.good();
}

char bayerColorAt(int x, int y, int cfa) {
    const bool evenX = (x & 1) == 0;
    const bool evenY = (y & 1) == 0;
    switch (cfa) {
        case 1: return evenY ? (evenX ? 'G' : 'R') : (evenX ? 'B' : 'G'); // GRBG
        case 2: return evenY ? (evenX ? 'G' : 'B') : (evenX ? 'R' : 'G'); // GBRG
        case 3: return evenY ? (evenX ? 'B' : 'G') : (evenX ? 'G' : 'R'); // BGGR
        default: return evenY ? (evenX ? 'R' : 'G') : (evenX ? 'G' : 'B'); // RGGB
    }
}

int sampleCfaValue(
    const std::vector<uint16_t>& raw,
    const RawImageGeometry& geometry,
    int x,
    int y
) {
    x = std::clamp(x, 0, geometry.width - 1);
    y = std::clamp(y, 0, geometry.height - 1);
    return std::max(
        0,
        static_cast<int>(raw[static_cast<size_t>(y) * geometry.width + x]) -
            geometry.blackLevel
    );
}

struct WhiteBalanceEstimate {
    float r = 1.0f;
    float g = 1.0f;
    float b = 1.0f;
    bool fallback = false;
    uint64_t sampleCount = 0;
};

constexpr float kPostprocessBlackLift = 0.008f;
constexpr float kPostprocessGamma = 2.20f;
constexpr float kPostprocessShoulderStrength = 0.16f;
constexpr float kPostprocessExposureBiasStops = 0.0f;
constexpr float kChromaDenoiseStrength = 0.18f;
constexpr float kSharpenStrength = 0.22f;
constexpr float kDarkSharpenSuppression = 0.70f;
constexpr float kHighlightSharpenSuppression = 0.85f;
constexpr float kWbGainMin = 0.6f;
constexpr float kWbGainMax = 2.2f;

uint8_t toneRawV02(float value, int whiteRange) {
    const float exposureScale = std::pow(2.0f, kPostprocessExposureBiasStops);
    const float linear = std::clamp(
        value * exposureScale / std::max(1, whiteRange),
        0.0f,
        1.0f
    );
    const float lifted = std::clamp(
        linear + kPostprocessBlackLift * (1.0f - linear),
        0.0f,
        1.0f
    );
    const float gammaMapped = std::pow(lifted, 1.0f / kPostprocessGamma);
    const float shouldered = gammaMapped /
        std::max(
            0.0001f,
            gammaMapped + kPostprocessShoulderStrength * (1.0f - gammaMapped)
        );
    return static_cast<uint8_t>(std::clamp(std::lround(shouldered * 255.0f), 0L, 255L));
}

bool writePostprocessMetadata(
    const std::string& path,
    const std::string& outputPath,
    int inputWidth,
    int inputHeight,
    int outputWidth,
    int outputHeight,
    const WhiteBalanceEstimate& wb,
    std::string& error
) {
    std::ofstream output(path);
    if (!output) {
        error = "metadata json open failed: " + path;
        return false;
    }
    output << "{\n"
           << "  \"nativePostprocess\": true,\n"
           << "  \"nativePostprocessVersion\": \"NATIVE_POSTPROCESS_V0_2\",\n"
           << "  \"inputWidth\": " << inputWidth << ",\n"
           << "  \"inputHeight\": " << inputHeight << ",\n"
           << "  \"outputWidth\": " << outputWidth << ",\n"
           << "  \"outputHeight\": " << outputHeight << ",\n"
           << "  \"demosaic\": \"edge_aware_scaled_bilinear_v0_2\",\n"
           << "  \"wbMode\": \"" << (wb.fallback ? "identity_fallback" : "gray_world_v0_2") << "\",\n"
           << "  \"wbGainR\": " << wb.r << ",\n"
           << "  \"wbGainG\": " << wb.g << ",\n"
           << "  \"wbGainB\": " << wb.b << ",\n"
           << "  \"wbSampleCount\": " << wb.sampleCount << ",\n"
           << "  \"wbFallback\": " << (wb.fallback ? "true" : "false") << ",\n"
           << "  \"toneMap\": \"filmic_shoulder_v0_2\",\n"
           << "  \"blackLift\": " << kPostprocessBlackLift << ",\n"
           << "  \"gamma\": " << kPostprocessGamma << ",\n"
           << "  \"shoulderStrength\": " << kPostprocessShoulderStrength << ",\n"
           << "  \"exposureBias\": " << kPostprocessExposureBiasStops << ",\n"
           << "  \"chromaDenoise\": \"mild_chroma_v0_2\",\n"
           << "  \"chromaDenoiseStrength\": " << kChromaDenoiseStrength << ",\n"
           << "  \"sharpen\": \"adaptive_luma_unsharp_v0_2\",\n"
           << "  \"sharpenStrength\": " << kSharpenStrength << ",\n"
           << "  \"darkSharpenSuppression\": " << kDarkSharpenSuppression << ",\n"
           << "  \"highlightSharpenSuppression\": "
           << kHighlightSharpenSuppression << ",\n"
           << "  \"status\": \"OK\",\n"
           << "  \"outputPath\": \"" << outputPath << "\",\n"
           << "  \"outputName\": \"" << outputPath.substr(outputPath.find_last_of("/\\\\") + 1) << "\",\n"
           << "  \"highResRawInput\": "
           << (static_cast<int64_t>(inputWidth) * inputHeight >= 40000000LL ? "true" : "false")
           << ",\n"
           << "  \"outputFormat\": \"RGBA_8888_RAW\"\n"
           << "}\n";
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
        const auto proxyMinMax = std::minmax_element(
            proxies[referenceIndex].begin(),
            proxies[referenceIndex].end()
        );
        const float proxyDynamicRange = proxyMinMax.first != proxies[referenceIndex].end()
            ? std::max(1.0f, *proxyMinMax.second - *proxyMinMax.first)
            : 1.0f;
        for (int i = 0; i < frameCount; ++i) {
            if (i == referenceIndex) {
                Alignment reference;
                reference.validProxyPixels = static_cast<int64_t>(proxyW) * proxyH;
                reference.validProxyFraction = 1.0f;
                reference.finalFrameWeightScale = std::max(1.0f, frameWeights[i]) * 1.5f;
                alignments[i] = reference;
            } else {
                alignments[i] = alignProxy(
                    proxies[referenceIndex],
                    proxies[i],
                    proxyW,
                    proxyH,
                    searchRadius,
                    downscale,
                    proxyDynamicRange
                );
                alignments[i].finalFrameWeightScale = alignments[i].acceptedForMerge
                    ? std::max(0.0f, frameWeights[i]) * alignments[i].confidence
                    : 0.0f;
            }
        }

        MergeStats mergeStats;
        mergeStats.totalOutputPixels = pixels;
        for (const auto& alignment : alignments) {
            if (alignment.acceptedForMerge) ++mergeStats.acceptedFrameCount;
            else ++mergeStats.rejectedFrameCount;
            if (alignment.confidence < 0.4f) ++mergeStats.lowConfidenceFrameCount;
            if (alignment.searchBoundaryHit) ++mergeStats.searchBoundaryHitCount;
        }
        if (mergeStats.acceptedFrameCount == 1) {
            mergeStats.mergeWarning = "REFERENCE_ONLY_MERGE";
        }
        std::vector<std::vector<float>>().swap(proxies);
        std::vector<uint16_t>().swap(raw);

        const std::string mergedPath = getString(env, outputMergedRawPath);
        const std::string alignmentPath = getString(env, outputAlignmentJsonPath);
        if (!mergeRaw16Tiles(
                TileMergeContext{
                    paths,
                    exposureScales,
                    alignments,
                    RawImageGeometry{width, height, blackLevel, whiteLevel},
                    MergeOptions{referenceIndex},
                    mergedPath
                },
                mergeStats,
                error
            )) {
            return env->NewStringUTF(("ERROR: " + error).c_str());
        }
        if (!writeAlignmentJson(
                alignmentPath,
                alignments,
                mergeStats,
                downscale,
                searchRadius,
                referenceIndex,
                error
            )) {
            return env->NewStringUTF(("ERROR: " + error).c_str());
        }
        std::ostringstream status;
        status << "OK: native RAW fusion v0.3 tile merge complete, accepted="
               << mergeStats.acceptedFrameCount
               << ", rejected=" << mergeStats.rejectedFrameCount;
        if (!mergeStats.mergeWarning.empty()) status << ", warning=" << mergeStats.mergeWarning;
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_projectnuke_keplernightlab_NativeRawEngine_processRaw16ToRgbOutput(
    JNIEnv* env,
    jobject,
    jstring mergedRawPath,
    jint width,
    jint height,
    jint cfaPattern,
    jint blackLevel,
    jint whiteLevel,
    jint outputWidth,
    jint outputHeight,
    jstring outputPath,
    jstring outputMetadataJsonPath
) {
    try {
        if (width <= 0 || height <= 0 || outputWidth <= 0 || outputHeight <= 0) {
            return env->NewStringUTF("ERROR: invalid dimensions");
        }
        const int64_t inputPixels = static_cast<int64_t>(width) * height;
        const int64_t outputPixels = static_cast<int64_t>(outputWidth) * outputHeight;
        if (inputPixels <= 0 || inputPixels > 120000000LL || outputPixels <= 0 || outputPixels > 32000000LL) {
            return env->NewStringUTF("ERROR: dimensions exceed native v0 limits");
        }

        std::vector<uint16_t> raw;
        std::string error;
        if (!readRaw16(getString(env, mergedRawPath), width, height, raw, error)) {
            return env->NewStringUTF(("ERROR: " + error).c_str());
        }
        const PostprocessOptions postprocessOptions{
            RawImageGeometry{width, height, blackLevel, whiteLevel},
            cfaPattern,
            outputWidth,
            outputHeight
        };
        const int whiteRange = std::max(1, static_cast<int>(whiteLevel) - static_cast<int>(blackLevel));
        auto rawAt = [&](int x, int y) {
            return sampleCfaValue(raw, postprocessOptions.input, x, y);
        };
        auto average2 = [](int a, int b) { return (a + b) / 2; };
        auto average4 = [](int a, int b, int c, int d) { return (a + b + c + d) / 4; };
        auto sourceCoordinate = [](int outputCoordinate, int inputSize, int outputSize) {
            int source = static_cast<int>(
                (static_cast<int64_t>(outputCoordinate) * inputSize) / outputSize
            );
            return std::clamp(
                (source / 2) * 2 + (outputCoordinate & 1),
                0,
                inputSize - 1
            );
        };
        auto demosaicAt = [&](int sx, int sy) -> std::array<float, 3> {
            const int value = rawAt(sx, sy);
            float r = 0.0f;
            float g = 0.0f;
            float b = 0.0f;
            const char color = bayerColorAt(sx, sy, postprocessOptions.cfaPattern);
            if (color == 'R' || color == 'B') {
                const int horizontalGradient =
                    std::abs(rawAt(sx - 1, sy) - rawAt(sx + 1, sy)) +
                    std::abs(2 * value - rawAt(sx - 2, sy) - rawAt(sx + 2, sy));
                const int verticalGradient =
                    std::abs(rawAt(sx, sy - 1) - rawAt(sx, sy + 1)) +
                    std::abs(2 * value - rawAt(sx, sy - 2) - rawAt(sx, sy + 2));
                if (horizontalGradient < verticalGradient) {
                    g = static_cast<float>(average2(rawAt(sx - 1, sy), rawAt(sx + 1, sy)));
                } else if (verticalGradient < horizontalGradient) {
                    g = static_cast<float>(average2(rawAt(sx, sy - 1), rawAt(sx, sy + 1)));
                } else {
                    g = static_cast<float>(average4(
                        rawAt(sx - 1, sy),
                        rawAt(sx + 1, sy),
                        rawAt(sx, sy - 1),
                        rawAt(sx, sy + 1)
                    ));
                }
                const int diagonalOneGradient =
                    std::abs(rawAt(sx - 1, sy - 1) - rawAt(sx + 1, sy + 1));
                const int diagonalTwoGradient =
                    std::abs(rawAt(sx + 1, sy - 1) - rawAt(sx - 1, sy + 1));
                const float opposite = diagonalOneGradient < diagonalTwoGradient
                    ? static_cast<float>(average2(rawAt(sx - 1, sy - 1), rawAt(sx + 1, sy + 1)))
                    : diagonalTwoGradient < diagonalOneGradient
                        ? static_cast<float>(average2(rawAt(sx + 1, sy - 1), rawAt(sx - 1, sy + 1)))
                        : static_cast<float>(average4(
                            rawAt(sx - 1, sy - 1),
                            rawAt(sx + 1, sy - 1),
                            rawAt(sx - 1, sy + 1),
                            rawAt(sx + 1, sy + 1)
                        ));
                if (color == 'R') {
                    r = static_cast<float>(value);
                    b = opposite;
                } else {
                    b = static_cast<float>(value);
                    r = opposite;
                }
            } else {
                g = static_cast<float>(value);
                const bool greenOnRedRow =
                    (cfaPattern == 2 || cfaPattern == 3) ? ((sy & 1) != 0) : ((sy & 1) == 0);
                const float horizontalColor = static_cast<float>(
                    average2(rawAt(sx - 1, sy), rawAt(sx + 1, sy))
                );
                const float verticalColor = static_cast<float>(
                    average2(rawAt(sx, sy - 1), rawAt(sx, sy + 1))
                );
                const float horizontalGreen = static_cast<float>(
                    average2(rawAt(sx - 2, sy), rawAt(sx + 2, sy))
                );
                const float verticalGreen = static_cast<float>(
                    average2(rawAt(sx, sy - 2), rawAt(sx, sy + 2))
                );
                const float correctedHorizontal = horizontalColor + 0.5f * (g - horizontalGreen);
                const float correctedVertical = verticalColor + 0.5f * (g - verticalGreen);
                if (greenOnRedRow) {
                    r = correctedHorizontal;
                    b = correctedVertical;
                } else {
                    r = correctedVertical;
                    b = correctedHorizontal;
                }
            }
            return {
                std::clamp(r, 0.0f, static_cast<float>(whiteRange)),
                std::clamp(g, 0.0f, static_cast<float>(whiteRange)),
                std::clamp(b, 0.0f, static_cast<float>(whiteRange))
            };
        };

        WhiteBalanceEstimate wb;
        double sampleR = 0.0;
        double sampleG = 0.0;
        double sampleB = 0.0;
        uint64_t sampleCount = 0;
        const int sampleStepX = std::max(8, postprocessOptions.outputWidth / 160);
        const int sampleStepY = std::max(8, postprocessOptions.outputHeight / 120);
        for (int oy = sampleStepY / 2; oy < postprocessOptions.outputHeight; oy += sampleStepY) {
            const int sy = sourceCoordinate(
                oy,
                postprocessOptions.input.height,
                postprocessOptions.outputHeight
            );
            for (int ox = sampleStepX / 2; ox < postprocessOptions.outputWidth; ox += sampleStepX) {
                const int sx = sourceCoordinate(
                    ox,
                    postprocessOptions.input.width,
                    postprocessOptions.outputWidth
                );
                const auto rgb = demosaicAt(sx, sy);
                const float luma = 0.25f * rgb[0] + 0.50f * rgb[1] + 0.25f * rgb[2];
                if (luma < whiteRange * 0.03f || luma > whiteRange * 0.92f) continue;
                sampleR += rgb[0];
                sampleG += rgb[1];
                sampleB += rgb[2];
                ++sampleCount;
            }
        }
        if (sampleCount >= 64 && sampleR > 1.0 && sampleG > 1.0 && sampleB > 1.0) {
            const double meanR = sampleR / sampleCount;
            const double meanG = sampleG / sampleCount;
            const double meanB = sampleB / sampleCount;
            wb.r = std::clamp(
                static_cast<float>(meanG / meanR),
                kWbGainMin,
                kWbGainMax
            );
            wb.g = 1.0f;
            wb.b = std::clamp(
                static_cast<float>(meanG / meanB),
                kWbGainMin,
                kWbGainMax
            );
        } else {
            wb.fallback = true;
        }
        wb.sampleCount = sampleCount;

        // Single output-sized RGB buffer. Denoise and sharpen are applied while rows are written.
        std::vector<uint8_t> toned(static_cast<size_t>(outputPixels) * 3);
        for (int oy = 0; oy < outputHeight; ++oy) {
            const int sy = sourceCoordinate(oy, height, outputHeight);
            for (int ox = 0; ox < outputWidth; ++ox) {
                const int sx = sourceCoordinate(ox, width, outputWidth);
                const auto rgb = demosaicAt(sx, sy);
                const size_t p = (static_cast<size_t>(oy) * outputWidth + ox) * 3;
                toned[p] = toneRawV02(rgb[0] * wb.r, whiteRange);
                toned[p + 1] = toneRawV02(rgb[1] * wb.g, whiteRange);
                toned[p + 2] = toneRawV02(rgb[2] * wb.b, whiteRange);
            }
        }

        const std::string rgbaOutputPath = getString(env, outputPath);
        std::ofstream output(rgbaOutputPath, std::ios::binary);
        if (!output) return env->NewStringUTF("ERROR: RGBA output open failed");
        std::vector<uint8_t> rgbaRow(static_cast<size_t>(outputWidth) * 4);
        for (int y = 0; y < outputHeight; ++y) {
            for (int x = 0; x < outputWidth; ++x) {
                const size_t center = (static_cast<size_t>(y) * outputWidth + x) * 3;
                const size_t out = static_cast<size_t>(x) * 4;
                const float centerR = toned[center];
                const float centerG = toned[center + 1];
                const float centerB = toned[center + 2];
                const float centerLuma = 0.25f * centerR + 0.50f * centerG + 0.25f * centerB;
                double lumaSum = 0.0;
                double chromaRSum = 0.0;
                double chromaBSum = 0.0;
                for (int dy = -1; dy <= 1; ++dy) {
                    const int yy = std::clamp(y + dy, 0, static_cast<int>(outputHeight) - 1);
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int xx = std::clamp(x + dx, 0, static_cast<int>(outputWidth) - 1);
                        const size_t p = (static_cast<size_t>(yy) * outputWidth + xx) * 3;
                        const float r = toned[p];
                        const float g = toned[p + 1];
                        const float b = toned[p + 2];
                        const float luma = 0.25f * r + 0.50f * g + 0.25f * b;
                        lumaSum += luma;
                        chromaRSum += r - luma;
                        chromaBSum += b - luma;
                    }
                }
                const float localLuma = static_cast<float>(lumaSum / 9.0);
                const float centerChromaR = centerR - centerLuma;
                const float centerChromaB = centerB - centerLuma;
                const float denoisedChromaR = centerChromaR * (1.0f - kChromaDenoiseStrength) +
                    static_cast<float>(chromaRSum / 9.0) * kChromaDenoiseStrength;
                const float denoisedChromaB = centerChromaB * (1.0f - kChromaDenoiseStrength) +
                    static_cast<float>(chromaBSum / 9.0) * kChromaDenoiseStrength;
                const float darkFactor = std::clamp(
                    (centerLuma - 18.0f) / 72.0f,
                    1.0f - kDarkSharpenSuppression,
                    1.0f
                );
                const float highlightFactor = std::clamp(
                    (245.0f - centerLuma) / 45.0f,
                    1.0f - kHighlightSharpenSuppression,
                    1.0f
                );
                const float adaptiveStrength = kSharpenStrength * darkFactor * highlightFactor;
                const float sharpenedLuma = std::clamp(
                    centerLuma + (centerLuma - localLuma) * adaptiveStrength,
                    0.0f,
                    255.0f
                );
                const float outR = sharpenedLuma + denoisedChromaR;
                const float outB = sharpenedLuma + denoisedChromaB;
                const float outG = sharpenedLuma -
                    0.5f * denoisedChromaR -
                    0.5f * denoisedChromaB;
                rgbaRow[out] = clampToByte(outR);
                rgbaRow[out + 1] = clampToByte(outG);
                rgbaRow[out + 2] = clampToByte(outB);
                rgbaRow[out + 3] = 255;
            }
            output.write(reinterpret_cast<const char*>(rgbaRow.data()), static_cast<std::streamsize>(rgbaRow.size()));
        }
        if (!output.good()) return env->NewStringUTF("ERROR: RGBA output write failed");

        if (!writePostprocessMetadata(
                getString(env, outputMetadataJsonPath),
                rgbaOutputPath,
                width,
                height,
                outputWidth,
                outputHeight,
                wb,
                error
            )) {
            return env->NewStringUTF(("ERROR: " + error).c_str());
        }
        return env->NewStringUTF("OK: native scaled demosaic+postprocess complete");
    } catch (const std::bad_alloc&) {
        return env->NewStringUTF("ERROR: native out of memory");
    } catch (const std::exception& e) {
        return env->NewStringUTF((std::string("ERROR: ") + e.what()).c_str());
    } catch (...) {
        return env->NewStringUTF("ERROR: unknown native failure");
    }
}

bool metadataContainsWarning(const std::string& metadataPath, const std::string& warning) {
    std::ifstream input(metadataPath);
    if (!input) return false;
    std::ostringstream contents;
    contents << input.rdbuf();
    return contents.str().find(warning) != std::string::npos;
}

bool writeNativeIspV2DebugJson(
    const std::string& path,
    const std::string& metadataPath,
    const std::string& rgbaPath,
    int width,
    int height,
    int outputWidth,
    int outputHeight,
    int cfaPattern,
    int blackLevel,
    int whiteLevel,
    const std::string& status,
    std::string& error
) {
    std::ofstream output(path);
    if (!output) {
        error = "native raw isp debug json open failed: " + path;
        return false;
    }
    const bool wbMissing = metadataContainsWarning(metadataPath, "WB_GAINS_MISSING");
    const bool matrixMissing = metadataContainsWarning(metadataPath, "COLOR_TRANSFORM_MISSING");
    output << "{\n"
           << "  \"rawRenderVersion\": \"native_raw_isp_v0.2\",\n"
           << "  \"nativeRawIspUsed\": true,\n"
           << "  \"nativePostprocess\": true,\n"
           << "  \"inputWidth\": " << width << ",\n"
           << "  \"inputHeight\": " << height << ",\n"
           << "  \"outputWidth\": " << outputWidth << ",\n"
           << "  \"outputHeight\": " << outputHeight << ",\n"
           << "  \"cfaPattern\": " << cfaPattern << ",\n"
           << "  \"blackLevelUsed\": " << blackLevel << ",\n"
           << "  \"whiteLevelUsed\": " << whiteLevel << ",\n"
           << "  \"mergedRawFormat\": \"black_level_subtracted_compact_raw16\",\n"
           << "  \"wbGainsUsed\": \"metadata_or_gray_world_fallback\",\n"
           << "  \"colorTransformUsed\": \"" << (matrixMissing ? "identity_fallback" : "metadata_3x3") << "\",\n"
           << "  \"demosaicMethod\": \"BILINEAR_SAFE_V0\",\n"
           << "  \"denoiseVersion\": \"native_chroma_luma_v0.2\",\n"
           << "  \"denoiseStrength\": 0.35,\n"
           << "  \"chromaDenoiseStrength\": " << kChromaDenoiseStrength << ",\n"
           << "  \"toneVersion\": \"native_conservative_shoulder_v0.2\",\n"
           << "  \"rawRenderExposureGain\": 1.0,\n"
           << "  \"rawRenderShadowLift\": " << kPostprocessBlackLift << ",\n"
           << "  \"rawRenderHighlightRollOff\": " << kPostprocessShoulderStrength << ",\n"
           << "  \"rawRenderSharpenAmount\": " << kSharpenStrength << ",\n"
           << "  \"hotPixelCount\": 0,\n"
           << "  \"channelMeansBeforeWb\": null,\n"
           << "  \"channelMeansAfterWb\": null,\n"
           << "  \"channelMeansAfterMatrix\": null,\n"
           << "  \"saturationEstimate\": null,\n"
           << "  \"overexposureRatio\": null,\n"
           << "  \"renderWarnings\": [";
    bool first = true;
    if (wbMissing) {
        output << "\"WB_GAINS_MISSING\"";
        first = false;
    }
    if (matrixMissing) {
        if (!first) output << ", ";
        output << "\"COLOR_TRANSFORM_MISSING\"";
    }
    output << "],\n"
           << "  \"metadataJsonPath\": \"" << metadataPath << "\",\n"
           << "  \"outputPath\": \"" << rgbaPath << "\",\n"
           << "  \"status\": \"" << status << "\"\n"
           << "}\n";
    return output.good();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_projectnuke_keplernightlab_NativeRawEngine_processRaw16ToRgbOutputV2(
    JNIEnv* env,
    jobject thiz,
    jstring mergedRawPath,
    jstring referenceRawPath,
    jint width,
    jint height,
    jint cfaPattern,
    jint blackLevel,
    jint whiteLevel,
    jint outputWidth,
    jint outputHeight,
    jstring metadataJsonPath,
    jstring outputRgbaPath,
    jstring outputDebugJsonPath,
    jstring outputReferenceDebugRgbaPath,
    jstring outputMergedLinearDebugRgbaPath
) {
    const jstring tempMainMetadata = env->NewStringUTF((getString(env, outputDebugJsonPath) + ".native.tmp").c_str());
    jstring mainStatusString = Java_com_projectnuke_keplernightlab_NativeRawEngine_processRaw16ToRgbOutput(
        env,
        thiz,
        mergedRawPath,
        width,
        height,
        cfaPattern,
        blackLevel,
        whiteLevel,
        outputWidth,
        outputHeight,
        outputRgbaPath,
        tempMainMetadata
    );
    const std::string mainStatus = getString(env, mainStatusString);
    env->DeleteLocalRef(tempMainMetadata);
    if (mainStatus.rfind("OK:", 0) != 0) {
        return env->NewStringUTF(mainStatus.c_str());
    }

    const int debugMax = 960;
    const int debugWidth = std::max(2, (outputWidth > outputHeight)
        ? debugMax
        : std::max(2, outputWidth * debugMax / std::max(1, outputHeight)));
    const int debugHeight = std::max(2, (outputHeight >= outputWidth)
        ? debugMax
        : std::max(2, outputHeight * debugMax / std::max(1, outputWidth)));

    const std::string mergedDebug = getOptionalString(env, outputMergedLinearDebugRgbaPath);
    if (!mergedDebug.empty()) {
        const jstring tempDebugPath = env->NewStringUTF((mergedDebug + ".json.tmp").c_str());
        Java_com_projectnuke_keplernightlab_NativeRawEngine_processRaw16ToRgbOutput(
            env,
            thiz,
            mergedRawPath,
            width,
            height,
            cfaPattern,
            blackLevel,
            whiteLevel,
            debugWidth,
            debugHeight,
            outputMergedLinearDebugRgbaPath,
            tempDebugPath
        );
        env->DeleteLocalRef(tempDebugPath);
    }

    const std::string referencePath = getOptionalString(env, referenceRawPath);
    const std::string referenceDebug = getOptionalString(env, outputReferenceDebugRgbaPath);
    if (!referencePath.empty() && !referenceDebug.empty()) {
        const jstring tempDebugPath = env->NewStringUTF((referenceDebug + ".json.tmp").c_str());
        Java_com_projectnuke_keplernightlab_NativeRawEngine_processRaw16ToRgbOutput(
            env,
            thiz,
            referenceRawPath,
            width,
            height,
            cfaPattern,
            blackLevel,
            whiteLevel,
            debugWidth,
            debugHeight,
            outputReferenceDebugRgbaPath,
            tempDebugPath
        );
        env->DeleteLocalRef(tempDebugPath);
    }

    std::string error;
    if (!writeNativeIspV2DebugJson(
            getString(env, outputDebugJsonPath),
            getString(env, metadataJsonPath),
            getString(env, outputRgbaPath),
            width,
            height,
            outputWidth,
            outputHeight,
            cfaPattern,
            blackLevel,
            whiteLevel,
            mainStatus,
            error
        )) {
        return env->NewStringUTF(("ERROR: " + error).c_str());
    }
    return env->NewStringUTF("OK: native RAW ISP v0.2 complete");
}
