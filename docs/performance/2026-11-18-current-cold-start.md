# 2026-11-18 当前安装包冷启动核对

当前最明确的启动卡顿来源是首屏 Compose 组合、文字测量和布局集中占用主线程。调试运行时仍有 JIT 工作，但本次 DEX 打开耗时很小，不能继续沿用优化前“DEX 打开约 271 ms”的分解作为当前原因。

本次对 PKG110 / Android 15 当前安装的 1.2.6 / code 16 debug 包做一次进程冷启动 Perfetto 采集，未清理用户数据、系统文件缓存或改变编译策略。安装包 SHA-256 为 `327a035cee36ed88684b2020be84d3d9a129b52d4b0066dbc9ab83bbbedb218f`，与上一轮交付包 `9666cb21...` 不同，因此这些数据只代表当前设备安装包，不作为上一轮代码优化的 A/B 成绩。工作区 HEAD 为 `2e57f7f`。

| 当前观测 | 耗时 | 含义 |
| --- | ---: | --- |
| `am start -W TotalTime` | 638 ms | 单次带跟踪的进程冷启动，不能视为稳定中位数 |
| 首个主线程 `doFrame` | 272.44 ms | 实际运行 212.81 ms，可运行等待 5.28 ms，主要有应用自身工作 |
| 首次 `AndroidOwner:onMeasure` | 139.56 ms | 包含测量内子组合，不能与内部组合耗时相加 |
| 单次 `Compose:recompose` 最长 | 64.34 ms | 仍超过常见帧预算 |
| 首屏后一次 `measureAndLayout` | 129.73 ms | 主线程实际运行 128.87 ms，可运行等待 0.48 ms |
| 同一后续帧 `doFrame` | 156.63 ms | 首屏出现后仍有布局长帧 |
| `TextStringSimpleNode::measure` 最长 | 35.20 ms | 首次文字布局本身也有明显成本 |
| `performCreate:MainActivity` | 73.84 ms | Activity 初始化也在启动关键路径，但不是唯一大头 |
| `bindApplication` | 101.04 ms | 当前进程初始化成本 |
| `OpenDexFilesFromOat` | 3.93 ms | 本次已不是主要直接耗时 |

JIT 线程在整个 8 秒轨迹窗口累计运行约 1.23 CPU 秒，主线程约 0.74 CPU 秒。JIT 的累计时间包含后台并行和首屏后的工作，不能把它当作启动被阻塞 1.23 秒，也不能单靠这个数字归因全部卡顿。

包仍有 `DEBUGGABLE` 标志；系统 ART 当前状态为 `verify / bg-dexopt`。这说明安装后系统已有验证产物，启动条件与最初 `run-from-apk` 记录不同。没有因此启用 R8 或执行人为 dexopt。

原有目录监听重复覆盖发布已修复。结合此前扫描结束后仍有慢帧，以及本次主线程布局实际运行时间，下一步应优先减少首屏关键路径的构建、文字排版、子组合和再次布局工作。已有证据不足以将某个单独业务控件确定为所有启动长帧的唯一原因；进一步定位需要对首次 composition/measure 内部调用做更细采样。

原始轨迹及查询：[`tmp/startup-current-20261118`](../../tmp/startup-current-20261118/)，包括 `cold-trace.perfetto-trace`、`analysis.json`、`apk.json`、`cold-trace-launch.txt` 和 gfxinfo。测量与组合数字有嵌套，表中不得简单求和。本次仅一次采样，不与历史不同 APK、ART 状态和负载下的首屏时间计算收益。
