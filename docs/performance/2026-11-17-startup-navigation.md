# 2026-11-17 启动与主页切页实测

在 PKG110 真机（Android 15、SM8650）上复现了启动和主页切页卡顿。主要耗时落在主线程，发现了媒体库监听初始化的重复集合计算与通知，以及页面切换时的 Compose 组合、测量开销。GPU 与主线程磁盘等待没有表现为主要瓶颈。

测试使用已安装的 1.2.6 / versionCode 16 arm64 调试版。安装包 SHA-256 与工作区 `app-standard-arm64-v8a-debug.apk` 相同；源码 HEAD 为 `3c49826421fe523395ea37b900e31c419450c5ff`。本次未更改应用实现、清除应用数据或改变 ART 编译策略。

**测试方式与结果**

冷启动采用 `am force-stop` 后 `am start -W`，重复三次。这是进程冷启动，未清理系统文件缓存。连续切页按“媒体库 → 分类 → 数据统计 → 设置 → 目录”循环五次，共注入 25 次点击，shell 每次点击后等待 80 毫秒；加上输入命令耗时，整段约 3.8–5.0 秒。这模拟了尚未等 250 毫秒页面过渡结束便继续点击的情形，不代表 25 个页面均已完整显示。

| 场景 | 系统统计的卡顿帧 / 总帧 | 卡顿比例 | 其他实测 |
| --- | ---: | ---: | --- |
| 三次冷启动，未开启 Perfetto | 每次 5 帧，首屏总计仅 6–7 帧 | 不用小样本比例代表日常流畅度 | 首屏时间 971 / 946 / 835 ms，中位数 946 ms |
| 冷启动后首次快速切页，未开启 Perfetto | 40 / 85 | 47.06% | 对照组，同样存在明显卡顿 |
| 扫描期间首次快速切页，开启 Perfetto | 46 / 74 | 62.16% | 主线程最长 `doFrame` 382.45 ms |
| 同一进程再次快速切页，扫描仍在进行 | 41 / 162 | 25.31% | 主线程最长 `doFrame` 164.50 ms |
| 等待扫描结束，再次快速切页 | 31 / 226 | 13.72% | 主线程最长 `doFrame` 144.11 ms，GPU P95 6 ms |

上述卡顿数采用 `dumpsys gfxinfo` 的 `Janky frames`，不同于 legacy 计数，也不与 Perfetto FrameTimeline 的分类数混用。Perfetto 也捕获到了 `App Deadline Missed` 和 `Buffer Stuffing`。跟踪本身会增加开销，因此保留了不跟踪的对照；扫描完成、JIT 预热和缓存变热同时发生，以上自然对照不能当作某一项优化的独立收益。

扫描结束后，另测了每次点击后等待 350 毫秒的单页面往返，每组六次往返：

| 往返路径 | 卡顿帧 / 总帧 | 卡顿比例 | gfxinfo 帧耗时 P95 |
| --- | ---: | ---: | ---: |
| 目录 ↔ 媒体库 | 16 / 387 | 4.13% | 42 ms |
| 目录 ↔ 分类 | 26 / 337 | 7.72% | 77 ms |
| 目录 ↔ 数据统计 | 19 / 335 | 5.67% | 77 ms |
| 目录 ↔ 设置 | 19 / 355 | 5.35% | 61 ms |

**媒体库监听初始化存在可定位的重复工作**

配置的媒体库根目录为 `/storage/emulated/0/Download` 和 `/storage/emulated/0/ADM`。初始化完成后，读取进程的 inotify fdinfo，得到约 4263 个内核目录监听。这不是“所有媒体文件都重新解码”的证据，热点集中在目录订阅与集合维护。

- [`MediaLibraryObservation.observe()`](../../app/src/main/java/com/subtitleedit/library/MediaLibraryFileObserver.kt#L302) 对每个新目录执行 `watches.values.any`，线性检查 canonicalPath，然后立即调用 `publishCoverage()`。
- [`publishCoverage()`](../../app/src/main/java/com/subtitleedit/library/MediaLibraryFileObserver.kt#L349) 每次重新遍历全部已监听目录、构建集合、计算移除项并发布。随订阅目录逐个增加，这部分总工作量呈平方增长。
- [`onCoverageChanged`](../../app/src/main/java/com/subtitleedit/library/MediaLibraryFileObserver.kt#L36) 又为整个集合计算父目录集合并保留更新版本，进一步分配字符串、File 和集合。
- [`MainActivity.observeSharedLibraryCache()`](../../app/src/main/java/com/subtitleedit/MainActivity.kt#L1046) 在主线程收集 coverage。当前目录即使没有发生相关覆盖变化，也可能反复调用 `watchDirectory()`，过滤当前目录行并同步子监听。

18 秒 simpleperf 采样窗口共得到 3879 个样本，未丢失样本。按 cpu-clock 采样权重估计：

| 调用链 | 对应线程样本中的占比 | 加权 CPU 时间估计 |
| --- | ---: | ---: |
| 后台 `MediaLibraryRepository.reconcileDirectories` | 后台 DefaultDispatcher 样本的 92.88% | 12.65 s |
| 后台 `MediaLibraryObservation.publishCoverage`，包含其调用的函数 | 后台 DefaultDispatcher 样本的 86.12% | 11.73 s |
| 主线程 `observeSharedLibraryCache` 的 coverage collector | 主线程样本的 14.52% | 585 ms |
| 主线程 `watchDirectory`，嵌套在上述 collector 内 | 主线程样本的 12.53% | 505 ms |

表中为包含子调用的占比，不能相加。采样时间用于定位热点，不等同于精确计时器。第一次采样导出的调用栈与统计保存在 `busy-*.json` 中。

Perfetto 给出了独立佐证：第二轮快速切页后的第 12–18 秒，已经停止注入点击，一个后台 worker 仍消耗约 4.94 秒 CPU，主线程另消耗约 454 ms CPU。这说明负担会延续到用户停止操作以后。扫描结束后再采样，后台 DefaultDispatcher 仅占进程样本的 5.83%，原 coverage collector 已不再是主线程热点。

**扫描结束以后，页面组合与测量仍然超出帧预算**

[`MainTopLevelPageTransition`](../../app/src/main/java/com/subtitleedit/feature/ui/MainTopLevelPageTransition.kt#L25) 使用整个页面的 Crossfade 与位移过渡；旧页面在动画完成前仍留在组合中。250 毫秒动画尚未完成时连续选择其他页面，会增加同时存活的页面与相关布局工作。保存页面数据和滚动位置本身不能避免页面重新进入组合时的构建、测量成本。

第二轮切页中，`AndroidOwner:measureAndLayout` 单次最长 104.90 ms；扫描结束后的对照中仍有 97.11 ms。后者最长 `doFrame` 为 144.11 ms。扫描期间的一个 164.50 ms 慢帧里，主线程实际运行了 158.27 ms，等待 CPU 仅约 1.02 ms；这更符合主线程工作量过大，而非长时间等磁盘或被其他线程抢占。

扫描结束后的 961 个 simpleperf 样本中，主线程占进程 CPU 样本的 70.97%。调用栈涉及 Recomposer、测量布局、主页过渡、底部导航，以及设置页的 `SettingsPageGroup`、`SettingsPageRow`。设置页通过 [`AppToolScaffold`](../../app/src/main/java/com/subtitleedit/ui/components/AppComponents.kt#L286) 使用可滚动 Column，一次构建全部设置项，值得作为页面构建成本的优化对象。但当前没有逐个 composable 的完整运行时 trace，不据此声称某一行设置就是所有慢帧的唯一来源。

**冷启动还有独立的 DEX 与首次 Compose 成本**

8 秒冷启动 Perfetto 轨迹中：

- 系统识别的 cold startup 为 948.06 ms，`am start -W` 为 948 ms。
- `bindApplication` 为 351.70 ms，其中 `OpenDexFilesFromOat` 为 271.05 ms，包含 APK 内 DEX 的读取、提取和验证。
- 首次主线程 `doFrame` 为 312.42 ms，后续一个为 199.20 ms。
- 首次布局中的 `AndroidOwner:onMeasure` 为 155.95 ms，后续 `measureAndLayout` 为 153.01 ms；一次 `Compose:recompose` 为 104.90 ms。
- JIT 编译线程在窗口内使用约 1.16 秒 CPU，主线程约 1.12 秒 CPU。

本包包含 17 个 DEX，未压缩共约 50.11 MiB。系统报告该包 ART 状态为 `run-from-apk`，且带 `DEBUGGABLE` 标志。因此调试版的 DEX 验证、解释执行和 JIT 是本次启动耗时的重要背景。尚未测正式版或 speed-profile 版本，不能把这些数据直接当作发布版成绩，也不能认为换正式版就会消除上面的监听算法与主线程布局问题。

**GPU、GC 与其他观察**

快速切页时，GPU P95 通常约 6–10 ms，主线程慢帧则达到 100–382 ms。系统主要记录 `Slow UI thread`；扫描结束后 GPU P95 仍为 6 ms。因此优先优化 GPU 特效缺少依据。

扫描期间有反复的并发 mark-compact GC，但其 70–100 ms 总持续时间不能当成 UI 停顿时间。第二轮轨迹中主线程 `FullSuspendCheck` 最长 3.61 ms，累计约 25.58 ms；大部分慢帧时间是主线程在运行。GC 与分配压力是伴随因素，目前证据不支持“单次 GC 暂停导致了全部数百毫秒卡顿”。扫描结束后的切页轨迹没有捕获到超过 1 ms 的 GC slice，仍存在慢帧。

应用内日志读取与写入线程只占少量 CPU 样本，未成为热点。测试前后系统 Thermal Status 都为 0，未报告系统热限频状态；这不等同于证明所有运行条件完全一致。

**建议按以下顺序处理，并用同一操作序列复测**

1. 批量或增量发布监听 coverage，避免在每个新目录上重建、广播整个集合；用 canonicalPath 索引代替逐项 `any`。保留扫描取消、目录移除、监听先于读取注册等现有正确性约束。
2. 主线程只响应当前目录和直接子目录相关的 coverage 变化。无关目录新增不应重新计算当前目录的监听配置。
3. 在保留原导航、过渡与状态恢复行为的前提下，减少切页时重新组合、测量的页面内容，检查快速切页中存活页面的范围；评估设置页惰性布局与更稳定的页面参数。
4. 使用正式构建进行启动和切页基准，再评估 Baseline Profile / 启动代码延迟初始化。先解决已实测的重复工作，再评价编译策略带来的收益。

本次结论是运行时定位结果，未进行禁用某一模块的独立 A/B 实验，也未实施性能修复。

**原始证据与复现**

原始文件目录：[`tmp/startup-page-perf-20261117`](../../tmp/startup-page-perf-20261117/)。该目录是本地诊断产物，不作为大体积文件加入 Git。

- [`environment.json`](../../tmp/startup-page-perf-20261117/environment.json)：设备、APK 哈希、编译状态与源码版本。
- [`cold-baseline.json`](../../tmp/startup-page-perf-20261117/cold-baseline.json) 和 `cold-baseline-*-gfx.txt`：未跟踪的三次冷启动。
- [`cold-trace.perfetto-trace`](../../tmp/startup-page-perf-20261117/cold-trace.perfetto-trace) 与 [`cold-sql.json`](../../tmp/startup-page-perf-20261117/cold-sql.json)：启动系统轨迹与查询结果。
- `rapid-traced-first.perfetto-trace`、`rapid-traced-repeat.perfetto-trace`、`rapid-settled.perfetto-trace` 及各自的 gfx / SQL JSON：扫描中与扫描后的切页对照。
- [`busy-background-summary.json`](../../tmp/startup-page-perf-20261117/busy-background-summary.json)、[`busy-sampled-summary.json`](../../tmp/startup-page-perf-20261117/busy-sampled-summary.json)、`busy-main-samples.json`：第一次繁忙窗口采样导出的热点与主线程调用栈。
- [`rapid-settled-sampled.data`](../../tmp/startup-page-perf-20261117/rapid-settled-sampled.data)、[`settled-sampled-summary.json`](../../tmp/startup-page-perf-20261117/settled-sampled-summary.json)：扫描结束后的原始 simpleperf 记录与统计。
- [`process-logcat.txt`](../../tmp/startup-page-perf-20261117/process-logcat.txt)、`inotify-137.txt`、`pair-*-gfx.txt`、[`frame-summary.json`](../../tmp/startup-page-perf-20261117/frame-summary.json)：进程日志、目录监听数量和分页面对照。

`gfxinfo framestats` 的原始明细最多保留最近约 120 帧，所以原始明细算出的 P95 不代表整轮全部帧；表中采用系统累计的卡顿数和直方图 P95。该设备的明细输出还把 FrameInterval 和 FrameStartTime 的值放在相反的列中，帧间隔单独检查时作了对应修正，帧完成时间和卡顿统计不受此问题影响。Perfetto 的错误统计仅见少量负时间戳排序丢弃（5–26 项），未见 ftrace buffer overrun 或 simpleperf 样本丢失；各轨迹的检查结果保存在 `trace-quality.json`。

采集脚本为 [`capture.py`](../../tmp/startup-page-perf-20261117/capture.py)，配置为 `trace-config.pbtxt`。坐标来自本机 `initial-ui.xml`，仅适用于本次 1080×2376 分辨率和五个底部导航项。更换设备或布局后应先更新坐标。复测会重启应用进程并操作页面，不清除应用数据：

```powershell
python tmp/startup-page-perf-20261117/capture.py baseline
python tmp/startup-page-perf-20261117/capture.py coldtrace
python tmp/startup-page-perf-20261117/capture.py rapid-traced-first
python tmp/startup-page-perf-20261117/capture.py rapid-traced-repeat
# 等待初始化扫描完成、线程 CPU 回落以后：
python tmp/startup-page-perf-20261117/capture.py rapid-settled
python tmp/startup-page-perf-20261117/capture.py isolated
```
