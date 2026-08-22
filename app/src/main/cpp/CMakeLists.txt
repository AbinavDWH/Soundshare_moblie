cmake_minimum_required(VERSION 3.22.1)
project(soundshare_engine)

# --- libopus built from source ---
set(OPUS_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory(opus)

find_package(oboe REQUIRED CONFIG)

add_library(soundshare_engine SHARED native_engine.cpp)

target_compile_options(soundshare_engine PRIVATE -std=c++17 -Wall)

target_link_libraries(soundshare_engine
    oboe::oboe
    opus
    android
    log)