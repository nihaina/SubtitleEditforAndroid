package com.subtitleedit.util

import android.os.Build
import android.os.Environment
import java.io.File

internal object AndroidDirectoryPolicy {
    fun isRestricted(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            !file.isDirectory ||
            !file.name.equals("Android", ignoreCase = true)
        ) return false

        val storageRoot = Environment.getExternalStorageDirectory()
        val parentPath = runCatching { file.parentFile?.canonicalPath }.getOrNull()
        val storageRootPath = runCatching { storageRoot.canonicalPath }.getOrNull()
        return parentPath != null && parentPath == storageRootPath
    }
}
