package com.subtitleedit.util

import com.subtitleedit.FileOperation
import org.junit.Assert.assertEquals
import org.junit.Test

class FileOperationUiPolicyTest {
    @Test
    fun selectionTitlesKeepOperationAndCount() {
        assertEquals("选择复制目标（已选 2 项）", FileOperationUiPolicy.selectionTitle(FileOperation.COPY, 2))
        assertEquals("选择移动目标（已选 3 项）", FileOperationUiPolicy.selectionTitle(FileOperation.MOVE, 3))
        assertEquals("选择解压目录", FileOperationUiPolicy.selectionTitle(FileOperation.EXTRACT, 0))
        assertEquals("已选择 1 项", FileOperationUiPolicy.selectionTitle(null, 1))
    }

    @Test
    fun destinationButtonLabelsMatchOperation() {
        assertEquals("移动到此处", FileOperationUiPolicy.destinationButtonLabel(FileOperation.MOVE))
        assertEquals("解压到此处", FileOperationUiPolicy.destinationButtonLabel(FileOperation.EXTRACT))
        assertEquals("复制到此处", FileOperationUiPolicy.destinationButtonLabel(FileOperation.COPY))
        assertEquals("复制到此处", FileOperationUiPolicy.destinationButtonLabel(null))
    }
}
