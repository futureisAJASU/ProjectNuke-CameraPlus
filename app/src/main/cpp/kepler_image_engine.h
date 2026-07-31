#pragma once

#include <jni.h>

namespace kepler_image {

// The implementation is deliberately integer-array based so callers can reuse
// their Bitmap pixel buffers and avoid per-pixel JNI allocations.
void process_argb(const jint* source, jint* output, int width, int height,
                  int denoise, int tone, float denoise_strength,
                  float sharpen, float local_contrast, float shadow_lift,
                  float highlight_rolloff, float saturation, int tile_rows);

}
