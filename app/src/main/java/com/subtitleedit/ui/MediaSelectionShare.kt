package com.subtitleedit.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.subtitleedit.R
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import java.io.File

/** Share the captured files through the app's existing provider, with read access for the recipient. */
internal fun mediaSelectionShareIntent(context: Context, paths: List<String>): Intent {
    val files = paths.distinct().map(::File)
    require(files.isNotEmpty() && files.all { it.isFile && (FileUtils.isAudioFile(it) || FileTypePolicy.isVideo(it)) }) {
        context.getString(R.string.media_lib_file_missing)
    }
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.provider", it) }
    val commonType = when {
        files.all(FileTypePolicy::isVideo) -> "video/*"
        files.none(FileTypePolicy::isVideo) -> "audio/*"
        else -> "*/*"
    }
    return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = commonType
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
        else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData = ClipData.newUri(context.contentResolver, files.first().name, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
