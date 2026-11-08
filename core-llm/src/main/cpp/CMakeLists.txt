cmake_minimum_required(VERSION 3.22)
project(plamo-llm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)

# --- OpenCL（Adreno GPU）---------------------------------------------------
# 実行時は端末の /vendor/lib64/libOpenCL.so（public.libraries.txt で公開）を使う。
# ビルド時のリンク先として、KhronosのICDローダーを構成時にビルドしておく。
# AGPが同梱しないよう、ライブラリの出力先とは別の場所に作る（APKには入れない）。
set(OPENCL_ICD_DIR ${CMAKE_BINARY_DIR}/opencl-icd)
if(NOT EXISTS ${OPENCL_ICD_DIR}/libOpenCL.so)
    execute_process(
        COMMAND ${CMAKE_COMMAND} -S ${THIRD_PARTY}/OpenCL-ICD-Loader -B ${OPENCL_ICD_DIR} -G Ninja
                -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
                -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
                -DANDROID_ABI=${ANDROID_ABI} -DANDROID_PLATFORM=${ANDROID_PLATFORM}
                -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
                -DOPENCL_ICD_LOADER_HEADERS_DIR=${THIRD_PARTY}/OpenCL-Headers
        RESULT_VARIABLE icd_configure)
    execute_process(COMMAND ${CMAKE_COMMAND} --build ${OPENCL_ICD_DIR} --target OpenCL RESULT_VARIABLE icd_build)
    if(NOT icd_configure EQUAL 0 OR NOT icd_build EQUAL 0)
        message(FATAL_ERROR "OpenCL ICDローダーのビルドに失敗しました")
    endif()
endif()
set(OpenCL_INCLUDE_DIR ${THIRD_PARTY}/OpenCL-Headers CACHE PATH "" FORCE)
set(OpenCL_LIBRARY ${OPENCL_ICD_DIR}/libOpenCL.so CACHE FILEPATH "" FORCE)

# --- llama.cpp --------------------------------------------------------------
# CPUの設定はイテレーション0で端末計測したCLIと同じ。GPUはOpenCL（docs/03_翻訳コア.md）
# GPU（OpenCL）とCPUの計算部分は、実行時に読み込む別のライブラリ（libggml-opencl.so、libggml-cpu-*.so）にする
# （GGML_BACKEND_DL）。OpenCLの無い端末では libggml-opencl.so の読み込みだけが失敗し、CPUで動く。
# CPUは世代ごとに最適化した版をすべて作り、端末に合う版を選ぶ（GGML_CPU_ALL_VARIANTS）。
# 2026-11-06、APKを配布するため、REDMAGIC 11 Pro 以外の端末でも動くようにした（docs/02_アーキテクチャ.md）。
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
set(GGML_SYSTEM_ARCH      ARM CACHE STRING "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI     ON  CACHE BOOL "" FORCE)
set(GGML_OPENCL           ON  CACHE BOOL "" FORCE)

add_subdirectory(${THIRD_PARTY}/llama.cpp build-llama)

add_library(plamo-llm SHARED plamo_llm.cpp)
target_link_libraries(plamo-llm PRIVATE llama ggml android log)
# 16KBページの端末でも読み込めるようにする（NDK r28以降は既定だが明示する）
target_link_options(plamo-llm PRIVATE "-Wl,-z,max-page-size=16384")
