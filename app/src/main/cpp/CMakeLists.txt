cmake_minimum_required(VERSION 3.22.1)

project(kepler_raw_engine)

add_library(kepler_raw_engine SHARED kepler_raw_engine.cpp)

find_library(log-lib log)

target_link_libraries(kepler_raw_engine ${log-lib})
