package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/** Resolves a writable local parent directory for a picked source file. */
object SourceOutputDirectory {
    fun resolve(context: Context, sourceUri: Uri): Uri {
        val source = Qwen3ForcedAlignerPathResolver.resolve(context, sourceUri)
            ?: throw IOException("无法访问源文件所在目录")
        val parent = source.parentFile
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?: throw IOException("没有写入源文件夹的权限")
        return Uri.fromFile(parent)
    }
}
