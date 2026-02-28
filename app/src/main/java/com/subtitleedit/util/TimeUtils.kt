package com.subtitleedit.util

/**
 * 时间轴工具类
 * 用于时间轴的解析、格式化和偏移计算
 */
object TimeUtils {
    
    /**
     * 将毫秒时间转换为 SRT 格式字符串 (HH:MM:SS,mmm)
     */
    fun formatSRT(timeMs: Long): String {
        val hours = timeMs / 3600000
        val minutes = (timeMs % 3600000) / 60000
        val seconds = (timeMs % 60000) / 1000
        val millis = timeMs % 1000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
    
    /**
     * 将毫秒时间转换为 LRC 格式字符串 ([MM:SS.xx])
     */
    fun formatLRC(timeMs: Long): String {
        val minutes = timeMs / 60000
        val seconds = (timeMs % 60000) / 1000f
        return String.format("[%02d:%05.2f]", minutes, seconds)
    }
    
    /**
     * 将 SRT 格式时间字符串解析为毫秒
     * 支持格式：HH:MM:SS,mmm 或 HH:MM:SS.mmm
     */
    fun parseSRT(timeStr: String): Long {
        val cleanTime = timeStr.trim().replace(',', '.')
        val parts = cleanTime.split(":")
        
        if (parts.size < 3) return 0L
        
        val hours = parts[0].toLongOrNull() ?: 0L
        val minutes = parts[1].toLongOrNull() ?: 0L
        
        val secondsParts = parts[2].split(".")
        val seconds = secondsParts[0].toLongOrNull() ?: 0L
        val millis = secondsParts.getOrNull(1)?.toLongOrNull() ?: 0L
        
        return hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
    }
    
    /**
     * 将 LRC 格式时间字符串解析为毫秒
     * 支持格式：[MM:SS.xx] 或 [MM:SS.xxx]
     */
    fun parseLRC(timeStr: String): Long {
        val cleanTime = timeStr.trim().removePrefix("[").removeSuffix("]")
        val parts = cleanTime.split(":")
        
        if (parts.size < 2) return 0L
        
        val minutes = parts[0].toLongOrNull() ?: 0L
        val seconds = parts[1].toDoubleOrNull() ?: 0.0
        
        return minutes * 60000 + (seconds * 1000).toLong()
    }
    
    /**
     * 应用时间偏移
     * @param timeMs 原始时间 (毫秒)
     * @param offsetMs 偏移量 (毫秒)，正数表示延迟，负数表示提前
     * @return 偏移后的时间，如果结果为负数则返回 0
     */
    fun applyOffset(timeMs: Long, offsetMs: Long): Long {
        return maxOf(0, timeMs + offsetMs)
    }
    
    /**
     * 格式化毫秒为可读字符串
     */
    fun formatDuration(durationMs: Long): String {
        val hours = durationMs / 3600000
        val minutes = (durationMs % 3600000) / 60000
        val seconds = (durationMs % 60000) / 1000
        val millis = durationMs % 1000
        
        return buildString {
            if (hours > 0) {
                append("${hours}小时")
            }
            if (minutes > 0) {
                append("${minutes}分")
            }
            if (seconds > 0 || isEmpty()) {
                append("${seconds}秒")
            }
            if (millis > 0) {
                append("${millis}毫秒")
            }
        }
    }
    
    /**
     * 计算两个时间点之间的持续时间
     */
    fun calculateDuration(startTime: Long, endTime: Long): Long {
        return maxOf(0, endTime - startTime)
    }
    
    /**
     * 验证时间轴是否有效
     */
    fun isValidTimeAxis(startTime: Long, endTime: Long): Boolean {
        return startTime >= 0 && endTime > startTime
    }
    
    /**
     * 将毫秒转换为输入框友好的格式 (HH:MM:SS.mmm)
     */
    fun formatForInput(timeMs: Long): String {
        return formatSRT(timeMs).replace(',', '.')
    }
    
    /**
     * 从输入框解析时间
     */
    fun parseFromInput(input: String): Long? {
        if (input.isBlank()) return null
        return try {
            parseSRT(input)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 格式化时间用于显示（搜索时使用）
     */
    fun formatForDisplay(timeMs: Long): String {
        return formatForInput(timeMs)
    }
}
