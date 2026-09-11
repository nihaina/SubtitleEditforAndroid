package com.subtitleedit.util

import com.subtitleedit.util.ArchiveManager.ProgressPhase

internal object ArchiveProgressPolicy {
    fun phaseLabel(phase: ProgressPhase): String = when (phase) {
        ProgressPhase.SCANNING -> "正在检查压缩包..."
        ProgressPhase.EXTRACTING -> "正在解压..."
    }
}
