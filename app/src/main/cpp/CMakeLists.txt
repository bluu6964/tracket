cmake_minimum_required(VERSION 3.18)
project(after_motion_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ggml is 10-50x slower without optimisation, so the native part is ALWAYS built as Release,
# also inside a Gradle "debug" build.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# After Motion engine (decoder pool etc.) - does not depend on whisper.cpp.
add_subdirectory(engine am_engine_build)

# whisper.cpp source goes here (see README.md in this folder). Without it only the engine is built
# and Auto Captions reports that the speech engine is missing, exactly as before.
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(WARNING "whisper.cpp sources not found in ${WHISPER_DIR} - building without Auto Captions.")
    return()
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# Keep the shared library compatible with the Android arm64-v8a baseline. Do not force
# -march=armv8.2-a+dotprod+fp16 globally: ggml performs compiler/feature probes and those flags
# can be passed to a host probe compiler, which rejects an ARM CPU name. ggml may enable safe
# per-target optimizations itself; runtime dispatch must not assume every arm64 device has dotprod.

# Android 15+ phones with 16 KB memory pages need 16 KB aligned libraries.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_subdirectory(${WHISPER_DIR} whisper_build EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_include_directories(whisper_jni PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)
target_link_libraries(whisper_jni whisper log android)
