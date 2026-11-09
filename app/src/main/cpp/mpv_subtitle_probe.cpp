#include <jni.h>
#include <dlfcn.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cctype>
#include <clocale>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "third_party/mpv/client.h"

namespace {

// Use the packaged player runtime, but never its global editor playback handle.
class MpvApi {
public:
    MpvApi() : library_(dlopen("libmpv.so", RTLD_NOW | RTLD_LOCAL)) {
        if (!library_) throw std::runtime_error("Cannot load the mpv player runtime");
        try {
            create = symbol<decltype(create)>("mpv_create");
            initialize = symbol<decltype(initialize)>("mpv_initialize");
            terminateDestroy = symbol<decltype(terminateDestroy)>("mpv_terminate_destroy");
            setOptionString = symbol<decltype(setOptionString)>("mpv_set_option_string");
            command = symbol<decltype(command)>("mpv_command");
            waitEvent = symbol<decltype(waitEvent)>("mpv_wait_event");
            getProperty = symbol<decltype(getProperty)>("mpv_get_property");
            free = symbol<decltype(free)>("mpv_free");
            freeNodeContents = symbol<decltype(freeNodeContents)>("mpv_free_node_contents");
            errorString = symbol<decltype(errorString)>("mpv_error_string");
        } catch (...) {
            dlclose(library_);
            throw;
        }
    }

    ~MpvApi() { dlclose(library_); }
    MpvApi(const MpvApi &) = delete;
    MpvApi &operator=(const MpvApi &) = delete;

    decltype(&mpv_create) create = nullptr;
    decltype(&mpv_initialize) initialize = nullptr;
    decltype(&mpv_terminate_destroy) terminateDestroy = nullptr;
    decltype(&mpv_set_option_string) setOptionString = nullptr;
    decltype(&mpv_command) command = nullptr;
    decltype(&mpv_wait_event) waitEvent = nullptr;
    decltype(&mpv_get_property) getProperty = nullptr;
    decltype(&mpv_free) free = nullptr;
    decltype(&mpv_free_node_contents) freeNodeContents = nullptr;
    decltype(&mpv_error_string) errorString = nullptr;

    void check(int result, const char *operation) const {
        if (result < 0) throw std::runtime_error(std::string(operation) + ": " + errorString(result));
    }

private:
    void *library_;

    template<typename T>
    T symbol(const char *name) {
        void *address = dlsym(library_, name);
        if (!address) throw std::runtime_error(std::string("Missing mpv player function: ") + name);
        return reinterpret_cast<T>(address);
    }
};

bool propertyFlag(const MpvApi &api, mpv_handle *handle, const std::string &name) {
    int flag = 0;
    return api.getProperty(handle, name.c_str(), MPV_FORMAT_FLAG, &flag) >= 0 && flag != 0;
}

std::string propertyText(const MpvApi &api, mpv_handle *handle, const std::string &name) {
    char *value = nullptr;
    if (api.getProperty(handle, name.c_str(), MPV_FORMAT_STRING, &value) < 0) return {};
    std::string text = value ? value : "";
    api.free(value);
    return text;
}

bool isLyricKey(const std::string &key) {
    std::string lower;
    std::string compact;
    for (unsigned char character : key) {
        const char normalized = static_cast<char>(std::tolower(character));
        lower += normalized;
        if (std::isalnum(character)) compact += normalized;
    }
    return lower.rfind("lyrics-", 0) == 0 || compact == "lyrics" || compact == "unsyncedlyrics" ||
        compact == "uslt" || compact == "sylt" || compact == "lyr" || compact == "wmlyrics" ||
        compact == "wmlyricssynchronised" || compact == "wmlyricssynchronized";
}

std::vector<std::string> probe(const std::string &path, jlong timeoutMs) {
    // The libmpv client API requires a dot decimal separator, just like the editor wrapper.
    std::setlocale(LC_NUMERIC, "C");
    MpvApi api;
    std::unique_ptr<mpv_handle, decltype(api.terminateDestroy)> handle(api.create(), api.terminateDestroy);
    if (!handle) throw std::runtime_error("Cannot create an mpv subtitle probe");
    auto option = [&](const char *name, const char *value) {
        api.check(api.setOptionString(handle.get(), name, value), name);
    };
    auto scriptOption = [&](const char *name) {
        const int result = api.setOptionString(handle.get(), name, "no");
        // The bundled runtime disables Lua/JavaScript/plugins at build time.
        // Those builds omit these options because there are no scripts to load.
        if (result != MPV_ERROR_OPTION_NOT_FOUND) api.check(result, name);
    };
    option("config", "no");
    scriptOption("load-scripts");
    scriptOption("osc");
    option("terminal", "no");
    option("input-default-bindings", "no");
    option("input-terminal", "no");
    option("pause", "yes");
    option("vo", "null");
    option("ao", "null");
    option("hwdec", "no");
    option("sid", "no");
    option("idle", "yes");
    option("keep-open", "yes");
    // Match the editor's player options: inspect tracks stored in this source.
    option("sub-auto", "no");
    option("audio-file-auto", "no");
    option("access-references", "no");
    option("load-unsafe-playlists", "no");
    option("network-timeout", "5");
    option("demuxer-max-bytes", "67108864");
    api.check(api.initialize(handle.get()), "Initializing mpv");

    const char *arguments[] = {"loadfile", path.c_str(), "replace", nullptr};
    api.check(api.command(handle.get(), arguments), "Loading the subtitle source");
    const auto deadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(std::clamp<jlong>(timeoutMs, 500, 30000));
    bool loaded = false;
    while (std::chrono::steady_clock::now() < deadline) {
        mpv_event *event = api.waitEvent(handle.get(), 0.1);
        if (event->event_id == MPV_EVENT_FILE_LOADED) {
            loaded = true;
            break;
        }
        if (event->event_id == MPV_EVENT_SHUTDOWN) throw std::runtime_error("mpv stopped before loading the source");
        if (event->event_id == MPV_EVENT_END_FILE) {
            const auto *end = static_cast<mpv_event_end_file *>(event->data);
            if (end && end->reason == MPV_END_FILE_REASON_ERROR) api.check(end->error, "Reading source subtitles");
            throw std::runtime_error("mpv ended before loading the source");
        }
    }
    if (!loaded) throw std::runtime_error("Timed out while reading source subtitles with mpv");

    int64_t count = 0;
    api.check(api.getProperty(handle.get(), "track-list/count", MPV_FORMAT_INT64, &count), "Reading mpv tracks");
    if (count < 0 || count > 10000) throw std::runtime_error("Invalid mpv track count");
    std::vector<std::string> values;
    for (int64_t index = 0; index < count; ++index) {
        const std::string base = "track-list/" + std::to_string(index) + "/";
        if (propertyText(api, handle.get(), base + "type") != "sub" || propertyFlag(api, handle.get(), base + "external")) continue;
        int64_t ffIndex = -1;
        // mpv's track id is one-based and is NOT the source FFmpeg stream index.
        if (api.getProperty(handle.get(), (base + "ff-index").c_str(), MPV_FORMAT_INT64, &ffIndex) < 0 || ffIndex < 0) {
            throw std::runtime_error("The mpv subtitle track has no source stream index");
        }
        values.push_back(std::to_string(ffIndex));
        values.push_back(propertyText(api, handle.get(), base + "codec"));
        values.push_back(propertyText(api, handle.get(), base + "lang"));
        values.push_back(propertyText(api, handle.get(), base + "title"));
        values.push_back(propertyFlag(api, handle.get(), base + "default") ? "1" : "0");
    }
    const size_t trackCount = values.size() / 5;
    std::vector<std::string> lyrics;
    mpv_node metadata{};
    if (api.getProperty(handle.get(), "metadata", MPV_FORMAT_NODE, &metadata) >= 0) {
        std::unique_ptr<mpv_node, decltype(api.freeNodeContents)> releaseMetadata(&metadata, api.freeNodeContents);
        if (metadata.format == MPV_FORMAT_NODE_MAP && metadata.u.list) {
            const auto *entries = metadata.u.list;
            if (entries->num < 0 || entries->num > 10000) throw std::runtime_error("Invalid mpv metadata count");
            size_t bytes = 0;
            for (int index = 0; index < entries->num; ++index) {
                const char *key = entries->keys[index];
                const auto &value = entries->values[index];
                if (!key || !isLyricKey(key) || value.format != MPV_FORMAT_STRING || !value.u.string) continue;
                const std::string text(value.u.string);
                bytes += text.size();
                if (bytes > 64 * 1024 * 1024) throw std::runtime_error("Embedded lyric metadata is too large");
                lyrics.emplace_back(key);
                lyrics.push_back(text);
            }
        }
    }
    values.insert(values.begin(), {"mpv-media-1", std::to_string(trackCount), std::to_string(lyrics.size() / 2)});
    values.insert(values.end(), lyrics.begin(), lyrics.end());
    return values;
}

// JNI's modified UTF-8 cannot represent supplementary characters directly.
jstring utf8String(JNIEnv *env, jclass stringClass, jmethodID constructor, const std::string &text) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(bytes);
        return nullptr;
    }
    jstring encoding = env->NewStringUTF("UTF-8");
    if (!encoding) {
        env->DeleteLocalRef(bytes);
        return nullptr;
    }
    auto value = static_cast<jstring>(env->NewObject(stringClass, constructor, bytes, encoding));
    env->DeleteLocalRef(encoding);
    env->DeleteLocalRef(bytes);
    return value;
}

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_subtitleedit_mpv_MpvSubtitleProbe_nativeProbe(JNIEnv *env, jobject, jbyteArray pathBytes, jlong timeoutMs) {
    try {
        if (!pathBytes) throw std::runtime_error("Missing mpv subtitle source path");
        const jsize length = env->GetArrayLength(pathBytes);
        std::string path(static_cast<size_t>(length), '\0');
        env->GetByteArrayRegion(pathBytes, 0, length, reinterpret_cast<jbyte *>(path.data()));
        if (env->ExceptionCheck()) return nullptr;
        if (path.empty() || path.find('\0') != std::string::npos) throw std::runtime_error("Invalid mpv subtitle source path");
        const auto values = probe(path, timeoutMs);
        jclass stringClass = env->FindClass("java/lang/String");
        if (!stringClass) return nullptr;
        jmethodID constructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
        if (!constructor) return nullptr;
        jobjectArray output = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
        if (!output) return nullptr;
        for (size_t index = 0; index < values.size(); ++index) {
            jstring value = utf8String(env, stringClass, constructor, values[index]);
            if (env->ExceptionCheck() || !value) return nullptr;
            env->SetObjectArrayElement(output, static_cast<jsize>(index), value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) return nullptr;
        }
        return output;
    } catch (const std::exception &error) {
        jclass exception = env->FindClass("java/io/IOException");
        if (exception) env->ThrowNew(exception, error.what());
    } catch (...) {
        jclass exception = env->FindClass("java/io/IOException");
        if (exception) env->ThrowNew(exception, "mpv subtitle probing failed");
    }
    return nullptr;
}
