package com.subtitleedit.util

import com.subtitleedit.SubtitleEntry
import java.io.BufferedReader
import java.io.StringReader
import java.nio.charset.Charset

/**
 * 字幕解析器
 * 支持 SRT 和 LRC 格式的解析和生成
 */
object SubtitleParser {
    
    /**
     * 字幕格式枚举
     */
    enum class SubtitleFormat {
        SRT,
        LRC,
        TXT,
        UNKNOWN
    }
    
    /**
     * 解析字幕内容
     * @param content 文件内容
     * @param charset 字符编码
     * @return 解析后的字幕列表
     */
    fun parse(content: String, charset: Charset = Charsets.UTF_8): List<SubtitleEntry> {
        val format = detectFormat(content)
        return when (format) {
            SubtitleFormat.SRT -> parseSRT(content)
            SubtitleFormat.LRC -> parseLRC(content)
            SubtitleFormat.TXT -> parseTXT(content)
            else -> emptyList()
        }
    }
    
    /**
     * 检测字幕格式
     */
    fun detectFormat(content: String): SubtitleFormat {
        val trimmed = content.trim()
        
        // SRT 格式特征：以数字开头，包含 --> 时间轴标记
        if (trimmed.matches(Regex("^\\d+.*"))) {
            if (trimmed.contains("-->")) {
                return SubtitleFormat.SRT
            }
        }
        
        // LRC 格式特征：以 [ 开头，包含时间标签
        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            return SubtitleFormat.LRC
        }
        
        // 尝试通过内容特征判断
        if (trimmed.contains("-->")) {
            return SubtitleFormat.SRT
        }
        
        if (Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}\\]").containsMatchIn(trimmed)) {
            return SubtitleFormat.LRC
        }
        
        // TXT 格式：纯文本，每行一条字幕（没有 --> 和 [] 时间标签）
        if (trimmed.isNotEmpty()) {
            return SubtitleFormat.TXT
        }
        
        return SubtitleFormat.UNKNOWN
    }
    
    /**
     * 解析 SRT 格式
     */
    fun parseSRT(content: String): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        val reader = BufferedReader(StringReader(content))
        
        var currentIndex = 0
        var currentEntry: SubtitleEntry? = null
        var state = ParseState.INDEX
        
        while (true) {
            val rawLine = reader.readLine() ?: break
            val currentLine = rawLine.trim()
            
            when (state) {
                ParseState.INDEX -> {
                    if (currentLine.matches(Regex("^\\d+$"))) {
                        currentIndex = currentLine.toInt()
                        state = ParseState.TIME
                    }
                }
                ParseState.TIME -> {
                    if (currentLine.contains("-->")) {
                        val times = parseSRTTime(currentLine)
                        currentEntry = SubtitleEntry(
                            index = currentIndex,
                            startTime = times.first,
                            endTime = times.second
                        )
                        state = ParseState.TEXT
                    }
                }
                ParseState.TEXT -> {
                    if (currentLine.isEmpty()) {
                        // 空行表示条目结束
                        if (currentEntry != null && currentEntry.text.isNotEmpty()) {
                            entries.add(currentEntry)
                        }
                        currentEntry = null
                        state = ParseState.INDEX
                    } else {
                        // 累积文本
                        if (currentEntry != null) {
                            if (currentEntry.text.isNotEmpty()) {
                                currentEntry.text += "\n"
                            }
                            currentEntry.text += currentLine
                        }
                    }
                }
            }
        }
        
        // 处理最后一个条目
        if (currentEntry != null && currentEntry.text.isNotEmpty()) {
            entries.add(currentEntry)
        }
        
        // 重新编号
        entries.forEachIndexed { index, entry ->
            entry.index = index + 1
        }
        
        return entries
    }
    
    /**
     * 解析 LRC 格式
     */
    fun parseLRC(content: String): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        val reader = BufferedReader(StringReader(content))
        var currentIndex = 1
        
        val timePattern = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]")
        
        while (true) {
            val currentLine = reader.readLine() ?: break
            val matcher = timePattern.find(currentLine)
            if (matcher != null) {
                val minutes = matcher.groupValues[1].toLong()
                val seconds = matcher.groupValues[2].toLong()
                val millis = matcher.groupValues[3].padEnd(3, '0').toLong()
                
                val timeMs = minutes * 60000 + seconds * 1000 + millis
                
                // 获取时间标签后的文本
                val text = currentLine.substring(matcher.range.last + 1).trim()
                
                if (text.isNotEmpty()) {
                    entries.add(
                        SubtitleEntry(
                            index = currentIndex++,
                            startTime = timeMs,
                            endTime = timeMs + 3000, // 默认 3 秒持续时间
                            text = text
                        )
                    )
                }
            }
        }
        
        // 设置结束时间
        for (i in entries.indices) {
            if (i < entries.size - 1) {
                entries[i].endTime = entries[i + 1].startTime
            }
        }
        
        return entries
    }
    
    /**
     * 解析 SRT 时间轴
     * @return Pair<startTime, endTime> (毫秒)
     */
    private fun parseSRTTime(timeLine: String): Pair<Long, Long> {
        val parts = timeLine.split("-->").map { it.trim() }
        if (parts.size < 2) {
            return Pair(0L, 0L)
        }
        
        val startMs = parseSRTTimestamp(parts[0])
        val endMs = parseSRTTimestamp(parts[1])
        
        return Pair(startMs, endMs)
    }
    
    /**
     * 解析 SRT 时间戳
     */
    private fun parseSRTTimestamp(timestamp: String): Long {
        // 处理格式：HH:MM:SS,mmm 或 HH:MM:SS.mmm
        val cleanTimestamp = timestamp.replace(',', '.')
        val parts = cleanTimestamp.split(":")
        
        if (parts.size < 3) return 0L
        
        val hours = parts[0].toLongOrNull() ?: 0L
        val minutes = parts[1].toLongOrNull() ?: 0L
        val secondsParts = parts[2].split(".")
        val seconds = secondsParts[0].toLongOrNull() ?: 0L
        val millis = secondsParts.getOrNull(1)?.toLongOrNull() ?: 0L
        
        return hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
    }
    
    /**
     * 生成 SRT 格式内容
     */
    fun toSRT(entries: List<SubtitleEntry>): String {
        val sb = StringBuilder()
        entries.forEachIndexed { index, entry ->
            sb.appendLine(index + 1)
            sb.appendLine(entry.getTimeAxisSRT())
            sb.appendLine(entry.text)
            sb.appendLine()
        }
        return sb.toString()
    }
    
    /**
     * 生成 LRC 格式内容
     */
    fun toLRC(entries: List<SubtitleEntry>): String {
        val sb = StringBuilder()
        entries.forEach { entry ->
            sb.appendLine("${entry.getTimeAxisLRC()}${entry.text}")
        }
        return sb.toString()
    }
    
    /**
     * 解析 TXT 格式（纯文本，每行一条字幕）
     * 时间从 0 开始，每条字幕默认 3 秒
     */
    fun parseTXT(content: String): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        val reader = BufferedReader(StringReader(content))
        var currentIndex = 1
        var currentTime = 0L
        
        while (true) {
            val currentLine = reader.readLine() ?: break
            val text = currentLine.trim()
            
            if (text.isNotEmpty()) {
                entries.add(
                    SubtitleEntry(
                        index = currentIndex++,
                        startTime = currentTime,
                        endTime = currentTime + 3000,
                        text = text
                    )
                )
                currentTime += 3000
            }
        }
        
        return entries
    }
    
    /**
     * 生成 TXT 格式内容（纯文本，每行一条字幕）
     */
    fun toTXT(entries: List<SubtitleEntry>): String {
        val sb = StringBuilder()
        entries.forEach { entry ->
            sb.appendLine(entry.text)
        }
        return sb.toString()
    }
    
    /**
     * 转换格式
     */
    fun convertFormat(content: String, from: SubtitleFormat, to: SubtitleFormat): String {
        val entries = when (from) {
            SubtitleFormat.SRT -> parseSRT(content)
            SubtitleFormat.LRC -> parseLRC(content)
            SubtitleFormat.TXT -> parseTXT(content)
            else -> emptyList()
        }
        
        return when (to) {
            SubtitleFormat.SRT -> toSRT(entries)
            SubtitleFormat.LRC -> toLRC(entries)
            SubtitleFormat.TXT -> toTXT(entries)
            else -> content
        }
    }
    
    private enum class ParseState {
        INDEX,
        TIME,
        TEXT
    }
}
