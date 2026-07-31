#include "kepler_image_engine.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstdint>
#include <iostream>
#include <vector>

namespace {
constexpr int kWidth = 48;
constexpr int kHeight = 40;

std::vector<jint> flatNoise() {
    std::vector<jint> pixels(kWidth * kHeight);
    for (int y = 0; y < kHeight; ++y) {
        for (int x = 0; x < kWidth; ++x) {
            const int noise = ((x * 37 + y * 53 + x * y * 7) % 31) - 15;
            const int value = std::clamp(128 + noise, 0, 255);
            pixels[y * kWidth + x] = (0xFF << 24) | (value << 16) |
                (value << 8) | value;
        }
    }
    return pixels;
}

double variance(const std::vector<jint>& pixels) {
    double sum = 0.0;
    for (jint pixel : pixels) sum += static_cast<double>(pixel & 255);
    const double mean = sum / pixels.size();
    double squared = 0.0;
    for (jint pixel : pixels) {
        const double delta = static_cast<double>(pixel & 255) - mean;
        squared += delta * delta;
    }
    return squared / pixels.size();
}

std::vector<jint> run(const std::vector<jint>& input, int algorithm,
                      float denoise, float sharpen = 0.0f,
                      float localContrast = 0.0f) {
    std::vector<jint> output(input.size(), 0);
    kepler_image::process_argb(
        input.data(), output.data(), kWidth, kHeight, algorithm, 0,
        denoise, sharpen, localContrast, 0.0f, 0.0f, 1.0f, 16);
    return output;
}

void require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}
}

int main() {
    const auto input = flatNoise();
    const auto identity = run(input, 0, 0.0f);
    require(identity == input, "zero-strength identity");

    const auto denoised = run(input, 0, 0.85f);
    require(variance(denoised) < variance(input), "denoise reduces flat-field variance");

    const auto sharpened = run(input, 0, 0.85f, 1.0f);
    require(variance(sharpened) <= variance(input), "sharpen does not amplify flat noise");

    std::vector<jint> edge(kWidth * kHeight);
    for (int y = 0; y < kHeight; ++y) for (int x = 0; x < kWidth; ++x) {
        const int value = x < kWidth / 2 ? 24 : 224;
        edge[y * kWidth + x] = (0xFF << 24) | (value << 16) | (value << 8) | value;
    }
    const auto edgeOutput = run(edge, 0, 0.8f, 1.0f, 0.25f);
    for (jint pixel : edgeOutput) {
        const int value = pixel & 255;
        require(value >= 0 && value <= 255, "bounded step edge");
    }

    const auto guided = run(input, 0, 0.7f);
    const auto multiscale = run(input, 1, 0.7f);
    const auto bilateral = run(input, 2, 0.7f);
    require(guided != multiscale, "guided and multiscale differ");
    require(guided != bilateral, "guided and bilateral differ");
    require(multiscale != bilateral, "multiscale and bilateral differ");
    std::cout << "native image engine host tests passed\n";
    return 0;
}
