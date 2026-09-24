# Qwen3 ForcedAligner ONNX 导出

## 已验证的导出方式

使用官方 `Qwen/Qwen3-ForcedAligner-0.6B` 权重、`qwen-asr==0.0.6` 和
`transformers==4.57.6`。`-hf` 是另一套 Transformers 原生实现，不能直接混用本脚本。

本次电脑环境为 Python 3.12.7、PyTorch 2.5.1+cpu、ONNX 1.22.0、ONNX Runtime
1.20.1。导出和验证都在 CPU 上执行，不需要 CUDA、FlashAttention 或 Android 工具链。

本机 Python 环境：`D:\Temp\qwen3-export-venv\Scripts\python.exe`。
依赖从清华 PyPI 镜像安装，模型与处理器文件来自 Qwen 官方 ModelScope 仓库。
下载的每个文件都按照 ModelScope 返回的 SHA-256 校验；清单保存在
`build/qwen3-forced-aligner/official/modelscope_manifest.json`。

在项目根目录执行：

```powershell
$env:PYTHONIOENCODING = 'utf-8'
& D:\Temp\qwen3-export-venv\Scripts\python.exe `
  tools/export_qwen3_forced_aligner_onnx.py `
  --model build/qwen3-forced-aligner/official `
  --output build/qwen3-forced-aligner/forced_aligner.onnx
```

原始官方权重保存在 `build/qwen3-forced-aligner/official/`。换电脑时可用官方
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
& D:\Temp\qwen3-export-venv\Scripts\python.exe `
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

对应单元测试覆盖完整双文件安装、复制/校验失败、取消和回滚，以及基于官方
processor 生成的四组 token ID/时间戳位置样例。桌面数值校验、Android 构建和
单元测试不能替代真机端到端及内存测试；FP16/INT8 量化仍未包含在本次产物中。
