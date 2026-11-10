cmake_minimum_required(VERSION 3.18)

# After Motion native engine (decoder pool, later: render graph, encoder, audio mixer).
add_library(am_engine SHARED
    decoder_pool.cpp decoder_jni.cpp
    egl_core.cpp renderer.cpp renderer_fx.cpp renderer_effects.cpp fx_plan.cpp mask_plan.cpp jpeg_luma.cpp render_jni.cpp
    native_export_encoder.cpp native_export_jni.cpp
    audio_mixer.cpp audio_decoder.cpp audio_preview.cpp audio_preview_jni.cpp
    layer_anim.cpp overlay_pose.cpp camera.cpp anim_jni.cpp image_loader.cpp
    export_plan.cpp export_plan_jni.cpp
    text_blit.cpp text_layout.cpp text_engine.cpp text_jni.cpp
    shape_engine.cpp shape_jni.cpp
    planar_track.cpp planar_jni.cpp
    remove_plate.cpp remove_jni.cpp stabilize_jni.cpp interp_jni.cpp face_swap_jni.cpp)
target_compile_features(am_engine PRIVATE cxx_std_17)
target_compile_options(am_engine PRIVATE -O2 -Wall -Wextra)
# Native text layers (FreeType + HarfBuzz). The sources are downloaded by the GitHub workflow into
# cpp/freetype and cpp/harfbuzz (like whisper.cpp). Without them text_engine.cpp compiles to a stub,
# available() is false and the exporter keeps drawing text with the Android Canvas.
set(AM_FT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../freetype)
set(AM_HB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../harfbuzz)
if(EXISTS ${AM_FT_DIR}/CMakeLists.txt AND EXISTS ${AM_HB_DIR}/src/harfbuzz.cc)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(FT_DISABLE_ZLIB ON CACHE BOOL "" FORCE)
    set(FT_DISABLE_BZIP2 ON CACHE BOOL "" FORCE)
    set(FT_DISABLE_PNG ON CACHE BOOL "" FORCE)
    set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "" FORCE)
    set(FT_DISABLE_BROTLI ON CACHE BOOL "" FORCE)
    add_subdirectory(${AM_FT_DIR} am_freetype_build EXCLUDE_FROM_ALL)
    # HarfBuzz as one translation unit (its own "amalgamated" build); OpenType shaping only, no FreeType glue needed.
    add_library(am_harfbuzz STATIC ${AM_HB_DIR}/src/harfbuzz.cc)
    target_include_directories(am_harfbuzz PUBLIC ${AM_HB_DIR}/src)
    target_compile_options(am_harfbuzz PRIVATE -O2 -fno-exceptions -fno-rtti -w)
    target_compile_definitions(am_engine PRIVATE AM_TEXT_ENGINE=1)
    target_sources(am_engine PRIVATE colr_render.cpp)  # colour emoji fonts of the COLR kind (needs FreeType 2.13+)
    target_include_directories(am_engine PRIVATE ${AM_FT_DIR}/include)
    target_link_libraries(am_engine freetype am_harfbuzz)
else()
    message(WARNING "FreeType / HarfBuzz sources not found - native text layers are disabled (Canvas text is used).")
endif()
# AMediaCodec / AMediaExtractor need API 21+, everything used here is available on minSdk 24.
target_link_libraries(am_engine mediandk android jnigraphics log EGL GLESv3 dl)
set_target_properties(am_engine PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")
