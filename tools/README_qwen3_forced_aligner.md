# Qwen3 ForcedAligner ONNX 导出

## 已验证的导出方式

使用官方 `Qwen/Qwen3-ForcedAligner-0.6B` 权重、`qwen-asr==0.0.6` 和
`transformers==4.57.6`。`-hf` 是另一套 Transformers 原生实现，不能直接混用本脚本。

参考验证环境为 Python 3.12.7、PyTorch 2.5.1+cpu、ONNX 1.22.0、ONNX Runtime
1.20.1。导出和验证都在 CPU 上执行，不需要 CUDA、FlashAttention 或 Android 工具链。

建议在项目根目录创建独立虚拟环境；以下 PowerShell 示例使用被 Git 忽略的
`.venv-qwen3-export/`。依赖可从清华 PyPI 镜像安装，模型与处理器文件来自
Qwen 官方 ModelScope 仓库。参考验证使用的文件已按 ModelScope 返回的 SHA-256
校验；下载清单保存在
`build/qwen3-forced-aligner/official/modelscope_manifest.json`。

在项目根目录创建环境，并将上述依赖安装到该环境中：

```powershell
python -m venv .venv-qwen3-export
$qwenPython = Join-Path $PWD '.venv-qwen3-export/Scripts/python.exe'
```

使用该环境执行导出：

```powershell
$env:PYTHONIOENCODING = 'utf-8'
& $qwenPython `
  tools/export_qwen3_forced_aligner_onnx.py `
  --model build/qwen3-forced-aligner/official `
  --output build/qwen3-forced-aligner/forced_aligner.onnx
```

原始官方权重保存在 `build/qwen3-forced-aligner/official/`。可用官方
ModelScope 下载命令获取：

```text
modelscope download --model Qwen/Qwen3-ForcedAligner-0.6B --local_dir build/qwen3-forced-aligner/official
```

## 输出文件与输入约定

- `forced_aligner.onnx`：约 7.76 MB 的模型图。
- `forced_aligner.onnx.data`：约 3.67 GB 的 FP32 权重。与图文件放在同一目录，保留文件名。
- `forced_aligner.validation.json`：文件大小、SHA-256 和三组动态长度验证结果。
- `parity_*.npz`：对应的输入张量和官方 PyTorch logits，供复查。
- `forced_aligner.graph.onnx`：无权重的中间图，不能单独用于推理。

导出使用 ONNX opset 18，固定 batch=1，音频帧数、文本长度可变。

| 输入 | 类型 | 形状 |
| --- | --- | --- |
| `input_ids` | int64 | `[1, sequence]` |
| `input_features` | float32 | `[1, 128, frames]` |
| `attention_mask` | int64 | `[1, sequence]` |
| `feature_attention_mask` | int64 | `[1, frames]` |
| 输出 `logits` | float32 | `[1, sequence, 5000]` |

音频特征掩码支持右侧补零；有效特征须从第一帧开始连续排列。
`<timestamp>` 的 token ID 为 151705，类别步长为 80 ms。
音频占位符必须按官方 processor 展开，数量为：

```text
((valid_frames % 100 + 7) // 8) + (valid_frames // 100) * 13
```

导出脚本将官方音频分块中的 Python 列表和 `pad_sequence` 改写为张量索引，
复用官方卷积和注意力层及其权重。参考后端是 `qwen-asr 0.0.6` 的 FP32/eager，
没有量化、重新训练或替换模型权重；未声称与 FlashAttention 2 后端等价。

## 真实音频验证

```powershell
$qwenPython = Join-Path $PWD '.venv-qwen3-export/Scripts/python.exe'
& $qwenPython `
  tools/verify_qwen3_forced_aligner_onnx.py `
  --model build/qwen3-forced-aligner/official `
  --onnx build/qwen3-forced-aligner/forced_aligner.onnx `
  --audio build/qwen3-forced-aligner/asr_zh.wav `
  --text '甚至出现交易几乎停滞的情况。' `
  --language Chinese
```

输入为官方中文样例，时长 4.204 秒，使用官方音频预处理、分词和占位符展开。
参考模型与导出包装层 logits 完全一致；ONNX 的最大绝对误差约 `1.05e-5`，
全部 26 个起止时间戳类别一致，生成 13 个逐字时间段。
结果见 `forced_aligner.audio-validation.json`，其中时间单位为毫秒。
这证明该样例的数值一致性，不代表完成所有语言、长音频或移动端精度评测。

## Android 接入

在“模型管理 → Qwen3-ASR”点击“导入 ONNX + DATA（两个文件）”，在系统文件
选择器中长按、多选同次导出的 `forced_aligner.onnx` 与 `forced_aligner.onnx.data`。
两个文件保留导出时的原名，一起复制至暂存目录；ONNX Runtime 校验成功后整组
替换，失败或复制过程中取消则保留旧模型。0.6B / 1.7B 共用这组对齐模型。

JNI 仍输出一个 `<|audio_pad|>`。Kotlin 在得到 log-mel 的有效帧数后按上面的
官方公式展开 input IDs，平移所有 timestamp positions，并用展开后的长度
构造 attention mask。缺少外部权重文件时不再显示为已配置。

ASR 下载包的 tokenizer 不含对齐用的 `<timestamp>`。JNI 会检查基础词表与
官方 token ID，并仅在对齐使用的内存实例中补齐 `<timestamp>=151705`
（`special=false`、`normalized=false`）。不会修改 ASR tokenizer 文件，也无需
重新下载或导入 ONNX 权重。已包含该标记的官方 ForcedAligner tokenizer 同样
受支持；ID 冲突或不兼容时明确报错。该检查在开始识别前执行。

对应单元测试覆盖完整双文件安装、复制/校验失败、取消和回滚，以及基于官方
processor 生成的四组 token ID/时间戳位置样例。桌面数值校验、Android 构建和
单元测试不能替代真机端到端及内存测试；FP16/INT8 量化仍未包含在本次产物中。

### 可选真机回归测试

`QwenForcedAlignmentNativeTest` 使用真实 tokenizer 检查 JNI 编码、音频占位符
展开、错误信息和重复关闭；不提供测试参数时自动跳过。准备一个可由应用读取的
独立测试目录（不要覆盖已安装模型），包含：

- `tokenizer/`：应用下载的六个 ASR tokenizer 文件，保留不含 `<timestamp>` 的原配置。
- `qwen3_forced_alignment_inputs.json`：复制自 `app/src/test/resources/`。
- `asr_zh.wav` 与 `forced_aligner.audio-validation.json`：可选，来自上述桌面音频验证。

构建并安装对应 ABI 的 debug APK 和 `standardDebugAndroidTest` APK 后运行：

```text
adb shell am instrument -w -e class com.subtitleedit.util.QwenForcedAlignmentNativeTest -e qwenTestDirectory /data/user/0/com.subtitleedit/files/qwen-alignment-test com.subtitleedit.test/androidx.test.runner.AndroidJUnitRunner
```

如需执行包含 log-mel 和 ONNX 推理的第三项测试，再添加
`-e qwenAlignerModel /data/user/0/com.subtitleedit/files/models/qwen3-asr/forced-aligner/forced_aligner.onnx`。
该测试只读取模型文件，将中文样例的全部起止时间戳与官方参考结果逐一比较。

## 本地产物与隐私

模型权重、转换产物、音频样例和验证报告统一放在被忽略的 `build/` 目录；
报告可能包含输入文件的完整路径和转录文本，分享前应先检查。个人环境说明可保存在
`*.local.md` 或 `local/`，不写入共享文档。共享示例使用项目相对路径或环境变量，
避免包含本机用户名、盘符、设备序列号和访问凭据。
