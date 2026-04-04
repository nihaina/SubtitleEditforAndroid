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
     * 支持解析空白时间标签作为上一行的结束时间
     * 例如：
     * [00:00.00] 字幕内容
     * [00:01.00](空白)
     * [00:02.00] 下一行字幕
     * 则第一行的结束时间为 00:01.00
     */
    fun parseLRC(content: String): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        val reader = BufferedReader(StringReader(content))
        var currentIndex = 1
        
        val timePattern = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]")
        
        // 首先解析所有时间标签和文本
        val timeTags = mutableListOf<Pair<Long, String>>() // Pair<时间，文本>
        
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
                
                timeTags.add(Pair(timeMs, text))
            }
        }
        
        // 处理时间标签，确定每个字幕的结束时间
        for (i in timeTags.indices) {
            val (startTime, text) = timeTags[i]
            
            // 如果文本为空，跳过（这是空白时间标签）
            if (text.isEmpty()) continue
            
            var endTime = startTime + 6000 // 默认 6 秒持续时间
            var endTimeModified = false
            
            // 查找下一个时间标签
            if (i < timeTags.size - 1) {
                val (nextStartTime, nextText) = timeTags[i + 1]
                
                if (nextText.isEmpty()) {
                    // 下一行是空白时间标签，使用它作为结束时间
                    endTime = nextStartTime
                    endTimeModified = true // 空白时间标签表示用户明确设置了结束时间
                } else if (nextStartTime < startTime + 6000) {
                    // 下一行起始时间小于 6 秒，使用下一行起始时间作为结束时间
                    endTime = nextStartTime
                }
                // 否则保持默认 6 秒
            }
            
            entries.add(
                SubtitleEntry(
                    index = currentIndex++,
                    startTime = startTime,
                    endTime = endTime,
                    text = text,
                    endTimeModified = endTimeModified
                )
            )
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
     * 优化：对比下一行开始时间，若有空隙则插入终止标签
     */
    fun toLRC(entries: List<SubtitleEntry>): String {
        val sb = StringBuilder()
        
        for (i in entries.indices) {
            val entry = entries[i]
            
            // 1. 输出当前字幕及其起始时间
            sb.appendLine("${entry.getTimeAxisLRC()}${entry.text}")
            
            // 2. 决定是否需要插入终止（空白）时间标签
            val nextEntry = entries.getOrNull(i + 1)
            
            if (nextEntry != null) {
                // 如果当前结束时间早于下一条开始时间（存在空白期），则需要终止标签
                if (entry.endTime < nextEntry.startTime) {
                    sb.appendLine(entry.formatTimeLRC(entry.endTime))
                }
            } else {
                // 如果是最后一条字幕，通常也需要输出终止标签，否则播放器会一直显示到结束
                sb.appendLine(entry.formatTimeLRC(entry.endTime))
            }
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
