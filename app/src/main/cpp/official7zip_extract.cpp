#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <fcntl.h>
#include <limits>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "third_party/7zip/CPP/Common/MyCom.h"
#include "third_party/7zip/CPP/Windows/PropVariant.h"
#include "third_party/7zip/CPP/7zip/IPassword.h"
#include "third_party/7zip/CPP/7zip/UI/Common/OpenArchive.h"

namespace {

using NWindows::NCOM::CPropVariant;
using Clock = std::chrono::steady_clock;

void ThrowJava(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

UString FromJava(JNIEnv *env, jstring value) {
    UString result;
    if (!value) return result;
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) return result;
    const jsize length = env->GetStringLength(value);
    for (jsize i = 0; i < length; ++i) {
        UInt32 ch = chars[i];
        if (ch >= 0xd800 && ch <= 0xdbff && i + 1 < length &&
            chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
            ch = 0x10000 + ((ch - 0xd800) << 10) + chars[++i] - 0xdc00;
        }
        result += static_cast<wchar_t>(ch);
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring ToJava(JNIEnv *env, const UString &value) {
    std::vector<jchar> chars;
    chars.reserve(value.Len());
    for (unsigned i = 0; i < value.Len(); ++i) {
        UInt32 ch = static_cast<UInt32>(value[i]);
        if (ch > 0xffff && ch <= 0x10ffff) {
            ch -= 0x10000;
            chars.push_back(static_cast<jchar>(0xd800 + (ch >> 10)));
            chars.push_back(static_cast<jchar>(0xdc00 + (ch & 0x3ff)));
        } else {
            chars.push_back(static_cast<jchar>(ch));
        }
    }
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}

jlong JavaSize(UInt64 value) {
    return static_cast<jlong>(std::min(value, static_cast<UInt64>(std::numeric_limits<jlong>::max())));
}

class ThreadEnv {
    JavaVM *vm_;
    bool attached_ = false;
public:
    JNIEnv *env = nullptr;
    explicit ThreadEnv(JavaVM *vm) : vm_(vm) {
        if (vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (vm_->AttachCurrentThread(&env, nullptr) == JNI_OK) attached_ = true;
            else env = nullptr;
        }
    }
    ~ThreadEnv() { if (attached_) vm_->DetachCurrentThread(); }
};

// 7-Zip may report progress from a codec thread. Keep Java references global,
// serialize callbacks, and deliver the original callback exception on the caller.
class JavaCallbacks {
    JavaVM *vm_ = nullptr;
    jobject callback_ = nullptr;
    jthrowable failure_ = nullptr;
    jmethodID opened_ = nullptr, open_ = nullptr, progress_ = nullptr, complete_ = nullptr, testEntry_ = nullptr;
    std::mutex mutex_;
    Clock::time_point lastProgress_{};
    bool failed_ = false;
    const bool testing_;

    bool CaptureException(JNIEnv *env) {
        if (!env->ExceptionCheck()) return false;
        jthrowable failure = env->ExceptionOccurred();
        env->ExceptionClear();
        if (!failure_) failure_ = static_cast<jthrowable>(env->NewGlobalRef(failure));
        env->DeleteLocalRef(failure);
        failed_ = true;
        return true;
    }
public:
    explicit JavaCallbacks(JNIEnv *env, jobject callback, bool testing = false) : testing_(testing) {
        env->GetJavaVM(&vm_);
        callback_ = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        if (!cls) return;
        if (testing_) testEntry_ = env->GetMethodID(cls, "onEntry", "(Ljava/lang/String;JZZ)V");
        else opened_ = env->GetMethodID(cls, "onArchiveOpened", "([Ljava/lang/String;)V");
        if (!testing_ && !env->ExceptionCheck()) open_ = env->GetMethodID(cls, "openEntry", "(Ljava/lang/String;JZZJ)I");
        if (!env->ExceptionCheck()) progress_ = env->GetMethodID(cls, "onProgress", "(JJ)V");
        if (!testing_ && !env->ExceptionCheck()) complete_ = env->GetMethodID(cls, "onEntryComplete", "(Z)V");
        env->DeleteLocalRef(cls);
    }
    ~JavaCallbacks() {
        ThreadEnv thread(vm_);
        if (thread.env) {
            if (failure_) thread.env->DeleteGlobalRef(failure_);
            if (callback_) thread.env->DeleteGlobalRef(callback_);
        }
    }
    bool Ready() const {
        return callback_ && progress_ && (testing_ ? testEntry_ != nullptr : opened_ && open_ && complete_);
    }
    HRESULT Fail(const char *type, const char *message) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (failed_) return E_ABORT;
        ThreadEnv thread(vm_);
        if (!thread.env) return E_FAIL;
        ThrowJava(thread.env, type, message);
        CaptureException(thread.env);
        return E_ABORT;
    }
    HRESULT TestEntry(const UString &name, jlong size, bool directory, bool link) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (failed_) return E_ABORT;
        ThreadEnv thread(vm_);
        if (!thread.env) return E_FAIL;
        JNIEnv *env = thread.env;
        jstring path = ToJava(env, name);
        if (CaptureException(env)) return E_ABORT;
        env->CallVoidMethod(callback_, testEntry_, path, size, directory, link);
        env->DeleteLocalRef(path);
        return CaptureException(env) ? E_ABORT : S_OK;
    }
    HRESULT Opened(const UStringVector &paths) {
        std::lock_guard<std::mutex> lock(mutex_);
        ThreadEnv thread(vm_);
        if (!thread.env) return E_FAIL;
        JNIEnv *env = thread.env;
        jclass stringClass = env->FindClass("java/lang/String");
        if (CaptureException(env)) return E_ABORT;
        jobjectArray values = env->NewObjectArray(paths.Size(), stringClass, nullptr);
        env->DeleteLocalRef(stringClass);
        if (CaptureException(env)) return E_ABORT;
        for (unsigned i = 0; i < paths.Size(); ++i) {
            jstring path = ToJava(env, paths[i]);
            if (CaptureException(env)) { env->DeleteLocalRef(values); return E_ABORT; }
            env->SetObjectArrayElement(values, i, path);
            env->DeleteLocalRef(path);
            if (CaptureException(env)) { env->DeleteLocalRef(values); return E_ABORT; }
        }
        env->CallVoidMethod(callback_, opened_, values);
        env->DeleteLocalRef(values);
        return CaptureException(env) ? E_ABORT : S_OK;
    }
    HRESULT Open(const UString &name, jlong size, bool directory, bool link, jlong mtime, int &fd) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (failed_) return E_ABORT;
        ThreadEnv thread(vm_);
        if (!thread.env) return E_FAIL;
        JNIEnv *env = thread.env;
        jstring path = ToJava(env, name);
        if (CaptureException(env)) return E_ABORT;
        fd = env->CallIntMethod(callback_, open_, path, size, directory, link, mtime);
        env->DeleteLocalRef(path);
        return CaptureException(env) ? E_ABORT : S_OK;
    }
    HRESULT Progress(UInt64 completed, UInt64 total, bool force = false) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (failed_) return E_ABORT;
        const auto now = Clock::now();
        if (!force && now - lastProgress_ < std::chrono::milliseconds(100)) return S_OK;
        lastProgress_ = now;
        ThreadEnv thread(vm_);
        if (!thread.env) return E_FAIL;
        thread.env->CallVoidMethod(callback_, progress_, JavaSize(completed), JavaSize(total));
        return CaptureException(thread.env) ? E_ABORT : S_OK;
    }
    HRESULT Complete(bool success) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (failed_) return E_ABORT;
        ThreadEnv thread(vm_);
        if (!thread.env) return E_FAIL;
        thread.env->CallVoidMethod(callback_, complete_, static_cast<jboolean>(success));
        return CaptureException(thread.env) ? E_ABORT : S_OK;
    }
    bool Rethrow(JNIEnv *env) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (failure_) env->Throw(failure_);
        return failed_;
    }
};

struct ExtractionState {
    JavaCallbacks &java;
    UString password;
    bool hasPassword;
    std::atomic<bool> passwordAsked{false};
    std::atomic<UInt64> written{0}, total{0};
    std::atomic<bool> writeFailed{false};
    bool streamFormat = false;
    bool testMode = false;
    bool countTestEntries = false;
    bool layeredTest = false;
    UInt64 testLayerBase = 0;
    UInt64 testEntryCount = 0;
    UInt64 testDeclaredBytes = 0;
    UInt64 maxTestEntries = 0;
    UInt64 maxTestBytes = 0;

    ExtractionState(JavaCallbacks &callbacks, const UString &text, bool defined)
        : java(callbacks), password(text), hasPassword(defined) {}
    HRESULT Progress(bool force = false) {
        const UInt64 completed = written.load();
        const UInt64 overhead = countTestEntries ? 0 : 64ULL * 1024 * 1024;
        const UInt64 testLimit = maxTestBytes > std::numeric_limits<UInt64>::max() - overhead
            ? std::numeric_limits<UInt64>::max() : maxTestBytes + overhead;
        if (testMode && completed >= testLayerBase && completed - testLayerBase > testLimit)
            return java.Fail("java/io/IOException", "解压数据超过安全限制");
        return java.Progress(completed, streamFormat || layeredTest ? 0 : total.load(), force);
    }
    HRESULT Password(BSTR *result) {
        passwordAsked = true;
        if (!hasPassword) return E_ABORT;
        *result = SysAllocString(password);
        return *result ? S_OK : E_OUTOFMEMORY;
    }
};

class OpenCallbacks final : public IOpenCallbackUI {
    ExtractionState &state_;
public:
    explicit OpenCallbacks(ExtractionState &state) : state_(state) {}
    HRESULT Open_CheckBreak() override { return state_.Progress(); }
    HRESULT Open_SetTotal(const UInt64 *, const UInt64 *) override { return state_.Progress(); }
    HRESULT Open_SetCompleted(const UInt64 *, const UInt64 *) override { return state_.Progress(); }
    HRESULT Open_Finished() override { return state_.Progress(); }
    HRESULT Open_CryptoGetTextPassword(BSTR *password) override { return state_.Password(password); }
};

class FdOutput final : public ISequentialOutStream, public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_1(ISequentialOutStream)
    Z7_IFACE_COM7_IMP(ISequentialOutStream)
    int fd_;
    ExtractionState &state_;
public:
    FdOutput(int fd, ExtractionState &state) : fd_(fd), state_(state) {}
    ~FdOutput() { Close(); }
    void Close() {
        if (fd_ >= 0) {
            if (close(fd_) != 0) state_.writeFailed = true;
            fd_ = -1;
        }
    }
};

Z7_COM7F_IMF(FdOutput::Write(const void *data, UInt32 size, UInt32 *processedSize)) {
    if (processedSize) *processedSize = 0;
    RINOK(state_.Progress())
    if (state_.testMode) {
        // Test mode decodes into a counting sink, never a filesystem output.
        // This also bounds raw streams whose headers do not carry their size.
        state_.written.fetch_add(size);
        RINOK(state_.Progress())
        if (processedSize) *processedSize = size;
        return S_OK;
    }
    const auto *bytes = static_cast<const unsigned char *>(data);
    UInt32 completed = 0;
    while (completed < size) {
        const ssize_t written = write(fd_, bytes + completed, size - completed);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) {
            state_.writeFailed = true;
            return E_FAIL;
        }
        completed += static_cast<UInt32>(written);
        state_.written.fetch_add(static_cast<UInt64>(written));
        if (processedSize) *processedSize = completed;
        RINOK(state_.Progress())
    }
    return S_OK;
}

HRESULT HasStringProperty(IInArchive *archive, UInt32 index, PROPID id, bool &present) {
    CPropVariant prop;
    RINOK(archive->GetProperty(index, id, &prop))
    present = prop.vt == VT_BSTR && prop.bstrVal && prop.bstrVal[0] != 0;
    return S_OK;
}

class ExtractCallbacks final : public IArchiveExtractCallback,
                               public ICryptoGetTextPassword,
                               public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_2(IArchiveExtractCallback, ICryptoGetTextPassword)
    Z7_IFACE_COM7_IMP(IProgress)
    Z7_IFACE_COM7_IMP(IArchiveExtractCallback)
    Z7_IFACE_COM7_IMP(ICryptoGetTextPassword)
    const CArc &arc_;
    ExtractionState &state_;
    CMyComPtr<ISequentialOutStream> stream_;
    FdOutput *streamSpec_ = nullptr;
    bool entryOpened_ = false;
    bool encrypted_ = false;
public:
    Int32 operationError = NArchive::NExtract::NOperationResult::kOK;
    bool passwordError = false;
    ExtractCallbacks(const CArc &arc, ExtractionState &state) : arc_(arc), state_(state) {}
    void CloseOutput() {
        if (streamSpec_) streamSpec_->Close();
        streamSpec_ = nullptr;
        stream_.Release();
    }
    ~ExtractCallbacks() { CloseOutput(); }
};

Z7_COM7F_IMF(ExtractCallbacks::SetTotal(UInt64 total)) {
    state_.total = total;
    return state_.Progress();
}
Z7_COM7F_IMF(ExtractCallbacks::SetCompleted(const UInt64 *)) { return state_.Progress(); }
Z7_COM7F_IMF(ExtractCallbacks::CryptoGetTextPassword(BSTR *password)) { return state_.Password(password); }
Z7_COM7F_IMF(ExtractCallbacks::PrepareOperation(Int32)) { return state_.Progress(); }

Z7_COM7F_IMF(ExtractCallbacks::GetStream(UInt32 index, ISequentialOutStream **output, Int32 askMode)) {
    *output = nullptr;
    CloseOutput();
    entryOpened_ = false;
    const Int32 expectedMode = state_.testMode ? NArchive::NExtract::NAskMode::kTest
                                             : NArchive::NExtract::NAskMode::kExtract;
    if (askMode != expectedMode) return state_.Progress();
    UString name;
    RINOK(arc_.GetItem_Path(index, name))
    bool directory = false, symbolic = false, hard = false, special = false;
    RINOK(Archive_IsItem_Dir(arc_.Archive, index, directory))
    RINOK(Archive_GetItemBoolProp(arc_.Archive, index, kpidEncrypted, encrypted_))
    RINOK(HasStringProperty(arc_.Archive, index, kpidSymLink, symbolic))
    RINOK(HasStringProperty(arc_.Archive, index, kpidHardLink, hard))
    RINOK(Archive_GetItemBoolProp(arc_.Archive, index, kpidIsAnti, special))
    CPropVariant mode;
    RINOK(arc_.Archive->GetProperty(index, kpidPosixAttrib, &mode))
    if (mode.vt == VT_UI4) {
        const UInt32 type = mode.ulVal & S_IFMT;
        if (type == S_IFLNK) symbolic = true;
        else if (type != 0 && type != S_IFREG && type != S_IFDIR) special = true;
    }
    UInt64 size = 0;
    bool defined = false;
    RINOK(arc_.GetItem_Size(index, size, defined))
    if (state_.testMode) {
        if (symbolic || hard || special)
            return state_.java.Fail("java/io/IOException", "压缩包包含不支持的链接或特殊文件");
        if (state_.countTestEntries) {
            if (++state_.testEntryCount > state_.maxTestEntries)
                return state_.java.Fail("java/io/IOException", "压缩包条目数量超过安全限制");
            if (!directory && defined) {
                if (size > state_.maxTestBytes - state_.testDeclaredBytes)
                    return state_.java.Fail("java/io/IOException", "解压数据超过安全限制");
                state_.testDeclaredBytes += size;
            }
            RINOK(state_.java.TestEntry(name, defined ? JavaSize(size) : -1, directory, false))
        }
        if (!directory) {
            streamSpec_ = new FdOutput(-1, state_);
            stream_ = streamSpec_;
            *output = stream_;
            (*output)->AddRef();
        }
        return state_.Progress();
    }
    CArcTime mtime;
    RINOK(arc_.GetItem_MTime(index, mtime))
    const UInt64 ticks = mtime.Get_FILETIME_as_UInt64();
    constexpr UInt64 epoch = 116444736000000000ULL;
    const jlong millis = mtime.Def && ticks >= epoch ? JavaSize((ticks - epoch) / 10000) : 0;
    int fd = -1;
    RINOK(state_.java.Open(name, defined ? JavaSize(size) : -1, directory,
                         symbolic || hard || special, millis, fd))
    entryOpened_ = true;
    // The Java resolver rejects links. Do not ever materialize one if a caller
    // supplies an output descriptor despite the unsupported type flag.
    if (symbolic || hard || special) return E_NOTIMPL;
    if (directory || fd < 0) {
        if (!directory && defined) state_.written.fetch_add(size);
        // Handlers may omit SetOperationResult for skipped items. Complete this
        // descriptor-free entry now, so Kotlin always receives one completion.
        entryOpened_ = false;
        return state_.java.Complete(true);
    }
    int duplicate;
    do { duplicate = fcntl(fd, F_DUPFD_CLOEXEC, STDERR_FILENO + 1); }
    while (duplicate < 0 && errno == EINTR);
    if (duplicate < 0) {
        state_.writeFailed = true;
        return E_FAIL;
    }
    streamSpec_ = new FdOutput(duplicate, state_);
    stream_ = streamSpec_;
    *output = stream_;
    (*output)->AddRef();
    return S_OK;
}

Z7_COM7F_IMF(ExtractCallbacks::SetOperationResult(Int32 result)) {
    CloseOutput();
    const bool success = result == NArchive::NExtract::NOperationResult::kOK && !state_.writeFailed;
    if (entryOpened_) {
        entryOpened_ = false;
        RINOK(state_.java.Complete(success))
    }
    if (!success) {
        operationError = result;
        passwordError = result == NArchive::NExtract::NOperationResult::kWrongPassword ||
            (encrypted_ && (result == NArchive::NExtract::NOperationResult::kCRCError ||
                            result == NArchive::NExtract::NOperationResult::kDataError));
        return E_FAIL;
    }
    return state_.Progress();
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_subtitleedit_util_OfficialSevenZip_extractNative(
    JNIEnv *env, jclass, jstring archivePath, jstring password, jobject callback) {
    try {
        if (!archivePath || !callback) {
            ThrowJava(env, "java/lang/IllegalArgumentException", "压缩包路径和解压回调不能为空");
            return;
        }
        const UString path = FromJava(env, archivePath);
        const UString text = FromJava(env, password);
        if (env->ExceptionCheck()) return;
        JavaCallbacks java(env, callback);
        if (!java.Ready() || env->ExceptionCheck()) return;
        ExtractionState state(java, text, password != nullptr);
        OpenCallbacks open(state);
        CREATE_CODECS_OBJECT
        HRESULT result = codecs->Load();
        CArchiveLink link;
        if (result == S_OK) {
            CObjectVector<COpenType> types;
            CIntVector excludedFormats;
            COpenOptions options;
            options.codecs = codecs;
            options.props = nullptr;
            options.types = &types;
            options.excludedFormats = &excludedFormats;
            options.filePath = path;
            result = link.Open_Strict(options, &open);
        }
        if (result == S_OK) result = java.Opened(link.VolumePaths);
        bool passwordError = false;
        Int32 operationError = NArchive::NExtract::NOperationResult::kOK;
        if (result == S_OK && !link.Arcs.IsEmpty()) {
            const CArc &arc = link.Arcs.Back();
            if (arc.FormatIndex >= 0) {
                const CArcInfoEx &format = codecs->Formats[static_cast<unsigned>(arc.FormatIndex)];
                state.streamFormat = format.Is_GZip() || format.Is_BZip2() || format.Is_Xz();
            }
            auto *extract = new ExtractCallbacks(arc, state);
            CMyComPtr<IArchiveExtractCallback> extractRef = extract;
            result = arc.Archive->Extract(nullptr, static_cast<UInt32>(-1), false, extractRef);
            extract->CloseOutput();
            passwordError = extract->passwordError;
            operationError = extract->operationError;
        } else if (result == S_OK) result = E_FAIL;
        if (result == S_OK) result = state.Progress(true);
        if (java.Rethrow(env)) return;
        if (result != S_OK) {
            if (passwordError || (state.passwordAsked && !state.writeFailed)) {
                ThrowJava(env, "com/subtitleedit/util/ArchivePasswordRequiredException",
                          password ? "压缩包密码错误" : "压缩包需要密码");
            } else {
                const std::string message = "7-Zip 解压失败（错误码 " +
                    std::to_string(static_cast<long>(result)) + ", 条目结果 " +
                    std::to_string(operationError) + "）";
                ThrowJava(env, "java/io/IOException", message.c_str());
            }
        }
    } catch (const std::bad_alloc &) {
        ThrowJava(env, "java/lang/OutOfMemoryError", "7-Zip 解压内存不足");
    } catch (...) {
        ThrowJava(env, "java/io/IOException", "7-Zip 解压发生异常");
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_subtitleedit_util_OfficialSevenZip_testNative(
    JNIEnv *env, jclass, jstring archivePath, jstring password, jobject callback,
    jint maxEntries, jlong maxBytes) {
    try {
        if (!archivePath || !callback || maxEntries <= 0 || maxBytes <= 0) {
            ThrowJava(env, "java/lang/IllegalArgumentException", "压缩包路径、测试回调或限制无效");
            return nullptr;
        }
        const UString path = FromJava(env, archivePath);
        const UString text = FromJava(env, password);
        if (env->ExceptionCheck()) return nullptr;
        JavaCallbacks java(env, callback, true);
        if (!java.Ready() || env->ExceptionCheck()) return nullptr;
        ExtractionState state(java, text, password != nullptr);
        state.testMode = true;
        state.maxTestEntries = static_cast<UInt64>(maxEntries);
        state.maxTestBytes = static_cast<UInt64>(maxBytes);
        OpenCallbacks open(state);
        CREATE_CODECS_OBJECT
        HRESULT result = state.Progress(true);
        if (result == S_OK) result = codecs->Load();
        CArchiveLink link;
        if (result == S_OK) {
            CObjectVector<COpenType> types;
            CIntVector excludedFormats;
            COpenOptions options;
            options.codecs = codecs;
            options.props = nullptr;
            options.types = &types;
            options.excludedFormats = &excludedFormats;
            options.filePath = path;
            result = link.Open_Strict(options, &open);
        }
        bool passwordError = false;
        Int32 operationError = NArchive::NExtract::NOperationResult::kOK;
        if (result == S_OK && link.Arcs.IsEmpty()) result = E_FAIL;
        state.layeredTest = link.Arcs.Size() > 1;
        UInt64 finalLayerBytes = 0;
        // Validate each compression layer as well as the final container. Opening
        // a TAR inside GZip must not replace the outer stream's CRC/footer test.
        for (unsigned i = 0; result == S_OK && i < link.Arcs.Size(); ++i) {
            const CArc &arc = link.Arcs[i];
            // Split is only a concatenation wrapper and has no checksum to test.
            // Its Extract() rewinds and consumes the same volume streams owned by
            // the already-open inner archive. Reading it here invalidates the
            // inner multi-stream's cached positions and can turn a correct AES
            // password into a spurious CRC/password failure.
            if (arc.FormatIndex >= 0 &&
                codecs->Formats[static_cast<unsigned>(arc.FormatIndex)].Is_Split()) continue;
            state.countTestEntries = i + 1 == link.Arcs.Size();
            state.testLayerBase = state.written.load();
            state.total = 0;
            state.streamFormat = false;
            if (arc.FormatIndex >= 0) {
                const CArcInfoEx &format = codecs->Formats[static_cast<unsigned>(arc.FormatIndex)];
                state.streamFormat = format.Is_GZip() || format.Is_BZip2() || format.Is_Xz();
            }
            auto *test = new ExtractCallbacks(arc, state);
            CMyComPtr<IArchiveExtractCallback> testRef = test;
            result = arc.Archive->Extract(nullptr, static_cast<UInt32>(-1), true, testRef);
            passwordError = test->passwordError;
            operationError = test->operationError;
            finalLayerBytes = state.written.load() - state.testLayerBase;
        }
        if (result == S_OK) result = state.Progress(true);
        if (java.Rethrow(env)) return nullptr;
        if (result != S_OK) {
            if (passwordError || (state.passwordAsked && !state.writeFailed)) {
                ThrowJava(env, "com/subtitleedit/util/ArchivePasswordRequiredException",
                          password ? "压缩包密码错误" : "压缩包需要密码");
            } else {
                const std::string message = "7-Zip 压缩包测试失败（错误码 " +
                    std::to_string(static_cast<long>(result)) + ", 条目结果 " +
                    std::to_string(operationError) + "）";
                ThrowJava(env, "java/io/IOException", message.c_str());
            }
            return nullptr;
        }
        jlongArray stats = env->NewLongArray(2);
        if (stats) {
            const jlong values[] = {JavaSize(state.testEntryCount),
                                   JavaSize(std::max(state.testDeclaredBytes, finalLayerBytes))};
            env->SetLongArrayRegion(stats, 0, 2, values);
        }
        return stats;
    } catch (const std::bad_alloc &) {
        ThrowJava(env, "java/lang/OutOfMemoryError", "7-Zip 测试内存不足");
    } catch (...) {
        ThrowJava(env, "java/io/IOException", "7-Zip 压缩包测试发生异常");
    }
    return nullptr;
}
