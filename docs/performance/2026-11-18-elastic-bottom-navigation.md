# 2026-11-18 底栏胶囊跟随分页的粘连移动

按参考图将底栏选中背景改为一个连续胶囊：手动拖动页面时前沿先伸向相邻项，后沿延后跟进；中途反向时直接沿原形状返回，松手后随分页吸附收拢。图标大小、图标与文字颜色同样随分页进度变化。保留原有底栏尺寸、静止图标大小、主题配色、文字排版及点击/无障碍选择逻辑。

`MainActivityScreen` 持有分页状态并同时传给页面容器和底栏。底栏用 `currentPage + currentPageOffsetFraction` 作为连续位置，不额外给手指位置叠加计时动画。一个 `drawBehind` 绘制胶囊，图标通过 `graphicsLayer` 更新缩放，图标/文字在绘制时计算颜色；滑动进度不在 `MainActivityScreen` 或导航项的组合阶段读取。

胶囊两端分别按相邻索引间进度的平方和反向平方推进，中心随手指连续移动，中间阶段拉长，到整数页面索引时恢复 56 × 28 dp。从右向左的布局会镜像指示器。页面缓存和数据加载沿用上一轮实现。

## 验证

- `:app:assembleStandardDebug`、`:app:assembleStandardDebugAndroidTest` 构建成功；主包与测试包均通过 `adb install -r` 安装到 PKG110 / Android 15（`3B65BU026VP00000`），保留应用数据。
- 最终导航、分页与目录返回回归集合 **22 项通过**：`MainBottomNavigationSwipeIndicatorTest` 4 项，`MainBottomNavigationAnimationTest` 2 项，`MainTopLevelPagerTest` 9 项，`MainModeNavigationTest` 3 项，`MainPageRecompositionTest` 2 项，`MainDirectoryReturnStateTest` 2 项。
- 新增回归验证 0 / 25 / 50 / 75 / 100% 的实际截图、前后沿拉伸、RTL 镜像、编辑/播放器模式真实触摸拖动、手指停留时不继续自行追赶、反向拖动、松手吸附，以及点击动画被拖动中断后的选中项同步。
- 组合跟踪确认：连续分页进度更新不重新执行底栏行或导航项的组合体。静止时与旧底栏的像素对比通过。早期实现曾扩大未选中图标，像素测试发现后已恢复原有 0.6–1 缩放范围。
- 实际页面拖动截图中的胶囊与页面位移同步，保留应用原主题；受控截图中使用绿色只是为了从像素中准确识别胶囊边界。

`MainNavigationMotionLatencyTest` 同时加强前台焦点检查。连续指示器在页面停稳后不需要再为选中项动画绘制一帧，原测试却要求先发布选中状态、再观察完整目标帧，因而可能漏掉已经提交的最终视觉帧。诊断记录证实此时选中项正确且目标页完整覆盖视口。已将手势检查改为分别确认目标帧提交和最终选中状态，点击测量的状态筛选保持原样。

本轮不以这些回归结果宣称整体帧率提升；主要验证动效与页面进度同步，以及没有引入逐帧组合。

## 模拟器补测

用户转接 ADB 后，仅对 `127.0.0.1:16384` 操作。模拟器 Android 15 / API 35，1440 × 2560，密度 640，首选 ABI 为 x86_64；通过 `adb install -r` 安装当前 x86_64 主包及测试包。

上述 **22 项回归在模拟器全部通过**。另在真实 `MainActivity` 中分别验证编辑模式与播放器模式，每种模式执行三轮、共 15 次底部点击及两个方向的手势，两个功能测试均通过。因此本次模拟器合计 **24 项通过**。拖动中间帧已检查，胶囊与页面位移同步；模拟器原播放器模式为开启，测试后已同步恢复，USB 保持唤醒恢复为原值 `0`。

模拟器的额外帧采样初次漏记过完整目标帧，失败时保存的几何证据显示：选中页正确、窗口有焦点、目标页与分页视口边界一致。添加仅用于功能验证的 `functionalVerification=true`：若帧回调未观察到最终覆盖，但当前选中项与完整页面几何均正确，则显式请求一帧确认提交。该样本的 `first_full_commit_ms` 设为 `null`，并标记 `explicit_confirmation_frame=true`，不作为实际切页耗时。最终播放器 15 次点击中 9 次、编辑模式 15 次点击中 12 次采用显式确认帧。默认性能测量不启用这个分支，模拟器结果也未用于宣称真机帧率或延迟改善。

日志及中间帧：`tmp/elastic-bottom-navigation-20261118/emulator-16384/`，`navigation-tests.txt`、`player-final-real-input.txt`、`editor-final-real-input.txt`。`player-swipe-mid.png` 与 `editor-swipe-mid.png` 是实际页面的手动拖动中间帧。

x86_64 debug APK SHA-256：`97d630e82d43a1c454a2a4b418033ced2b8d1dfcf0aa8afc8a3d948927aacff2`。

构建及设备日志：`tmp/elastic-bottom-navigation-20261118/`，最终回归日志 `final-tests.txt`。胶囊阶段截图、RTL 对照及真实拖动截图位于同目录。

ARM64 debug APK：`app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk`，SHA-256：`35c785df1c0fd7e0ebbe8fe66454b17bfd5f7453674a28f04e30649e9f82a7fc`。

最终测试 APK SHA-256：`13454f71a915a81ad77ef5d5b5e4bfc5c848c630ce46aa424c5d26270893bb9c`。
