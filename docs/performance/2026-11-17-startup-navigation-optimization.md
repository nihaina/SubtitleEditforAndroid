# 2026-11-17 启动与切页优化结果

本轮降低了媒体库启动后的后台负担，并缓解了部分切页长帧。快速连续切页仍会掉帧，冷启动首屏没有确认得到明显改善。

保留的实现改动：

- `MediaLibraryFileObserver.kt`：使用 canonicalPath 索引去重目录监听；原生监听仍在读取目录之前注册，覆盖集合改为在请求边界批量发布。删除目录时立即交回监听覆盖，取消或失败也会发布实际已注册的集合。未变化的覆盖集合不重复通知。
- `MainActivity.kt`：主线程只响应当前目录或其直接子目录相关的覆盖变化，忽略其他目录的新增。
- `SettingsScreen.kt`：设置页按现有分组使用 LazyColumn，减少屏幕外分组的构建工作。保留组内行顺序、外观、回调与页面状态恢复。

页面过渡实现最终与本轮开始时一致。本轮曾尝试限制快速切页中保留的页面数量，但实测增加了页面重新构建、切页成绩反而变差，因此已撤回。

**不开性能跟踪的前后对照**

在同一 PKG110 / Android 15 设备上先覆盖安装原始 APK，再覆盖安装优化 APK，均保留应用数据。每个版本启动后按“媒体库、分类、数据统计、设置、目录”循环五次，注入 25 次点击；每次点击后 shell 等待 80 ms，整段约 3.7–4.8 秒。连续测三轮，轮间等两秒。卡顿比例与 P95 采用 gfxinfo 累计统计及直方图。

| 轮次 | 原始版卡顿帧 / 总帧 | 优化版卡顿帧 / 总帧 | 原始版 P95 | 优化版 P95 |
| --- | ---: | ---: | ---: | ---: |
| 第一轮，刚启动 | 42 / 78（53.85%） | 47 / 100（47.00%） | 550 ms | 450 ms |
| 第二轮 | 32 / 190（16.84%） | 36 / 197（18.27%） | 113 ms | 85 ms |
| 第三轮 | 38 / 218（17.43%） | 34 / 231（14.72%） | 101 ms | 77 ms |

第一轮卡顿比例改善了约 6.9 个百分点；第二轮卡顿比例略有上升，但长帧 P95 缩短；第三轮两项均改善。整体收益主要体现在长帧缩短，不能据此宣称所有切页均已流畅。该对照为同一操作序列的单组顺序测试，并非随机交叉的多设备基准，JIT 和系统调度仍会影响数值。

原始数据：`tmp/startup-page-perf-ab-baseline-20261117/untraced-*-gfx.txt` 与 `tmp/startup-page-perf-retained-20261117/untraced-*-gfx.txt`。结构化结果为 [`comparison.json`](../../tmp/startup-page-perf-retained-20261117/comparison.json)。两个版本安装后均为 1.2.6 调试版，最终安装 APK 的哈希核对见 [`installed-apk.json`](../../tmp/startup-page-perf-retained-20261117/installed-apk.json)。

**进程与系统轨迹对照**

沿用原分析的 Perfetto 配置和相同切页序列，在第二轮 18 秒采集窗口比较全部进程线程：

| 项目 | 原始版 | 优化版 |
| --- | ---: | ---: |
| 全部 DefaultDispatcher 线程 CPU 时间合计 | 16.18 s | 3.09 s |
| 主线程 CPU 时间 | 4.86 s | 3.62 s |
| 并发 mark-compact GC 次数 | 16 | 1 |
| 最长主线程 doFrame | 164.50 ms | 126.77 ms |
| 最长 Compose measureAndLayout | 104.90 ms | 89.23 ms |

后台 CPU 合计下降约 81%；并发 GC 总持续时间不能等同于 UI 暂停时间。轨迹对照包含目录扫描进度、预热和缓存变化，不能把全部收益归因到单个改动。数据来源为 [`process-comparison.json`](../../tmp/startup-page-perf-retained-20261117/process-comparison.json)，对应原始轨迹为优化前 `tmp/startup-page-perf-20261117/rapid-traced-repeat.perfetto-trace` 和优化后 `tmp/startup-page-perf-retained-20261117/rapid-traced-repeat.perfetto-trace`。

中间版本的 simpleperf 进一步确认：原来占后台 CPU 样本 86% 的 `publishCoverage` 已不再是主要热点，热点转为实际目录读取与文件属性读取。保留同一批量发布实现的最终版也具有上述 CPU 和 GC 改善。

冷启动方面，最终未跟踪测试的首屏时间为 851 ms，Perfetto 采集时为 987 ms。与原始三次 835–971 ms 的范围相比，尚不能确认首屏提升；首次 Compose 长帧和调试版 DEX/JIT 成本仍然存在。此前撤回的过渡实验所得 882/855/855 ms 不作为最终构建的成绩。

**验证**

- `:app:assembleStandardDebug` 与 `:app:assembleStandardDebugAndroidTest` 成功。
- 最终测试 APK 已重新安装，37 项真机测试通过，日志为 [`navigation-perf-verified-device-tests.log`](../../tmp/navigation-perf-verified-device-tests.log)。覆盖递归文件事件、根目录变化、扫描取消、索引状态、主页过渡、设置页布局与状态恢复、媒体库返回位置、主目录返回位置。
- 新增 257 个目录的覆盖发布次数测试：整轮初始注册只发布一次完整集合；嵌套新增文件仍被正确索引；删除后旧快照保持不变。
- 新增删除并重建目录的回归：canonicalPath 索引与原生监听能恢复，后续文件写入仍会更新索引。
- 设置页两种外观的底部备份/恢复回调、滚动位置恢复测试通过；另检查了设备上的设置页画面。
- `git diff --check` 通过。应用数据保留，设备最终安装的是本轮保留版本。

剩余工作应针对首次 Compose 构建与各页面测量继续定位，并用正式构建评估 DEX/JIT 的影响。本轮未通过缩短动画或改变导航行为来掩盖长帧。
