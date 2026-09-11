package com.subtitleedit.util

import com.subtitleedit.FileOperation

/** 文档浏览器选择/目标选择状态对应的界面文案。 */
internal object FileOperationUiPolicy {
    fun selectionTitle(operation: FileOperation?, selectedCount: Int): String = when (operation) {
        FileOperation.COPY -> "选择复制目标（已选 $selectedCount 项）"
        FileOperation.MOVE -> "选择移动目标（已选 $selectedCount 项）"
        FileOperation.EXTRACT -> "选择解压目录"
        null -> "已选择 $selectedCount 项"
    }

    fun destinationButtonLabel(operation: FileOperation?): String = when (operation) {
        FileOperation.MOVE -> "移动到此处"
        FileOperation.EXTRACT -> "解压到此处"
        FileOperation.COPY, null -> "复制到此处"
    }
}
