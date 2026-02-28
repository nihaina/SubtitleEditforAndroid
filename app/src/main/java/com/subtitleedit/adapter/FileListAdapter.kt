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
import com.subtitleedit.util.FileUtils
import java.io.File

/**
 * 文件列表适配器
 */
class FileListAdapter(
    private val onItemClick: (File) -> Unit
) : ListAdapter<File, FileListAdapter.FileViewHolder>(FileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivFileIcon: ImageView = itemView.findViewById(R.id.ivFileIcon)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val tvFileExtension: TextView = itemView.findViewById(R.id.tvFileExtension)

        fun bind(file: File) {
            val context = itemView.context
            
            // 设置图标
            if (file.isDirectory) {
                ivFileIcon.setImageResource(R.drawable.ic_folder)
                tvFileSize.visibility = View.GONE
                tvFileExtension.visibility = View.GONE
            } else {
                ivFileIcon.setImageResource(R.drawable.ic_file)
                tvFileSize.text = FileUtils.formatFileSize(file.length())
                tvFileSize.visibility = View.VISIBLE
                tvFileExtension.text = file.extension.uppercase()
                tvFileExtension.visibility = View.VISIBLE
            }

            // 设置文件名
            tvFileName.text = file.name

            // 点击事件
            itemView.setOnClickListener {
                onItemClick(file)
            }
        }
    }

    private class FileDiffCallback : DiffUtil.ItemCallback<File>() {
        override fun areItemsTheSame(oldItem: File, newItem: File): Boolean {
            return oldItem.absolutePath == newItem.absolutePath
        }

        override fun areContentsTheSame(oldItem: File, newItem: File): Boolean {
            return oldItem.name == newItem.name && 
                   oldItem.length() == newItem.length() && 
                   oldItem.isDirectory == newItem.isDirectory
        }
    }
}
