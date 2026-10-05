#include <jni.h>

#include "llama.h"
#include "chat.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>
#include <unistd.h>

namespace {

struct LocalModel {
    llama_model * model = nullptr;
    common_chat_templates_ptr templates;
    std::atomic<bool> cancelled{false};
    std::mutex generation_mutex;
};

struct LoadProgressContext {
    JNIEnv * env = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
};

std::once_flag backend_once;

bool abort_callback(void * user_data) {
    return static_cast<LocalModel *>(user_data)->cancelled.load(std::memory_order_relaxed);
}

std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<std::string> jobject_array_to_strings(JNIEnv * env, jobjectArray values) {
    std::vector<std::string> result;
    const jsize size = env->GetArrayLength(values);
    result.reserve(size);
    for (jsize i = 0; i < size; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        result.push_back(jstring_to_string(env, value));
        env->DeleteLocalRef(value);
    }
    return result;
}

// Return the largest prefix ending on a complete UTF-8 code point. Token
// pieces may split a code point; NewStringUTF must never receive that suffix.
size_t complete_utf8_prefix(const std::string & text) {
    if (text.empty()) return 0;
    size_t pos = text.size() - 1;
    while (pos > 0 && (static_cast<unsigned char>(text[pos]) & 0xc0) == 0x80) --pos;
    if ((static_cast<unsigned char>(text[pos]) & 0xc0) == 0x80) return 0;
    const auto first = static_cast<unsigned char>(text[pos]);
    size_t expected = 1;
    if ((first & 0xe0) == 0xc0) expected = 2;
    else if ((first & 0xf0) == 0xe0) expected = 3;
    else if ((first & 0xf8) == 0xf0) expected = 4;
    const size_t actual = text.size() - pos;
    if (expected > 1 && actual < expected) return pos;
    return text.size();
}

void callback_delta(JNIEnv * env, jobject callback, jmethodID method, const std::string & text) {
    if (text.empty()) return;
    jstring value = env->NewStringUTF(text.c_str());
    if (value == nullptr) return;
    env->CallVoidMethod(callback, method, value);
    env->DeleteLocalRef(value);
}

void callback_complete(
    JNIEnv * env,
    jobject callback,
    jmethodID method,
    jint status,
    const std::string & response,
    const std::string & error) {
    jstring output = env->NewStringUTF(response.c_str());
    jstring message = env->NewStringUTF(error.c_str());
    if (output != nullptr && message != nullptr) {
        env->CallVoidMethod(callback, method, status, output, message);
    }
    if (output != nullptr) env->DeleteLocalRef(output);
    if (message != nullptr) env->DeleteLocalRef(message);
}

bool model_load_progress(float progress, void * user_data) {
    auto * context = static_cast<LoadProgressContext *>(user_data);
    if (context == nullptr || context->env == nullptr || context->callback == nullptr ||
        context->method == nullptr) {
        return true;
    }
    context->env->CallVoidMethod(context->callback, context->method, progress);
    // A disconnected UI callback must not abort model loading. The service
    // still receives the final native handle and can report completion.
    if (context->env->ExceptionCheck()) {
        context->env->ExceptionClear();
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_subtitleedit_localllm_LocalLlmNative_loadModelWithProgress(
    JNIEnv * env, jobject, jstring path, jboolean disable_repack, jobject callback) {
    std::call_once(backend_once, [] { llama_backend_init(); });
    const std::string model_path = jstring_to_string(env, path);
    if (model_path.empty()) return 0;

    auto model = std::make_unique<LocalModel>();
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.use_extra_bufts = disable_repack == JNI_FALSE;
    LoadProgressContext progress_context;
    if (callback != nullptr) {
        progress_context.env = env;
        progress_context.callback = callback;
        const jclass callback_class = env->GetObjectClass(callback);
        progress_context.method = env->GetMethodID(callback_class, "onProgress", "(F)V");
        if (progress_context.method != nullptr) {
            params.progress_callback = model_load_progress;
            params.progress_callback_user_data = &progress_context;
        }
        env->DeleteLocalRef(callback_class);
    }
    // A SAF-selected model is passed through the service as /proc/self/fd/N.
    // llama.cpp expects a regular path, and the descriptor is kept open by the
    // service for the whole lifetime of the resident model.
    model->model = llama_model_load_from_file(model_path.c_str(), params);
    if (model->model == nullptr) return 0;

    model->templates = common_chat_templates_init(model->model, "");
    if (!model->templates) {
        llama_model_free(model->model);
        return 0;
    }
    return reinterpret_cast<jlong>(model.release());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_subtitleedit_localllm_LocalLlmNative_modelSize(
    JNIEnv *, jobject, jlong handle) {
    auto * model = reinterpret_cast<LocalModel *>(handle);
    return model == nullptr || model->model == nullptr
        ? 0
        : static_cast<jlong>(llama_model_size(model->model));
}

extern "C" JNIEXPORT void JNICALL
Java_com_subtitleedit_localllm_LocalLlmNative_unloadModel(JNIEnv *, jobject, jlong handle) {
    auto * model = reinterpret_cast<LocalModel *>(handle);
    if (model == nullptr) return;
    std::lock_guard<std::mutex> lock(model->generation_mutex);
    model->cancelled.store(true, std::memory_order_relaxed);
    if (model->model != nullptr) llama_model_free(model->model);
    delete model;
}

extern "C" JNIEXPORT void JNICALL
Java_com_subtitleedit_localllm_LocalLlmNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto * model = reinterpret_cast<LocalModel *>(handle);
    if (model != nullptr) model->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_subtitleedit_localllm_LocalLlmNative_generate(
    JNIEnv * env,
    jobject,
    jlong handle,
    jint context_size,
    jfloat temperature,
    jobjectArray role_values,
    jobjectArray content_values,
    jboolean thinking_enabled,
    jobject callback) {
    auto * model = reinterpret_cast<LocalModel *>(handle);
    if (model == nullptr || model->model == nullptr || callback == nullptr) return 1;
    const auto roles = jobject_array_to_strings(env, role_values);
    const auto contents = jobject_array_to_strings(env, content_values);
    if (roles.empty() || roles.size() != contents.size()) return 1;

    const jclass callback_class = env->GetObjectClass(callback);
    const jmethodID delta_method = env->GetMethodID(callback_class, "onDelta", "(Ljava/lang/String;)V");
    const jmethodID complete_method = env->GetMethodID(
        callback_class, "onComplete", "(ILjava/lang/String;Ljava/lang/String;)V");
    if (delta_method == nullptr || complete_method == nullptr) return 1;

    std::lock_guard<std::mutex> lock(model->generation_mutex);
    model->cancelled.store(false, std::memory_order_relaxed);

    std::vector<common_chat_msg> messages;
    messages.reserve(roles.size());
    for (size_t i = 0; i < roles.size(); ++i) {
        common_chat_msg message;
        message.role = roles[i];
        message.content = contents[i];
        messages.push_back(std::move(message));
    }
    common_chat_templates_inputs inputs;
    inputs.messages = messages;
    inputs.add_generation_prompt = true;
    inputs.enable_thinking = thinking_enabled == JNI_TRUE;
    const auto rendered = common_chat_templates_apply(model->templates.get(), inputs);
    if (rendered.prompt.empty()) {
        callback_complete(env, callback, complete_method, 1, "", "聊天模板渲染失败");
        return 0;
    }

    common_chat_parser_params parser_params(rendered);
    const bool parser_initialized = !rendered.parser.empty();
    if (parser_initialized) {
        // Keep reasoning in a stable <think> block so the Kotlin layer can
        // render it consistently across models and thinking modes.
        parser_params.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        parser_params.parse_tool_calls = false;
        parser_params.parser.load(rendered.parser);
    }

    const auto * vocab = llama_model_get_vocab(model->model);
    const int32_t token_count = -llama_tokenize(
        vocab, rendered.prompt.c_str(), static_cast<int32_t>(rendered.prompt.size()),
        nullptr, 0, true, true);
    if (token_count <= 0) {
        callback_complete(env, callback, complete_method, 1, "", "本地模型提示词编码失败");
        return 0;
    }
    std::vector<llama_token> prompt_tokens(token_count);
    if (llama_tokenize(vocab, rendered.prompt.c_str(), static_cast<int32_t>(rendered.prompt.size()),
                       prompt_tokens.data(), token_count, true, true) < 0) {
        callback_complete(env, callback, complete_method, 1, "", "本地模型提示词编码失败");
        return 0;
    }

    auto context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(std::max(1024, static_cast<int>(context_size)));
    context_params.n_batch = std::min<uint32_t>(512, context_params.n_ctx);
    context_params.n_ubatch = context_params.n_batch;
    // Keep generation aligned with LMPlayground: reserve two system/runtime
    // cores and cap inference at four workers to avoid starving the UI.
    const long online_cpus = sysconf(_SC_NPROCESSORS_ONLN);
    const int n_threads = std::max(1L, std::min(4L, online_cpus > 0 ? online_cpus - 2 : 1L));
    context_params.n_threads = n_threads;
    context_params.n_threads_batch = n_threads;
    context_params.abort_callback = abort_callback;
    context_params.abort_callback_data = model;
    llama_context * context = llama_init_from_model(model->model, context_params);
    if (context == nullptr) {
        callback_complete(env, callback, complete_method, 1, "", "本地模型上下文创建失败");
        return 0;
    }

    auto sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.001f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    int status = 0;
    std::string response;
    std::string normalized_emitted;
    size_t emitted = 0;
    bool stop_reached = false;

    auto normalized_response = [&](bool partial, size_t input_end) {
        const std::string input = response.substr(0, input_end);
        if (!parser_initialized) return input;
        try {
            const auto parsed = common_chat_parse(input, partial, parser_params);
            if (parsed.reasoning_content.empty()) return parsed.content;
            std::string normalized = "<think>" + parsed.reasoning_content;
            if (!parsed.content.empty()) {
                normalized += "</think>";
                normalized += parsed.content;
            }
            return normalized;
        } catch (...) {
            return input;
        }
    };

    auto emit_normalized_delta = [&](bool partial, size_t input_end) {
        const std::string normalized = normalized_response(partial, input_end);
        if (normalized.size() >= normalized_emitted.size() &&
            normalized.compare(0, normalized_emitted.size(), normalized_emitted) == 0) {
            if (normalized.size() > normalized_emitted.size()) {
                callback_delta(
                    env, callback, delta_method,
                    normalized.substr(normalized_emitted.size()));
            }
        } else if (!normalized.empty()) {
            // Parser state can be non-monotonic while a control-channel token
            // is incomplete. The final response remains authoritative.
            callback_delta(env, callback, delta_method, normalized);
        }
        normalized_emitted = normalized;
    };

    const size_t batch_size = std::min<size_t>(context_params.n_batch, prompt_tokens.size());
    for (size_t offset = 0; offset < prompt_tokens.size(); offset += batch_size) {
        if (model->cancelled.load(std::memory_order_relaxed)) {
            status = 2;
            break;
        }
        const size_t count = std::min(batch_size, prompt_tokens.size() - offset);
        auto batch = llama_batch_get_one(prompt_tokens.data() + offset, static_cast<int32_t>(count));
        if (llama_decode(context, batch) != 0) {
            status = 1;
            break;
        }
    }

    if (status == 0) {
        const size_t max_tokens = 2048;
        for (size_t generated = 0; generated < max_tokens; ++generated) {
            if (model->cancelled.load(std::memory_order_relaxed)) {
                status = 2;
                break;
            }
            const llama_token token = llama_sampler_sample(sampler, context, -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            char buffer[256];
            int piece_size = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, true);
            if (piece_size < 0) {
                std::vector<char> expanded(static_cast<size_t>(-piece_size));
                piece_size = llama_token_to_piece(vocab, token, expanded.data(), expanded.size(), 0, true);
                if (piece_size > 0) response.append(expanded.data(), static_cast<size_t>(piece_size));
            } else if (piece_size > 0) {
                response.append(buffer, static_cast<size_t>(piece_size));
            }

            size_t safe_end = response.size();
            for (const auto & stop : rendered.additional_stops) {
                if (stop.empty()) continue;
                if (response.size() >= stop.size() &&
                    response.compare(response.size() - stop.size(), stop.size(), stop) == 0) {
                    response.resize(response.size() - stop.size());
                    safe_end = response.size();
                    stop_reached = true;
                    break;
                }
                const size_t partial_start = string_find_partial_stop(response, stop);
                if (partial_start != std::string::npos) {
                    safe_end = std::min(safe_end, partial_start);
                }
            }
            const size_t complete = complete_utf8_prefix(response.substr(0, safe_end));
            if (complete > emitted) {
                emit_normalized_delta(true, complete);
                emitted = complete;
            }
            if (stop_reached) break;
            auto next = token;
            auto batch = llama_batch_get_one(&next, 1);
            if (llama_decode(context, batch) != 0) {
                status = 1;
                break;
            }
        }
    }

    const std::string final_response = normalized_response(false, response.size());
    if (final_response.size() > normalized_emitted.size() &&
        final_response.compare(0, normalized_emitted.size(), normalized_emitted) == 0) {
        callback_delta(env, callback, delta_method, final_response.substr(normalized_emitted.size()));
    } else if (normalized_emitted.empty() && !final_response.empty()) {
        callback_delta(env, callback, delta_method, final_response);
    }
    llama_sampler_free(sampler);
    llama_free(context);
    callback_complete(
        env, callback, complete_method, status, final_response,
        status == 2 ? "本地模型生成已取消" : (status == 0 ? "" : "本地模型解码失败"));
    return 0;
}
