# SubtitleEdit for Android

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![API](https://img.shields.io/badge/API-26%2B-brightgreen?style=for-the-badge)

给音声字幕翻译的时候，不方便用电脑，在手机上改发现没有合适的软件

就借助AI做了一个，功能参考了subtitle edit，操作逻辑主要根据个人使用习惯来做

目前主要完善基础的字幕编辑功能，一边做新功能一边优化操作逻辑一边修bug

可以根据波形图和频谱图快速对轴

## 主要功能

### 字幕编辑

- **多格式支持**: 支持 SRT、LRC、WebVTT、TXT 格式
- **可视化编辑**: 点击即可编辑时间轴和字幕文本
- **时间偏移**: 支持批量的毫秒/秒/分/时级别的时间调整
- **多选操作**: 支持批量选择、复制、粘贴、删除
- **源视图模式**: 直接查看编辑原始文本内容
- **格式转换**:支持批量转换 SRT、LRC、WebVTT 字幕格式，支持视频格式转化
- **音频对照**:可打开音频，根据波形图快速调整字幕时间轴，支持mp3，wav，flac等格式
- **快速打轴**:可以在波形图界面快速插入字幕
- **快速转录**:可以针对部分字幕所在时间段进行局部的语音转文字，用于对照参考
- **快速翻译**:编辑界面对于选中字幕快速翻译和修改应用
- **快速TTS**:朗读选中字幕，用于对照参考

### 语音转字幕

- **多模型支持**:支持 Whisper、SenseVoice 等 sherpa-onnx ASR 模型
- **多媒体输入**:支持选择音频或视频文件，可进行批量处理
- **多格式输出**:支持生成 SRT、LRC、TXT 字幕
- **语音分段**:支持使用 VAD 检测语音片段，也可调整相关识别参数
- **运行控制**:显示识别进度和详细日志，支持运行中取消

### 自动打轴

- **语音检测**:使用 VAD 自动检测音频中的语音片段
- **时间轴生成**:根据检测结果生成字幕时间轴
- **格式支持**:支持将时间轴保存为多种字幕格式

### 人声分离

- **本地分离**:使用 HTDemucs ONNX 模型在设备本地进行音轨分离
- **多音轨输出**:支持输出 Vocals、Drums、Bass、Other
- **模型兼容**:支持通用四轨模型和 FT 单音轨模型
- **智能选择**:单音轨优先使用对应 FT 模型，多音轨使用通用模型一次输出
- **运行控制**:显示分块进度和详细日志，支持运行中取消

### 音视频转换

- **格式转换**:支持常用音频和视频格式之间的转换
- **音频提取**:支持从视频文件中提取音频
- **输出管理**:可选择输出目录并处理同名文件冲突

### AI 功能

- **快速翻译**:对选中的字幕进行翻译、预览和应用
- **服务配置**:支持配置 AI 平台、API Key、模型和语言
- **自定义提示词**:可根据字幕翻译需求调整提示词

### 文件管理

- 内置文件浏览器，方便查找文件
- 支持多种文本编码 (UTF-8, GBK, BIG5 等)
- 音频文件对于同名的多个字幕格式可以选择性打开

### 草稿箱

- 手动保存编辑草稿
- 随时恢复未完成的编辑

## 环境要求

- JDK 17
- Android SDK 26+

### 构建步骤

1. 克隆项目

   ```bash
   git clone https://github.com/nihaina/SubtitleEditforAndroid
   cd SubtitleEditforAndroid
   ```
2. 打开项目
3. 同步 Gradle 依赖
4. 构建 Debug APK（普通版或 QNN 版）

   ```bash
   ./gradlew :app:assembleStandardDebug  # 不包含 QNN 运行库
   ./gradlew :app:assembleQnnDebug       # arm64 QNN 版
   ```

   Release 构建使用对应的 `assembleStandardRelease` 和
   `assembleQnnRelease` 任务。导出的 APK 位于
   `app/build/outputs/apk/<flavor>/<buildType>/export/`，普通版提供所有
   ABI，QNN 版仅提供 arm64-v8a APK。
5. 运行应用

## 使用说明

### 编辑字幕

1. 在主界面浏览并选择字幕或音频文件
2. 点击文件进入编辑界面
3. 点击时间轴或文本进行编辑
4. 长按字幕显示更多操作选项
5. 选择字幕后点击“对勾选字幕操作”进入二级菜单，只对选择字幕修改
6. 无论有没有选择字幕，只要没有通过“对勾选字幕操作”进行修改，所有修改将只对当前长按字幕有影响
7. 如果有音频文件，可以在波形图视图拖拽字幕快速修改时间戳
8. 在波形图视图长按快速打轴按钮，移动播放头，即可快速打轴
9. 记得用草稿箱备份并及时保存

### 批量转换

1. 进入批量转换页面
2. 选择目标格式
3. 选择要转换的文件
4. 选择输出路径
5. 点击开始转换

### AI 翻译

1. 在设置中配置 AI API Key
2. 在编辑器中选择要翻译的字幕
3. 长按选择"AI 翻译"
4. 预览并应用翻译结果

### 使用语音转字幕

1. 根据帮助下载对应模型文件：[sherpa-onnx ASR 模型](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)
2. 将模型文件解压并放入方便查找的文件夹
3. 在模型配置中选择对应的模型文件
4. 可根据实际需求在模型设置中设置 VAD 参数或更换 VAD 模型
5. 选择需要转录的音频或视频文件，根据需求选择语言、格式和输出目录，然后开始转换

## 权限说明

只需要存储权限，没有其他权限要求

欢迎提交 Issue 和 Pull Request！

本项目采用 **[GPL-3.0 License](https://www.gnu.org/licenses/gpl-3.0.html)** 授权

第三方组件的版权与许可信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 鸣谢

本项目在开发过程中，部分核心功能基于以下项目实现，感谢大佬的开源！

语音转字幕：[github.com/k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)

人声分离：[github.com/demixr/demixr-app](https://github.com/demixr/demixr-app)

## 后续更新规划

添加音频无损切割，无损合并

视频字幕的相关工作流程适配

更多字幕格式适配

字幕合并

......
