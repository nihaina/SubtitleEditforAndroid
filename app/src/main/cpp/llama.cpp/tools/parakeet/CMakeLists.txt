# parakeet.cpp (NVIDIA Parakeet ASR on ggml), vendored from mudler/parakeet.cpp
# v0.4.0 — see VENDORED_FROM.txt.
#
# Upstream builds its own third_party/ggml. Here the library links the in-tree
# ggml target instead, so the process has exactly one ggml: one backend registry,
# one set of GGML_BACKEND_DL CPU variants (i8mm/KleidiAI), and the "fd:N" path
# handling in ggml_fopen applies to Parakeet GGUFs for free.
#
# CLI, server, tests and the -march=native/LLAMAFILE forcing are all dropped;
# the Android build sets those globally.

set(PARAKEET_SRC
    src/parakeet.cpp
    src/model.cpp
    src/parakeet_capi.cpp
    src/common.cpp
    src/audio_io.cpp
    src/transcription_json.cpp
    src/model_loader.cpp
    src/backend.cpp
    src/ggml_graph.cpp
    src/fft.cpp
    src/mel.cpp
    src/mel_gpu.cpp
    src/subsampling.cpp
    src/relpos_attention.cpp
    src/conformer.cpp
    src/pos_enc.cpp
    src/encoder.cpp
    src/streaming_encoder.cpp
    src/streaming.cpp
    src/ctc_decoder.cpp
    src/prediction.cpp
    src/joint.cpp
    src/prompt_kernel.cpp
    src/tdt.cpp
    src/rnnt.cpp
    src/transducer_batch.cpp
    src/tokenizer.cpp
    src/search.cpp
    src/transcription.cpp)

add_library(parakeet STATIC ${PARAKEET_SRC})

target_include_directories(parakeet
    PUBLIC  ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src
            ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

target_compile_definitions(parakeet PRIVATE PARAKEET_VERSION="0.4.0")
target_compile_features(parakeet PRIVATE cxx_std_17)

target_link_libraries(parakeet PUBLIC ggml)
