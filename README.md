![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![API](https://img.shields.io/badge/API-33%2B-brightgreen?style=for-the-badge)

给音声字幕翻译的时候，不方便用电脑，在手机上改发现没有合适的软件

就借助ai做了一个，功能基本是参考subtitle edit做的，后续应该会加上音频对照功能

## 主要功能

### 字幕编辑

- **多格式支持**: 完美支持 SRT、LRC、TXT 字幕格式
- **可视化编辑**: 点击即可编辑时间轴和字幕文本
- **时间偏移**: 支持毫秒/秒/分/时级别的时间调整
- **多选操作**: 支持批量选择、复制、粘贴、删除
- **源视图模式**: 直接编辑原始文本内容
- **音频对照**:可打开音频对照翻译，支持mp3，wav，flac等格式

### 搜索功能

- 支持搜索字幕文本和时间
- 高亮显示搜索结果
- 快速跳转匹配项

### 文件管理

- 内置文件浏览器
- 支持多种文本编码 (UTF-8, GBK, BIG5 等)
- 批量转换字幕格式

### 草稿箱

- 手动保存编辑草稿
- 随时恢复未完成的编辑

### AI 翻译

- 集成 AI 翻译功能
- 支持批量翻译选中字幕
- 可配置翻译模型和语言

### 个性化设置

- 自定义默认编码
- AI API 配置
- 翻译语言设置

## 环境要求

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

## 使用说明

### 编辑字幕

1. 在主界面浏览并选择字幕文件
2. 点击文件进入编辑界面
3. 点击时间轴或文本进行编辑
4. 长按字幕显示更多操作选项
5. 使用菜单保存或导出

### 批量转换

1. 进入批量转换页面
2. 选择目标格式
3. 选择要转换的文件
4. 点击开始转换

### AI 翻译

1. 在设置中配置 AI API Key
2. 在编辑器中选择要翻译的字幕
3. 长按选择"AI 翻译"
4. 预览并应用翻译结果

## 支持的字幕格式

| 格式 | 扩展名 | 描述                         |
| :--: | :----: | :--------------------------- |
| SRT |  .srt  | 最常用的字幕格式，支持时间轴 |
| LRC |  .lrc  | 歌词文件格式，常用于音乐     |
| TXT |  .txt  | 纯文本格式，无时间轴         |

## 权限说明

给存储权限就行



欢迎提交 Issue 和 Pull Request！

本项目采用 **[GPL-3.0 License](https://www.gnu.org/licenses/gpl-3.0.html)** 授权
