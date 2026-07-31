cmake_minimum_required(VERSION 3.22.1)

project(kepler_raw_engine)

add_library(
    kepler_raw_engine
    SHARED
    kepler_raw_engine.cpp
    frame_quality.cpp
    fusion_alignment.cpp
    kepler_image_engine.cpp
)

# Keep device-debug native timing representative while retaining symbols.
# Deliberately omit fast-math: NaN/finite behavior is part of the pipeline contract.
target_compile_options(kepler_raw_engine PRIVATE -O2 -g -Wall -Wextra -Wpedantic)

find_library(log-lib log)

target_link_libraries(kepler_raw_engine ${log-lib})
