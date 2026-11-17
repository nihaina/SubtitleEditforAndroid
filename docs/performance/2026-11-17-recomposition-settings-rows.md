# 2026-11-17 主页重组隔离与设置页逐行加载

本轮只实现主页重组范围细化与设置页逐行惰性布局，使用 Standard Debug 包构建和真机测试。未修改 R8、编译策略或页面过渡动画。

**实现与验证依据**

- 将工具栏提取到 `MainPageToolbar.kt`，只传入标题、选择数量、搜索和菜单选项等小状态。目录行和文件元数据不再作为工具栏依赖。
- 将底部导航和选择操作栏提取到 `MainBottomBar.kt`，使用独立的小状态。目录数据更新无需重新执行底栏内容。
- `DirectoryBrowserState.kt` 只保留目录视口所需数据。目录文件与元数据沿用原始集合，以引用比较识别替换，不复制、哈希或逐项比较大集合。`DirectoryBrowserPage` 可以跳过仅由工具栏变化引起的重组。
- 目录滚动位置的初始查找只在进入该目录/显示模式时计算；同一页后续更新继续通过既有滚动请求和定位流程处理。
- 设置页外观、通用、目录和编辑分组中的每一行成为独立 lazy item，保留稳定键和内容类型；八行的“通用设置”不再一次构建。保留组外圆角、连续边框、组内间距、按钮顺序和回调。

重组行为通过测试专用 `CompositionTracer` 观察真实的 Compose 编译器事件，而非只比较状态对象引用：

| 操作 | 必须执行 | 必须跳过 |
| --- | --- | --- |
| 仅更新工具栏标题 | `MainPageToolbar` | `DirectoryBrowserPage`、`BrowserFileLayout`、`MainBottomBar` |
| 替换目录列表和元数据 | `DirectoryBrowserPage` | `MainPageToolbar`、`MainBottomBar` |

两项断言均通过。测试未把 UI 数据强行标记为 `Stable` / `Immutable`。跟踪器仅存在于 androidTest 中，测试后释放。

设置页测试确认屏幕外的“软件目录”行尚未进入组合，滚动后仍可到达底部备份/恢复操作；两种外观均保留滚动恢复。另有像素对照测试，将逐行分组与原有整组 Column 的圆角轮廓、背景和边框比较，允许的差异阈值为总像素的 0.3%，两种外观均通过。设备上的设置页画面亦已查看。

**真机性能对照**

设备仍为 PKG110 / Android 15，使用原有真实目录与媒体库。先测修改前 APK，再测修改后 APK，均不清除应用数据。基线 APK 已检查含上一轮 canonicalPath 监听索引，即本轮是在上一轮优化基础上的增量对照。

每轮按“媒体库 → 分类 → 数据统计 → 设置 → 目录”循环五次，注入 25 次点击，每次命令后等待 80 ms；连续三轮，轮间等待两秒。以下为不开性能跟踪时 gfxinfo 的累计统计与直方图 P95。

| 轮次 | 修改前卡顿比例 | 修改后卡顿比例 | 修改前 P95 | 修改后 P95 |
| --- | ---: | ---: | ---: | ---: |
| 刚启动的第一轮 | 40/85（47.06%） | 43/96（44.79%） | 550 ms | 550 ms |
| 第二轮 | 38/202（18.81%） | 33/181（18.23%） | 97 ms | 101 ms |
| 第三轮 | 32/203（15.76%） | 32/227（14.10%） | 77 ms | 81 ms |

卡顿比例略有下降，但 P95 没有稳定下降，不能宣称本轮已显著改善全部快速切页。两版本为单组顺序测试，包含 JIT、缓存和系统负载差异；这些结果不足以建立统计显著性。

另测每次点击后等待 350 ms、各六次往返：

| 路径 | 修改前卡顿比例 | 修改后卡顿比例 | 修改前 P95 | 修改后 P95 |
| --- | ---: | ---: | ---: | ---: |
| 目录 ↔ 媒体库 | 4.45% | 5.07% | 48 ms | 48 ms |
| 目录 ↔ 分类 | 5.85% | 6.31% | 77 ms | 89 ms |
| 目录 ↔ 数据统计 | 6.10% | 5.97% | 77 ms | 89 ms |
| 目录 ↔ 设置 | 5.35% | 4.57% | 57 ms | 69 ms |

分页面成绩也有波动。本轮有明确证据的收益是无关重组被跳过、屏幕外设置行不再整组构建；整体流畅度仍受首次构建和页面测量影响。

修改后预热切页的 Perfetto 记录中，最长主线程 `doFrame` 146.73 ms，最长 `measureAndLayout` 95.72 ms，单次 `Compose:recompose` 最长 18.02 ms。这份记录没有同条件的新基线轨迹，不能单独用于声称耗时下降。仍能确认主线程布局长帧尚未消除。

**检查与产物**

- `:app:assembleStandardDebug` 和 `:app:assembleStandardDebugAndroidTest` 成功；最终构建日志为 `tmp/recompose-row-delivery-build.log`。
- 30 项针对本轮范围的设备测试通过，最终构建重新安装后的日志为 [`recompose-row-delivery-tests.log`](../../tmp/recompose-row-delivery-tests.log)：真实重组跟踪、两种设置页外观与状态恢复、分组像素对照、主页过渡、模式切换、目录列表/网格首帧恢复、多级目录与五个分类返回。
- `git diff --check` 通过。

额外运行的播放定位集成测试 `MainMediaLocationTest` 在收尾恢复真实媒体库时触发重新索引，卡在系统媒体元数据读取；最终终止这项额外运行，不计为通过。已从测试保存的状态恢复全部七个播放器偏好，核对原媒体目录配置，并删除该测试自己生成的缓存目录。恢复记录在 `tmp/recompose-row-perf-20261117/cleanup/recovery.json`。目录定位脉冲本身的独立 Compose 回归已通过，但完整“播放中定位”集成流程此次没有取得整项通过结果。

性能原始数据在 [`tmp/recompose-row-perf-20261117`](../../tmp/recompose-row-perf-20261117/)：

- [`apk-comparison.json`](../../tmp/recompose-row-perf-20261117/apk-comparison.json)：APK 哈希及构建对应关系。性能采集使用 `after.sha256` 所指构建；随后仅清理无用 import，最终交付哈希单列为 `delivery_sha256`。
- [`frame-comparison.json`](../../tmp/recompose-row-perf-20261117/frame-comparison.json)：本表各轮结构化结果，原始 `gfxinfo` 文件位于 `before/` 和 `after/`。
- `after/rapid-settled.perfetto-trace` 与 [`after/settled-sql.json`](../../tmp/recompose-row-perf-20261117/after/settled-sql.json)：预热切页轨迹及查询结果。
- `before/capture.py` 与 `after/capture.py`：同一坐标、顺序和等待间隔的采集脚本。
- `settings-row-material.png`：逐行布局后的设备设置页画面。

这些数据均来自 debug 包；没有据此推断 release 成绩或启用 R8。
