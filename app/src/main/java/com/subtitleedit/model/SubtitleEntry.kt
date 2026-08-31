package com.subtitleedit.model

import com.subtitleedit.util.TimeUtils
import java.util.concurrent.atomic.AtomicLong

/**
 * 字幕条目数据类
 * 表示单个字幕条目，包含时间轴和文本内容
 */
data class SubtitleEntry(
    var index: Int = 0,
    var startTime: Long = 0L,      // 开始时间 (毫秒)
    var endTime: Long = 0L,        // 结束时间 (毫秒)
    var text: String = "",         // 字幕文本
    // 标记 endTime 是否被用户修改过（用于 LRC 格式保存）
    var endTimeModified: Boolean = false,
    // WebVTT cue identifier；其它格式保持为空
    var cueIdentifier: String = "",
    // WebVTT 时间轴后的 cue settings；其它格式保持为空
    var cueSettings: String = "",
    // 仅用于编辑器内存中的稳定关联，不参与字幕格式序列化。
    var stableId: Long = nextStableId()
) {
    /**
     * 格式化时间戳为 SRT 格式 (HH:MM:SS,mmm)
     */
    fun formatTimeSRT(timeMs: Long): String = TimeUtils.formatSRT(timeMs)

    /**
     * 格式化时间戳为 LRC 格式 ([MM:SS.xx])
     */
    fun formatTimeLRC(timeMs: Long): String = TimeUtils.formatLRC(timeMs)
    
    /**
     * 获取 SRT 格式的时间轴字符串
     */
    fun getTimeAxisSRT(): String {
        return "${formatTimeSRT(startTime)} --> ${formatTimeSRT(endTime)}"
    }
    
    /**
     * 获取 LRC 格式的时间轴字符串
     */
    fun getTimeAxisLRC(): String {
        return formatTimeLRC(startTime)
    }
    
    /**
     * 复制当前条目
     */
    fun copy(): SubtitleEntry {
        return SubtitleEntry(
            index,
            startTime,
            endTime,
            text,
            endTimeModified,
            cueIdentifier,
            cueSettings,
            stableId
        )
    }

    private companion object {
        private val nextId = AtomicLong(1L)

        fun nextStableId(): Long = nextId.getAndIncrement()
    }
}
