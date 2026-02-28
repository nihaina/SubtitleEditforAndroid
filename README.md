# 字幕编辑 for Android (SubtitleEditforAndroid)

一款功能强大的 Android 字幕编辑工具，专为字幕爱好者和专业人士打造。

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![API](https://img.shields.io/badge/API-33%2B-brightgreen?style=for-the-badge)
![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)

## 📱 应用预览

| 文件浏览 | 字幕编辑 | 批量转换 |
|:---:|:---:|:---:|
| 📁 文件管理器 | ✏️ 可视化编辑 | 🔄 格式转换 |

## ✨ 主要功能

### 📝 字幕编辑
- **多格式支持**: 完美支持 SRT、LRC、TXT 字幕格式
- **可视化编辑**: 点击即可编辑时间轴和字幕文本
- **时间偏移**: 支持毫秒/秒/分/时级别的时间调整
- **多选操作**: 支持批量选择、复制、粘贴、删除
- **源视图模式**: 直接编辑原始文本内容

### 🔍 搜索功能
- 支持搜索字幕文本和时间
- 高亮显示搜索结果
- 快速跳转匹配项

### 📦 文件管理
- 内置文件浏览器
- 支持多种文本编码 (UTF-8, GBK, BIG5 等)
- 批量转换字幕格式

### 💾 草稿箱
- 自动保存编辑草稿
- 随时恢复未完成的编辑

### 🤖 AI 翻译
- 集成 AI 翻译功能
- 支持批量翻译选中字幕
- 可配置翻译模型和语言

### ⚙️ 个性化设置
- 自定义默认编码
- AI API 配置
- 翻译语言设置

## 🛠️ 技术栈

- **语言**: Kotlin
- **最低 SDK**: Android 13 (API 33)
- **目标 SDK**: Android 14 (API 34)
- **架构**: MVVM
- **主要库**:
  - AndroidX Core KTX
  - Material Design Components
  - RecyclerView
  - ViewBinding
  - Kotlin Coroutines
  - OkHttp3

## 📋 项目结构

```
app/src/main/java/com/subtitleedit/
├── adapter/              # RecyclerView 适配器
│   ├── FileListAdapter.kt
│   └── SubtitleAdapter.kt
├── model/                # 数据模型
│   └── SubtitleEntry.kt
├── util/                 # 工具类
│   ├── AiTranslator.kt   # AI 翻译
│   ├── DraftManager.kt   # 草稿管理
│   ├── FileUtils.kt      # 文件操作
│   ├── SettingsManager.kt # 设置管理
│   ├── SubtitleParser.kt # 字幕解析
│   └── TimeUtils.kt      # 时间工具
├── MainActivity.kt       # 主界面 (文件浏览)
├── EditorActivity.kt     # 编辑器界面
├── BatchConvertActivity.kt # 批量转换
├── DraftsActivity.kt     # 草稿箱
├── SettingsActivity.kt   # 设置
└── ToolsActivity.kt      # 工具页面
```

## 🚀 快速开始

### 环境要求
- Android Studio Hedgehog 或更高版本
- JDK 17
- Android SDK 33+

### 构建步骤

1. 克隆项目
```bash
git clone https://github.com/yourusername/SubtitleEditforAndroid.git
cd SubtitleEditforAndroid
```

2. 使用 Android Studio 打开项目

3. 同步 Gradle 依赖

4. 运行应用
```bash
./gradlew assembleDebug
```

## 📖 使用说明

### 编辑字幕
1. 在主界面浏览并选择字幕文件
2. 点击文件进入编辑界面
3. 点击时间轴或文本进行编辑
4. 长按字幕显示更多操作选项
5. 使用菜单保存或导出

### 批量转换
1. 进入批量转换页面
2. 选择源格式和目标格式
3. 选择要转换的文件
4. 点击开始转换

### AI 翻译
1. 在设置中配置 AI API Key
2. 在编辑器中选择要翻译的字幕
3. 长按选择"AI 翻译"
4. 预览并应用翻译结果

## 📄 支持的字幕格式

| 格式 | 扩展名 | 描述 |
|:---:|:---:|:---|
| SRT | .srt | 最常用的字幕格式，支持时间轴 |
| LRC | .lrc | 歌词文件格式，常用于音乐 |
| TXT | .txt | 纯文本格式，无时间轴 |

## 🔐 权限说明

| 权限 | 用途 |
|:---|:---|
| READ/WRITE_EXTERNAL_STORAGE | 读写字幕文件 (Android 12 及以下) |
| MANAGE_EXTERNAL_STORAGE | 管理外部存储 (Android 13+) |
| INTERNET | AI 翻译功能 |
| ACCESS_NETWORK_STATE | 网络状态检测 |

## 📝 更新日志

### v1.0.0
- 初始版本发布
- 支持 SRT/LRC/TXT 格式
- 文件浏览器
- 字幕编辑功能
- 批量转换
- 草稿箱
- AI 翻译
- 搜索功能

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情

## 🙏 致谢

感谢以下开源项目：
- [AndroidX](https://developer.android.com/jetpack/androidx)
- [Material Components](https://material.io/develop/android)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [OkHttp](https://square.github.io/okhttp/)

## 📧 联系方式

如有问题或建议，请通过以下方式联系：
- Email: your.email@example.com
- Issues: [GitHub Issues](https://github.com/yourusername/SubtitleEditforAndroid/issues)

---

**Made with ❤️ by SubtitleEdit Team**
