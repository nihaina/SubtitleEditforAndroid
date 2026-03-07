package com.subtitleedit

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
    var endTimeModified: Boolean = false
) {
    /**
     * 格式化时间戳为 SRT 格式 (HH:MM:SS,mmm)
     */
    fun formatTimeSRT(timeMs: Long): String {
        val hours = timeMs / 3600000
        val minutes = (timeMs % 3600000) / 60000
        val seconds = (timeMs % 60000) / 1000
        val millis = timeMs % 1000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
    
    /**
     * 格式化时间戳为 LRC 格式 ([MM:SS.xx])
     */
    fun formatTimeLRC(timeMs: Long): String {
        val minutes = timeMs / 60000
        val seconds = (timeMs % 60000) / 1000f
        return String.format("[%02d:%05.2f]", minutes, seconds)
    }
    
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
        return SubtitleEntry(index, startTime, endTime, text, endTimeModified)
    }
}
