## FFmpegKitNext

FFmpegKitNext 8.1.0 的本地 Maven 产物位于：

```text
app/libs/ffmpeg-kit-next-maven/com/arthenica/ffmpeg-kit-next/8.1.0/
```

该 AAR 使用上游 `android-r27d` profile 在 API 24 上构建，包含
`armeabi-v7a`、`arm64-v8a`、`x86` 和 `x86_64`。重新生成时使用：

```bash
./nix-android.sh \
  -p android-r27d \
  --enable-android-zlib \
  --disable-arm-v7a-neon
```

AAR SHA-256：

```text
F4F34356D184CDB08C6CA96A35504D29931708E93EC0071E69C627DB9835C30B
```

## ONNX Runtime Java

ONNX Runtime 1.27.1 的 Java API 位于：

```text
app/libs/onnxruntime-java-1.27.1.jar
```

四个 ABI 对应的 `libonnxruntime4j_jni.so` 位于 `app/src/main/jniLibs/`。
这些文件提取自 sherpa-onnx Android 构建使用的上游包：

```text
https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.27.1/onnxruntime-android-1.27.1.zip
```

原始包 SHA-256：

```text
DEFADE26209F72CF4FA9769B18052C842833D6BEF12924595D26F03B995548CA
```

## sherpa-onnx

## ✅ 集成已完成

sherpa-onnx v1.13.5 及项目内的 SenseVoice CTC duration 补丁已成功集成。

### 集成内容

1. **Kotlin API 源码**

   - 位置：`app/src/main/java/com/k2fsa/sherpa/onnx/`
   - 包含所有必需的 API 类（OfflineRecognizer、OfflineStream 等）
2. **Native 库文件**

   - 位置：`app/src/main/jniLibs/`
   - 四个 ABI 的 `libsherpa-onnx-jni.so` 均由 v1.13.5 应用项目补丁后重新构建
   - arm64-v8a 额外启用 `SHERPA_ONNX_ENABLE_QNN=ON`
   - 支持架构：
     - arm64-v8a (主流 64 位设备)
     - armeabi-v7a (32 位设备)
     - x86 (模拟器)
     - x86_64 (64 位模拟器)

### 使用方法

直接在代码中导入使用：

```kotlin
import com.k2fsa.sherpa.onnx.*

val recognizer = OfflineRecognizer(config)
```

### 源文件

- 原始包：`sherpa-onnx-v1.13.5-android.tar.bz2` (45MB)
- 下载地址：https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.5
- JNI 补丁：`app/libs/sherpa-onnx-v1.13.5-sensevoice-ctc-durations.patch`

在 sherpa-onnx v1.13.5 源码根目录应用补丁：

```bash
git apply /path/to/SubtitleEditforAndroid/app/libs/sherpa-onnx-v1.13.5-sensevoice-ctc-durations.patch
```

### 注意事项

- 无需额外的 AAR 依赖
- Native 库会根据设备架构自动加载
- 确保 minSdk >= 24 (Android 7.0)
