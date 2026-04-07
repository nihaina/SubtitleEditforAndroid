# sherpa-onnx 集成说明

## ✅ 集成已完成

sherpa-onnx v1.12.35 已成功集成到项目中。

### 集成内容

1. **Kotlin API 源码**
   - 位置：`app/src/main/java/com/k2fsa/sherpa/onnx/`
   - 包含所有必需的 API 类（OfflineRecognizer、OfflineStream 等）

2. **Native 库文件**
   - 位置：`app/src/main/jniLibs/`
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

- 原始包：`sherpa-onnx-v1.12.35-android.tar.bz2` (42MB)
- 下载地址：https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.35

### 注意事项

- 无需额外的 AAR 依赖
- Native 库会根据设备架构自动加载
- 确保 minSdk >= 24 (Android 7.0)
