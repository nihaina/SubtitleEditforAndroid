# 语音转字幕功能实现总结

## 已完成的工作

### 1. UI 布局更新 ✅
- 添加了模型选择区域（选择模型文件按钮 + 模型路径显示）
- 添加了模型下载指引链接
- 将进度条改为确定性进度条（0-100%）
- 添加了取消按钮

### 2. SettingsManager 扩展 ✅
- 添加了 `KEY_WHISPER_MODEL_PATH` - 保存模型路径
- 添加了 `KEY_WHISPER_MODEL_NAME` - 保存模型名称
- 提供了 getter/setter 方法

### 3. WhisperRecognizer 工具类 ✅
- 实现了 Whisper 识别核心功能
- 支持长音频分段处理（30秒/段）
- 支持进度回调
- 支持取消操作
- 自动释放资源

### 4. SpeechToSubtitleActivity 完整实现 ✅
- 文件选择（音频/视频）
- 模型选择和验证
- 音频预处理（转换为 16kHz PCM WAV）
- Whisper 识别
- 字幕生成（SRT/LRC/TXT）
- 字幕保存
- 进度显示和取消功能
- 错误处理

### 5. sherpa-onnx 集成 ✅
- **已完成源码集成**（v1.12.35）
- Kotlin API 源码已复制到 `app/src/main/java/com/k2fsa/sherpa/onnx/`
- Native 库（.so）已放置到 `app/src/main/jniLibs/`
- 支持架构：arm64-v8a, armeabi-v7a, x86, x86_64
- 无需额外的 AAR 依赖

## 集成方式说明

### sherpa-onnx 集成详情

**下载的包**：`sherpa-onnx-v1.12.35-android.tar.bz2` (42MB)

**包含内容**：
1. Native 库文件（.so）：
   - libonnxruntime.so (~19MB)
   - libsherpa-onnx-c-api.so (~4.8MB)
   - libsherpa-onnx-cxx-api.so (~410KB)
   - libsherpa-onnx-jni.so (~5.1MB)

2. Kotlin API 源码（从 GitHub 源码仓库获取）：
   - OfflineRecognizer.kt - 离线识别器
   - OfflineStream.kt - 音频流处理
   - FeatureConfig.kt - 特征配置
   - 以及其他 20+ 个 API 文件

**集成步骤**：
1. 解压 tar.bz2 包，获得 jniLibs 目录（包含各架构的 .so 文件）
2. 从 sherpa-onnx GitHub 源码克隆 kotlin-api 目录
3. 将 jniLibs 移动到 `app/src/main/jniLibs/`
4. 将 Kotlin API 文件复制到 `app/src/main/java/com/k2fsa/sherpa/onnx/`
5. 无需在 build.gradle.kts 中添加额外依赖

## 需要手动完成的步骤

### 下载 Whisper 模型
用户需要自行下载 Whisper 模型文件：

**推荐来源：**
- Hugging Face: https://huggingface.co/k2-fsa/sherpa-onnx-whisper-large-v3
- GitHub Releases: https://github.com/k2-fsa/sherpa-onnx/releases

**需要的文件：**
- encoder.onnx
- decoder.onnx
- tokens.txt

**模型选择：**
- Whisper Tiny (~40MB) - 快速，适合实时
- Whisper Base (~75MB) - 平衡性能和质量
- Whisper Small (~250MB) - 高质量
- Whisper Large V3 (~3GB) - 最高质量（你的需求）

## 使用流程

1. 启动应用，进入"工具" → "语音转字幕"
2. 点击"选择模型文件"，选择已下载的模型目录
3. 点击"选择文件"，选择音频或视频文件
4. 选择识别语言（支持 12 种语言 + 自动检测）
5. 选择输出格式（SRT/LRC/TXT）
6. 点击"开始转换"
7. 等待识别完成（显示实时进度）
8. 选择保存位置

## 技术特点

- **离线识别**：完全本地运行，无需网络
- **长音频支持**：自动分段处理，避免内存溢出
- **多语言支持**：支持 12 种语言识别
- **多格式输出**：支持 SRT、LRC、TXT 格式
- **实时进度**：显示详细的处理进度和状态
- **可取消**：支持随时取消转换
- **资源管理**：自动释放模型资源

## 性能预估

- **Whisper Large V3**：1分钟音频约需 30-60 秒处理
- **内存占用**：2-3GB（模型加载）+ 分段处理缓冲
- **建议设备**：8GB RAM 以上的 Android 设备

## 注意事项

1. **首次使用**：需要下载 Whisper 模型（sherpa-onnx 已集成）
2. **存储空间**：Large V3 模型约 3GB，确保有足够空间
3. **处理时间**：长音频识别耗时较长，1小时音频可能需要 30-60 分钟
4. **内存管理**：使用分段处理避免内存溢出
5. **权限**：需要存储权限读取音频和模型文件

## 文件清单

**新增文件：**
- `app/src/main/java/com/subtitleedit/util/WhisperRecognizer.kt`
- `app/src/main/java/com/k2fsa/sherpa/onnx/*.kt` (22个 Kotlin API 文件)
- `app/src/main/jniLibs/[arch]/*.so` (Native 库文件)
- `app/libs/README.md`
- `app/libs/sherpa-onnx-v1.12.35-android.tar.bz2` (原始包)

**修改文件：**
- `app/src/main/java/com/subtitleedit/SpeechToSubtitleActivity.kt`
- `app/src/main/res/layout/activity_speech_to_subtitle.xml`
- `app/src/main/java/com/subtitleedit/util/SettingsManager.kt`
- `app/build.gradle.kts`

## 下一步

1. 同步 Gradle 项目
2. 下载 Whisper 模型文件
3. 测试语音转字幕功能
4. 根据实际使用情况优化性能
