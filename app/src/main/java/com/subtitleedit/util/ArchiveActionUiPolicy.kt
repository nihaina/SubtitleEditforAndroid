package com.subtitleedit.util

/** 归档动作菜单和阻塞进度对话框使用的稳定文案。 */
internal object ArchiveActionUiPolicy {
    val actionLabels: Array<String>
        get() = arrayOf("解压预览", "解压到当前文件夹", "解压到指定目录", "解压测试")

    fun progressTitle(action: ArchiveAction): String = when (action) {
        ArchiveAction.PREVIEW -> "正在读取压缩包"
        ArchiveAction.TEST -> "正在测试压缩包"
        ArchiveAction.EXTRACT_CURRENT -> error("不应直接执行解压操作")
    }

    enum class ArchiveAction { PREVIEW, EXTRACT_CURRENT, TEST }
}
