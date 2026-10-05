#pragma once

// LMPlayground: the app builds ggml with GGML_BACKEND_DL=ON, so the CPU backend
// lives in separate libggml-cpu-*.so files that are dlopen'd at runtime and the
// direct-linkage helpers (ggml_backend_cpu_init, ggml_backend_is_cpu,
// ggml_backend_cpu_set_n_threads) are not exported by libggml.so. Reach the CPU
// backend through the device registry instead, which is how llama.cpp itself
// does it and which works in both linkage modes.

#include "ggml-backend.h"

namespace pk {

inline ggml_backend_t backend_cpu_init() {
    ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    return dev ? ggml_backend_dev_init(dev, nullptr) : nullptr;
}

inline bool backend_is_cpu(ggml_backend_t backend) {
    if (!backend) return false;
    ggml_backend_dev_t dev = ggml_backend_get_device(backend);
    return dev && ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU;
}

inline void backend_cpu_set_n_threads(ggml_backend_t backend, int n_threads) {
    if (!backend) return;
    ggml_backend_dev_t dev = ggml_backend_get_device(backend);
    if (!dev) return;
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
    if (!reg) return;
    auto set_n_threads = (ggml_backend_set_n_threads_t)
        ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads");
    if (set_n_threads) set_n_threads(backend, n_threads);
}

} // namespace pk
