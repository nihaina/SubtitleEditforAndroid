package com.subtitleedit.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.R
import com.subtitleedit.model.ArchivePreviewItem
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ArchivePreviewAdapter(
    private val onDirectoryClick: (ArchivePreviewItem) -> Unit
) : ListAdapter<ArchivePreviewItem, ArchivePreviewAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.ivFileIcon)
        private val name: TextView = itemView.findViewById(R.id.tvFileName)
        private val detailsRow: View = itemView.findViewById(R.id.fileDetailsRow)
        private val size: TextView = itemView.findViewById(R.id.tvFileSize)
        private val mediaDuration: TextView = itemView.findViewById(R.id.tvMediaDuration)
        private val extension: TextView = itemView.findViewById(R.id.tvFileExtension)
        private val modifiedTime: TextView = itemView.findViewById(R.id.tvFileModifiedTime)
        private val modifiedTimeFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        fun bind(item: ArchivePreviewItem) {
            name.text = item.name
            icon.setImageResource(iconFor(item))
            mediaDuration.visibility = View.GONE
            if (item.modifiedTimeMillis > 0L) {
                modifiedTime.text = modifiedTimeFormat.format(Date(item.modifiedTimeMillis))
                modifiedTime.visibility = View.VISIBLE
            } else {
                modifiedTime.visibility = View.GONE
            }
            if (item.isDirectory) {
                detailsRow.visibility = View.VISIBLE
                size.text = if (item.itemCount == 0) {
                    itemView.context.getString(R.string.directory_empty)
                } else {
                    itemView.context.getString(R.string.directory_item_count, item.itemCount)
                }
                size.visibility = View.VISIBLE
                extension.text = ""
                extension.visibility = View.INVISIBLE
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.setOnClickListener { onDirectoryClick(item) }
            } else {
                detailsRow.visibility = View.VISIBLE
                size.text = if (item.size >= 0L) FileUtils.formatFileSize(item.size) else "大小未知"
                size.visibility = View.VISIBLE
                val suffix = item.name.substringAfterLast('.', "").uppercase()
                extension.text = suffix
                extension.visibility = if (suffix.isEmpty()) View.GONE else View.VISIBLE
                itemView.isClickable = false
                itemView.isFocusable = false
                itemView.setOnClickListener(null)
            }
            itemView.setOnLongClickListener(null)
            itemView.isLongClickable = false
        }

        private fun iconFor(item: ArchivePreviewItem): Int {
            if (item.isDirectory) return R.drawable.ic_folder
            val file = File(item.name)
            val suffix = file.extension.lowercase()
            return when {
                FileUtils.isAudioFile(file) -> R.drawable.ic_file_audio
                FileUtils.isSubtitleFile(file) || FileTypePolicy.isText(file) -> R.drawable.ic_file_text
                suffix in VIDEO_EXTENSIONS -> R.drawable.ic_file_video
                suffix in ArchiveManager.recognizedExtensions -> R.drawable.ic_file_archive
                else -> R.drawable.ic_file
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ArchivePreviewItem>() {
        override fun areItemsTheSame(oldItem: ArchivePreviewItem, newItem: ArchivePreviewItem) =
            oldItem.path == newItem.path

        override fun areContentsTheSame(oldItem: ArchivePreviewItem, newItem: ArchivePreviewItem) =
            oldItem == newItem
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v")
    }
}
