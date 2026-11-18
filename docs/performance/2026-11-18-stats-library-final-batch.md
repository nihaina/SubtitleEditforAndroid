# 统计页与媒体库最后一批优化及统一验证

本轮在已有的「目录刷新与播放写入解耦」「统计排名按标签加载」「热力图首屏减负」基础上，处理仍在统计查询和摘要更新路径里的重复工作，不变更界面布局、点击和排序规则。

## 改动

- 每份统计快照在 IO 调度器上建立日期索引。日期和统计粒度切换只遍历所选区间及对比区间，不再在主线程把全部历史转成 Map。热力图通过同一索引查当天值，无需扫描全部历史来筛选年份。
- 「播放最多」由 `(play_count DESC, played_at_ms DESC, id DESC)` 索引直接提供顺序。「从未播放」将目录、收藏和歌单各自按 `(title COLLATE NOCASE, path)` 读成有序流；SQLite 合并后取 `LIMIT`，不先把所有候选放入临时排序。仍优先显示索引条目的标签，其次是收藏，最后是最早的歌单条目；播放历史或收听会话均能排除候选。
- 「热门作品」仍需要汇总所选时间范围的收听会话，但只为最终入围的作品统计不同播放 ID 和迁移前的旧播放次数，保留时长和路径的排序及并列规则。
- 数据库从 v4 升到 v5，只新增五个索引，不重建表或清除媒体库、歌单、收藏及历史。首次打开旧库有一次性建索引成本；不应直接用只识别 v4 的旧 APK 打开已升级的数据库。

## 统一验证

Standard Debug 的 JVM 测试为 1,974 通过、22 跳过，主包和测试包构建通过。最终 Standard Release 构建亦通过，四个 APK 经 ZIP 完整性检查；Release 未安装运行。在 Android 15 / x86_64 模拟器用 `adb install -r` 覆盖安装后，媒体库与统计页的 112 项测试通过。首轮旧版迁移夹具暴露了「索引已存在」错误；新索引创建改为幂等后重跑了整批。摘要单测用 20,000 条合成历史验证窄区间只访问匹配的两个日期，并覆盖同日重复数据与负数修正。

### 50,000 条数据库记录

`MediaPlaybackRankingTest` 使用单独的 SQLite 文件和 50,000 条合成曲目、12,500 条播放历史，以及收藏、重复的歌单记录和只有收听会话的条目；不创建 50,000 个真实媒体文件。先执行冻结的 v4 查询，再用实际 `SQLiteOpenHelper` 升级同一份数据库，比较返回条目、元数据优先级及排序。查询在各版本分别预热 5 次并测量 15 次，以下是读取结果的墙钟中位数：

| 查询 | v4 | v5 |
| --- | ---: | ---: |
| 从未播放前 100 项 | 21.23 ms | 0.40 ms |
| 播放最多前 20 项 | 0.65 ms | 0.05 ms |

Android 上的 `EXPLAIN QUERY PLAN` 显示，两条 v5 查询都没有临时排序，「从未播放」采用 `MERGE (UNION ALL)`；索引和排序对拍通过。打开旧测试库并升级耗时 47 ms（包括打开数据库，不是纯建索引时间）。另在主机 SQLite 3.45.3 上对两版「从未播放」SQL 进行了 100 组随机混合元数据/路径/历史、每组 6 个限制值的结果对拍，共 600 次通过。

这是特定行数、播放比例和缓存状态下的局部对照；大量候选都被排除时仍可能扫描很多索引项，查询耗时不等同于整页延迟，也未测量新增索引的长期写入开销。

### 4,096 个真实文件

同批运行了 `MediaLibraryLargeIndexTest`：首次扫描 848 ms，无变更重扫 124 ms（未再读标签），替换一个文件后 162 ms（仅新增文件读取标签）。24 次播放进度写入和 6 次收听记录写入没有触发额外全量目录查询。文件遍历及 SQLite 是真实路径，标签读取用轻量注入计数；这些扫描耗时不是前后提速对照，也不包含媒体解码。测试库和目录已清理。

## 复现

1. `gradlew.bat :app:testStandardDebugUnitTest :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest :app:assembleStandardRelease --console=plain`
   若原有 2 GB Gradle 堆在 Release 分 ABI 打包时出现 `Java heap space`，可单独重试 `gradlew.bat :app:assembleStandardRelease --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8' --console=plain`。本轮以这些临时参数通过，未修改项目默认构建配置。
2. 用 `adb install -r` 覆盖安装 Standard Debug x86_64 主包和测试包。通过 `adb shell am instrument -w -r -e class com.subtitleedit.library.MediaPlaybackRankingTest,com.subtitleedit.ui.MediaLibraryLargeIndexTest com.subtitleedit.test/androidx.test.runner.AndroidJUnitRunner` 可单独复跑两个数据压力用例。
3. 给统计页采集应用 atrace 时，可以用 `Stats:summary:aggregate` 单独观察区间聚合，不要将其与包含该工作的列表或页面时间相加。

本地日志、执行计划和测试类清单保留在忽略目录 `tmp/stats-final-batch-20261118/`。
