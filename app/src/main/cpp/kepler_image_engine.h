#pragma once

#include <jni.h>

namespace kepler_image {

enum class ProcessStatus : int {
    SUCCESS = 0,
    INVALID_ARGUMENT = 1,
    ARRAY_LENGTH_MISMATCH = 2,
    ARRAY_ACQUIRE_FAILED = 3,
    PROCESSING_FAILED = 4
};

// The implementation is deliberately integer-array based so callers can reuse
// their Bitmap pixel buffers and avoid per-pixel JNI allocations.
ProcessStatus process_argb(const jint* source, jint* output, int width, int height,
                           int denoise, int tone, float denoise_strength,
                           float sharpen, float local_contrast, float shadow_lift,
                           float highlight_rolloff, float saturation, int tile_rows);

}
