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
  --enable-android-media-codec \
  --enable-gpl \
  --enable-lame \
  --enable-libvpx \
  --enable-libvorbis \
  --enable-opus \
  --enable-x264 \
  --disable-arm-v7a-neon
```

当前本地产物按上述配置构建，启用 Android zlib 与 MediaCodec，并额外包含 `libmp3lame`、`libx264`、`libvpx`、
`libvorbis` 和 `libopus` 编码器。启用 `x264` 后，FFmpegKit 产物按 GPLv3
分发；不要将它与未启用 GPL 的旧 AAR 混用。

AAR SHA-256：

```text
CA186ABB26A43B45D1DBC526A10787544D5BF150F23C192532DF4E46D8F2733A
```

## ONNX Runtime Java

ONNX Runtime 1.28.2 的 Java API 位于：

```text
app/libs/onnxruntime-java-1.28.2.jar
```

四个 ABI 对应的 `libonnxruntime4j_jni.so` 位于 `app/src/main/jniLibs/`。
这些文件提取自 sherpa-onnx Android 构建使用的上游包：

```text
https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip
```

原始包 SHA-256：

```text
01518867F78241138B6AA25925802E843A4FA9085AF8D303D49E35BBB52AFF4D
```

## sherpa-onnx

## ✅ 集成已完成

sherpa-onnx v1.13.8 及项目内的 SenseVoice CTC duration 补丁已成功集成。

### 集成内容

1. **Kotlin API 源码**

   - 位置：`app/src/main/java/com/k2fsa/sherpa/onnx/`
   - 包含所有必需的 API 类（OfflineRecognizer、OfflineStream 等）
2. **Native 库文件**

   - 位置：`app/src/main/jniLibs/`
   - 四个 ABI 的 `libsherpa-onnx-jni.so`、`libsherpa-onnx-c-api.so` 和 `libsherpa-onnx-cxx-api.so` 均由 v1.13.8 应用项目补丁后重新构建，并移除调试符号
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

- 原始包：`sherpa-onnx-v1.13.8-android.tar.bz2` (46MB)
- 下载地址：https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8
- 源码提交：`11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`
- JNI 补丁：`app/libs/sherpa-onnx-v1.13.8-sensevoice-ctc-durations.patch`

原始包 SHA-256：`2FF63469A71CB6009AA2E3ED5F4A670F8ABDCBE4BB9FFD23776AFC792A6B4F44`

在 sherpa-onnx v1.13.8 源码根目录应用补丁：

```bash
git apply /path/to/SubtitleEditforAndroid/app/libs/sherpa-onnx-v1.13.8-sensevoice-ctc-durations.patch
```

补丁统计 CTC greedy search 中连续相同非 blank token 的帧数，并由 SenseVoice
结果转换器输出秒单位的 `durations`。它不是基于指定文本的强制对齐；本次升级保持原补丁范围，
未修改通用 `offline-recognizer-ctc-impl.h`，因此 Parakeet CTC 返回的 `durations` 仍为空。

### 注意事项

- 无需额外的 AAR 依赖
- Native 库会根据设备架构自动加载
- 确保 minSdk >= 24 (Android 7.0)
