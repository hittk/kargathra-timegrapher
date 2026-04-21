cmake_minimum_required(VERSION 3.22.1)
project(kargathra_engine VERSION 1.0)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Oboe (fetched via Gradle AAR, headers via prefab) ──────────────────────────
find_package(oboe REQUIRED CONFIG)

# ── Source files ──────────────────────────────────────────────────────────────
add_library(
    kargathra_engine
    SHARED
    AudioEngine.cpp
    BandpassFilter.cpp
    NoiseFloor.cpp
    TickDetector.cpp
    AmplitudeCalculator.cpp
    JNIBridge.cpp
)

# ── Include dirs ──────────────────────────────────────────────────────────────
target_include_directories(kargathra_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# ── Link libraries ─────────────────────────────────────────────────────────────
target_link_libraries(
    kargathra_engine
    oboe::oboe
    android
    log
    atomic
)

# ── Compiler flags ─────────────────────────────────────────────────────────────
target_compile_options(kargathra_engine PRIVATE
    -Wall
    -Wextra
    -O2
    -ffast-math           # Safe for DSP; improves filter performance
)
