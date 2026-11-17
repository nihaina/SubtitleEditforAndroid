# 2026-11-18 主页横向分页与页面保留

## 行为与实现

主页使用 Foundation `HorizontalPager`，编辑模式与播放器模式都按底部导航顺序支持左右滑动。滑动停稳后只派发一次目标导航；点击底部标签则把分页器移到目标页，快速点击时取消前一个移动请求，以最后一次选择为准。

移除了原来的 `Crossfade` 和 28 dp 叠加位移。工具栏与正文共同移动，底部导航和迷你播放器保持固定。点击切页复用 `AppMotion.enter()` 的强调减速曲线和 350 ms 时长；拖动、甩动的位移与吸附由分页器处理。这是项目采用的 MD3 空间移动方案，350 ms 是项目的运动参数，不代表所有 MD3 页面都规定相同时长。系统动画缩放仍适用。

最多保留当前模式的五个页面宿主，真实内容在首次选择或进入视口时创建，之后保留组合、列表状态及图片请求，避免每次返回都销毁和重建整页。未访问的隐藏页保持为空，不在启动时同步创建五个完整页面。切换编辑/播放器模式会重建对应的分页器，防止共用页面 ID 导致内容串页。

隐藏页的子生命周期停在 `CREATED`，避免隐藏的返回键回调继续处理输入。统计、分类及媒体库的仓库订阅和自动查询仅在当前页面停稳后运行。仓库版本和扫描状态通过 `collectRetainedPageState` 保留最后显示值，重新进入时立即读取最新版本；这三类页面使用已有查询快照，数据版本未变时复用结果，版本变化时才刷新相关数据。分类的定位元数据查询也在离开后取消。

顶层 `SaveableStateProvider` 使用“模式 + 页面 ID”的固定键，避免设置页等页面因每次选择的版本号改变而重新创建。目录的列表/网格状态、媒体库目录和分类内部状态继续使用原有状态对象与恢复流程。

## 边界

- 保留 UI 会增加已访问页面的常驻内存，上限为本模式五页；Activity 销毁时释放。没有额外保存完整的磁盘 UI 缓存，也未增加启动时的媒体扫描。
- 首次进入页面仍需要创建可见 UI；数据变化、配置变化和必要的内部导航仍会触发重组或布局。这次改动不能据此宣称首次构建成本已经消失。
- 内部横向滚动区域通过 Compose 的嵌套滚动处理自己的手势；新增了对应回归用例，实际设备上的边缘交接仍需验证。
- 原有数据读写、目录操作和草稿重新进入的业务规则保持原样。

## 本轮验证

已执行：

```powershell
.\gradlew.bat :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest :app:testStandardDebugUnitTest --tests com.subtitleedit.util.MainNavigationPolicyTest --tests com.subtitleedit.util.MainBackNavigationPolicyTest --console=plain
```

最后一次源代码更新后再次执行主包与 Android 测试包构建，成功。导航策略 3 项、返回策略 5 项单元测试通过，`git diff --check` 通过。

离线阶段未检测到原手机 `3B65BU026VP00000`；用户重新连接后，实际设备为 **OPD2404 平板、Android 16 / API 36、2120 × 3000、序列号 `7d9ca392`**。此设备已安装的主包哈希与候选包一致。使用 `adb install -r` 覆盖安装主包和测试包，未卸载或清除用户数据，也没有运行会触发真实媒体库清理重建的 `MainMediaLocationTest`。

设备最终执行的 **47 项界面回归测试全部通过**：

- `MainTopLevelPagerTest` 9 项：横向移动及不透明页面、双向滑动、快速反向/跨页请求、隐藏生命周期与返回键、数据更新不触发导航或目录哈希、禁用手势、垂直位置保留、隐藏订阅暂停及恢复、内部横向滚动。
- `MainPageRecompositionTest`、`MainModeNavigationTest`、`MainBottomNavigationAnimationTest`。
- `MainDirectoryReturnStateTest`、`MediaBrowseNavigationStateTest`，包括真实目录返回、列表/网格锚点、分类详情与状态恢复。
- `ListeningStatsTransitionTest`、`ListeningStatsLazyListTest`、`ListeningStatsArtworkTest`、`MediaAlbumTransitionTest`、`MediaCategoryScrollbarTest`。
- `SettingsPageLayoutTest`、`SettingsLazyGroupShapeTest`。

首轮暴露了测试本身的三类问题，并在修正后重新运行完整上述集合：分页订阅用例原先仅在 `SideEffect` 内读取 State，无法触发组合观察，改为在组合阶段读取；专辑异步等待不应要求内容相同的空历史 Map 必须引用相同，保留原有目录加载次数及缓存对象复用断言；设置像素对比固定为整数密度，避免设备 2.625 倍密度下“逐行取整”和“总高度取整”的 1 px 差异。后两项没有改动生产布局或查询逻辑。

新增 `MainNavigationMotionLatencyTest`，在两种模式下分别以真实 MotionEvent 和系统帧时钟运行；每种模式连续点击五页、共三轮，随后在真实 `MainActivity` 中左右滑动，验证页面选择及完整目标帧。编辑模式与播放器模式的未跟踪测量各通过 1 项，另一次带 Perfetto 的播放器测量通过。测试期间临时切换模式，结束后同步恢复为本设备原来的编辑模式；USB 保持唤醒也已读回并恢复为原值 `0`。

原手机此前诊断的 USB 保持唤醒恢复命令仍等待原序列号重新连接；对应 PID 及输出位于 `tmp/main-pager-20261118/restore-usb-awake.*`。它与本次平板测试独立，不会安装 APK 或修改应用数据。

构建日志：`tmp/main-pager-build3.log`、`tmp/main-pager-final-build.log` 及 `tmp/main-pager-20261118/device-7d9ca392/final-test-build2.log`。最终回归日志：`tmp/main-pager-20261118/device-7d9ca392/final-regression-tests.txt`。

## 本机切页、启动与进程轨迹

测量从应用接收抬手事件开始。在 OnDraw 中读取目标页与分页视口的实际交集，记录目标页覆盖视口宽度至少 1% 的帧提交，以及首次覆盖至少 99.5% 的帧提交。没有使用 Compose 测试时钟。`FrameCommitCallback` 表示帧已提交，不等于屏幕物理呈现；几何采样与 FrameMetrics 监听也有测试开销。完整覆盖时间包含预期的页面移动，不能当作点击后全程无反馈的等待。

播放器模式未跟踪结果如下，重复进入值取第二、三轮中位数，单位 ms：

| 页面 | 首次可见提交 | 重复进入首次可见提交 | 重复进入完整覆盖提交 |
| --- | ---: | ---: | ---: |
| 媒体库 | 119.22 | 65.62 | 347.89 |
| 分类 | 113.51 | 76.25 | 353.60 |
| 数据统计 | 350.10 | 67.07 | 347.72 |
| 设置 | 170.81 | 58.40 | 345.57 |
| 目录 | 100.15 | 78.78 | 353.04 |

编辑模式重复进入各页的首次可见提交中位数约 57–73 ms。第二、三轮导航及随后 500 ms 刷新窗口内，FrameMetrics 总帧耗时 P95 为编辑模式 17.34 ms、播放器模式 18.73 ms；最大值分别为 40.29 ms、49.26 ms。这些数值并不证明零掉帧，也不是旧版本与新版的同机 A/B。

Perfetto 单独一轮确认：首次进入统计，点击派发约 **6.10 ms**，首次可见提交约 **356.10 ms**；其中 `AndroidOwner:measureAndLayout` 约 **205.80 ms**，CPU 实际运行约 **205.38 ms**，相关 `Choreographer#doFrame` 约 **293.20 ms**。等待主要落在首次 UI 布局的主线程执行中；这段轨迹不足以把全部开销归到某一个控件。重复进入统计的首次可见提交约 64–67 ms，与页面组合保留后的行为一致，首次构建成本仍明显存在。

Android 16 的此轨迹缺少目标进程名称元数据，分析脚本用本测试的 `PagerTouch` 标记定位实际主线程，避免依赖缺失的进程名。轨迹未报告解析或丢失错误。首次 Perfetto 尝试因配置文件路径读取权限失败，未作为跟踪数据使用；最终从标准输入加载配置成功。

三次强制结束应用进程再启动，`am start -W` 均返回 `COLD / ok`，`TotalTime` 分别为 **812、797、830 ms**。该指标是 Activity 显示时间，不代表全部目录异步加载已经完成。最后一次启动后的目录画面已检查，应用停留在编辑模式目录页。

测试设备的**媒体索引为 0 条**。目录使用设备真实文件夹，专辑/统计的功能回归另有受控样本，但本轮耗时不覆盖大媒体库及大量封面的运行条件。此前 PKG110 手机的耗时来自不同硬件，不能直接相减计算提升比例。

诊断进程内，五页访问前后 PSS：编辑模式 182206 → 206868 KiB，播放器模式 182119 → 216524 KiB。差值包含 UI 保留、debug/JIT、几何采样及测量数据分配，不可全部算作页面缓存成本；空索引条件也不能用于推断大媒体库峰值内存。独立冷启动目录页的进程 PSS 为 114266 KiB，与附加 instrumentation 的进程不直接比较。

原始数据与脚本：`tmp/main-pager-20261118/device-7d9ca392/final-motion-*.json`、`final-motion-summary.json`、`pager.perfetto-trace`、`trace-analysis.json`、`cold-runs.json`、`final-launch.png`；汇总脚本为 `tmp/main-pager-20261118/analyze-device.py`。

候选 ARM64 debug APK：`app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk`，SHA-256：`00d3458e5b96a53746f578f1be1964adb316eb08bbad6ac40ad015a42503f853`。

最终测试 APK：`app/build/outputs/apk/androidTest/standard/debug/app-standard-debug-androidTest.apk`，SHA-256：`090f3d2761b85ec86ba046c693d087106ebf609980ac344997ba9f44b23ffe4f`。
