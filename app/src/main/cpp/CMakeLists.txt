cmake_minimum_required(VERSION 3.22.1)
project("nova_native" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Common link libraries
find_library(log-lib log)
find_library(android-lib android)


# ============================================================
# whisper.cpp - Speech-to-Text library
# ============================================================

# Path to whisper.cpp source (cloned as submodule)
set(WHISPER_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")

# Whisper uses the same ggml backend as llama.cpp
# We compile whisper's own ggml copy to avoid symbol conflicts
set(WHISPER_GGML_SOURCES
    ${WHISPER_CPP_DIR}/ggml/src/ggml.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-backend-reg.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-threading.cpp
    ${WHISPER_CPP_DIR}/ggml/src/gguf.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/ops.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/vec.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/unary-ops.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/binary-ops.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/quants.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/traits.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/repack.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/hbm.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/llamafile/sgemm.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-backend-dl.cpp
)

# ARM64-specific whisper source files
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    list(APPEND WHISPER_GGML_SOURCES
        ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/arch/arm/quants.c
        ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp
        ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/arch/arm/repack.cpp
    )
endif()

# Build shared library: libnova_whisper.so
add_library(nova_whisper SHARED
    # JNI bridge
    whisper_jni.cpp
    # whisper.cpp core
    ${WHISPER_CPP_DIR}/src/whisper.cpp
    # ggml (whisper's own copy)
    ${WHISPER_GGML_SOURCES}
)

target_include_directories(nova_whisper PRIVATE
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/ggml/include
    ${WHISPER_CPP_DIR}/ggml/src
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu
    ${WHISPER_CPP_DIR}/src
)

target_compile_definitions(nova_whisper PRIVATE
    GGML_USE_CPU
    _GNU_SOURCE
    GGML_VERSION="0.0.0"
    GGML_COMMIT="unknown"
    WHISPER_VERSION="0.0.0"
)

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_definitions(nova_whisper PRIVATE
        __ARM_NEON
        __ARM_FEATURE_FMA
        __ARM_FEATURE_MATMUL_INT8
        __ARM_FEATURE_DOTPROD
    )
    target_compile_options(nova_whisper PRIVATE
        -march=armv8.2-a+dotprod+fp16+i8mm
        -ffast-math
        -fno-finite-math-only
    )
endif()

target_compile_options(nova_whisper PRIVATE
    -pthread
    -fvisibility=hidden
)

target_link_libraries(nova_whisper
    ${log-lib}
    ${android-lib}
)


# ============================================================
# Piper TTS - Text-to-Speech library (optional)
# Only builds if ONNX Runtime prebuilt is available.
# Cloud Bridge provides ElevenLabs TTS as alternative.
# ============================================================

set(ONNXRUNTIME_DIR "${CMAKE_CURRENT_SOURCE_DIR}/onnxruntime")
set(ONNXRUNTIME_LIB "${ONNXRUNTIME_DIR}/lib/arm64-v8a/libonnxruntime.so")

if(EXISTS ${ONNXRUNTIME_LIB})
    set(PIPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/piper")
    set(PIPER_PHONEMIZE_DIR "${PIPER_DIR}/piper-phonemize")

    add_library(nova_piper SHARED
        piper_jni.cpp
        ${PIPER_DIR}/src/cpp/piper.cpp
    )

    add_library(onnxruntime SHARED IMPORTED)
    set_target_properties(onnxruntime PROPERTIES
        IMPORTED_LOCATION ${ONNXRUNTIME_LIB}
    )

    target_include_directories(nova_piper PRIVATE
        ${PIPER_DIR}/src/cpp
        ${PIPER_PHONEMIZE_DIR}/src
        ${ONNXRUNTIME_DIR}/include
    )

    target_compile_definitions(nova_piper PRIVATE _GNU_SOURCE)

    if(${ANDROID_ABI} STREQUAL "arm64-v8a")
        target_compile_options(nova_piper PRIVATE
            -march=armv8.2-a+dotprod+fp16+i8mm -ffast-math)
    endif()

    target_compile_options(nova_piper PRIVATE -pthread -fvisibility=hidden)
    target_link_libraries(nova_piper onnxruntime ${log-lib} ${android-lib})
else()
    message(WARNING "ONNX Runtime not found at ${ONNXRUNTIME_LIB} — skipping Piper TTS build. Use ElevenLabs via Cloud Bridge instead.")
endif()
