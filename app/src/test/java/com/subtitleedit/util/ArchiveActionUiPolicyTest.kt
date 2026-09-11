package com.subtitleedit.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveActionUiPolicyTest {
    @Test
    fun actionLabelsStayInMenuOrder() {
        assertArrayEquals(
            arrayOf("解压预览", "解压到当前文件夹", "解压到指定目录", "解压测试"),
            ArchiveActionUiPolicy.actionLabels
        )
    }

    @Test
    fun progressTitlesMatchAction() {
        assertEquals("正在读取压缩包", ArchiveActionUiPolicy.progressTitle(ArchiveActionUiPolicy.ArchiveAction.PREVIEW))
        assertEquals("正在测试压缩包", ArchiveActionUiPolicy.progressTitle(ArchiveActionUiPolicy.ArchiveAction.TEST))
    }
}
