# 统计页首载与 4,096 文件媒体库验证

## 改动

- 统计页默认仍显示「最近播放」。摘要、日历、当日时间线和最近播放不再等待「播放最多」「从未播放」的 SQL 查询。这两个标签首次选中时才查；未有结果时显示加载状态，不显示误导性的空态。
- 两个标签各自保留结果，仓库版本和页面刷新版本不变时复用；切换日期、年份和统计粒度不会使其失效。仓库变化后，只更新当前打开的标签，其余标签选中后再更新，已加载的旧结果不会在查询期间清空。
- 首载摘要的四张卡片直接显示占位文字，不创建未在运行的 `AnimatedContent` 布局。实际统计到达后的文字过渡与原先相同，未修改热力图与列表布局。

## 首载对照

Android 15 / x86_64 模拟器，Standard Debug。对比改动前的 `663603b` 与本轮改动，各运行 5 次 `ListeningStatsTransitionTest#firstLoadKeepsTheFullCalendarGeometryWhileRealValuesArrive`。该用例通过挂起加载器固定占位阶段，验证数据显示前后日历高度与位置，**不测 SQLite 或真实主页切页**。

从每条 Perfetto 轨迹取该标记的**首次**切片。墙钟含等待，线程运行时间取该切片与该线程 `sched` 运行区间的交集。以下单位为毫秒，统计量为中位数；列表与子项切片嵌套，不能相加：

| 切片 | 改动前墙钟 | 改动后墙钟 | 改动前线程运行 | 改动后线程运行 |
| --- | ---: | ---: | ---: | ---: |
| `Stats:summary:compose` | 75.85 | 36.71 | 72.78 | 35.82 |
| `Stats:list:measure` | 336.29 | 280.98 | 331.08 | 278.41 |
| `Stats:summary:measure` | 53.16 | 56.46 | 52.01 | 56.07 |
| `Stats:heatmap:measure` | 114.14 | 115.22 | 114.13 | 115.12 |

本次观察到的主要收益是少做摘要的首次组合工作；摘要测量和热力图没有观测到缩短。模拟器 Debug 的小样本不代表 Release、真机或整页端到端耗时。

## 大库压力测试

`MediaLibraryLargeIndexTest#playbackWritesReuseLargeCatalogAndLoadRankingsOnlyWhenSelected` 在应用专用缓存的四个子目录中生成 4,096 个 100 ms PCM WAV。执行实际文件遍历、属性读取、SQLite 索引、重扫、仓库写入和 Compose 页面更新；注入轻量标签读取器计数，**不包含 TagLib/FFmpeg 元数据解析开销**。测试结束后删除独立数据库和目录。

| 场景 | 结果 |
| --- | --- |
| 首次扫描 | 4,096 个条目、4,096 次标签读取，963 ms |
| 无变更重扫 | 4,096 个条目、0 次额外标签读取，不变更目录版本，219 ms |
| 新增、删除各 1 个后重扫 | 1 次额外标签读取，目录变更使媒体库查询一次，180 ms |
| 播放进度 24 次 + 收听记录 6 次写入 | 验证 6 次可观察的历史刷新；额外全量目录查询 0 次，曲目与浏览索引快照不变 |
| 统计页进入、切换排名、后台写入后重新打开 | 默认统计未查询隐藏排名；轨迹共记录 `most` 1 次和 `never` 2 次（首次选中和版本变化后再次选中），同版本重选无查询；已播放曲目从未播放列表移除 |

表中的扫描耗时是一次成功运行的观测值，不是本轮扫描代码的提速对照。文件数是本轮索引压力的重点，未测试数 GB 单文件的读取或解码负载。

## 回归与复现

1. 执行 `gradlew.bat :app:testStandardDebugUnitTest :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest --console=plain`。
2. 使用 `adb install -r` 覆盖安装 Standard Debug x86_64 主包和测试包。用 `adb shell am instrument -w -r -e class com.subtitleedit.ui.MediaLibraryLargeIndexTest com.subtitleedit.test/androidx.test.runner.AndroidJUnitRunner` 运行大库用例。
3. 对主包启用 atrace 并采集 `sched_switch` / `sched_waking`，可查阅 `Stats:insights-most:query` 与 `Stats:insights-never:query` 切片；扫描日志标签为 `LargeMediaIndexTest`。

本轮 JVM 通过 1,971 项、跳过 22 项；模拟器大库用例及 38 项定向回归通过。日志、首载对照轨迹和大库轨迹保存在本地 `tmp/stats-insights-20261118/`，未加入版本控制。
