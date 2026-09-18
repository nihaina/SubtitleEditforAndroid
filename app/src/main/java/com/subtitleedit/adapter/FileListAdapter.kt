package com.subtitleedit.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.subtitleedit.R
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 文件列表适配器
 */
class FileListAdapter(
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit,
    private val isItemRestricted: (File) -> Boolean = { false }
) : ListAdapter<File, FileListAdapter.FileViewHolder>(FileDiffCallback()) {

    private companion object {
        private val SELECTION_PAYLOAD = Any()
        val AUDIO_EXTENSIONS = FileUtils.AUDIO_EXTENSIONS + setOf("opus", "ac3", "amr")
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v",
            "ts", "3gp", "mpg", "mpeg", "mts", "m2ts"
        )
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff", "avif"
        )
    }

    private var selectionMode = false
    private var selectedPaths: Set<String> = emptySet()
    private val apkIconCache = mutableMapOf<String, Drawable?>()
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbnailCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val mediaDurationCache = ConcurrentHashMap<String, String>()
    private val missingMediaThumbnailCache = LruCache<String, Boolean>(512)
    private val pendingMediaPreviewKeys = ConcurrentHashMap.newKeySet<String>()
    private val directoryItemCountCache = ConcurrentHashMap<String, Int>()
    private val pendingDirectoryCountKeys = ConcurrentHashMap.newKeySet<String>()
    private val modifiedTimeFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var relativePathRoot: File? = null

    private data class MediaPreview(val duration: String, val thumbnail: Bitmap?)

    fun setRelativePathRoot(root: File?) {
        val previousPath = relativePathRoot?.absolutePath
        val newPath = root?.absolutePath
        if (previousPath == newPath) return
        relativePathRoot = root
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun updateSelection(selectionMode: Boolean, selectedPaths: Set<String>) {
        val modeChanged = this.selectionMode != selectionMode
        // MainViewModel exposes a mutable linked set. Keep a value snapshot here;
        // otherwise a later add/remove mutates the "previous" set as well and no
        // row receives a selection payload after the first click.
        val previousPaths = this.selectedPaths.toSet()
        this.selectionMode = selectionMode
        this.selectedPaths = selectedPaths.toSet()
        if (itemCount == 0) return
        if (modeChanged) {
            notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
            return
        }
        currentList.forEachIndexed { position, file ->
            if ((file.absolutePath in previousPaths) != (file.absolutePath in selectedPaths)) {
                notifyItemChanged(position, SELECTION_PAYLOAD)
            }
        }
    }

    /** Rebind every visible/current row after an AsyncListDiffer submission. */
    fun refreshSelectionVisuals() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.any { it === SELECTION_PAYLOAD }) {
            holder.bindSelectionVisual(getItem(position))
        } else {
            holder.bind(getItem(position))
        }
    }

    override fun onViewAttachedToWindow(holder: FileViewHolder) {
        super.onViewAttachedToWindow(holder)
        // A holder can survive a directory transition while AsyncListDiffer is
        // applying the new list. Reapply only the selection visuals at attach time
        // so a row never keeps the previous directory's alpha/stroke state.
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            holder.bindSelectionVisual(getItem(position))
        }
    }

    override fun onViewRecycled(holder: FileViewHolder) {
        holder.clearPendingBindings()
        super.onViewRecycled(holder)
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivFileIcon: ImageView = itemView.findViewById(R.id.ivFileIcon)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val fileDetailsRow: View = itemView.findViewById(R.id.fileDetailsRow)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val tvMediaDuration: TextView = itemView.findViewById(R.id.tvMediaDuration)
        private val tvFileExtension: TextView = itemView.findViewById(R.id.tvFileExtension)
        private val tvFileModifiedTime: TextView = itemView.findViewById(R.id.tvFileModifiedTime)
        private val card: MaterialCardView = itemView as MaterialCardView
        private val iconPadding = (8 * itemView.resources.displayMetrics.density + 0.5f).toInt()

        fun bind(file: File) {
            val isRestricted = isItemRestricted(file)
            val thumbnailKey = thumbnailKey(file)
            ivFileIcon.tag = thumbnailKey
            ivFileIcon.scaleType = ImageView.ScaleType.FIT_CENTER
            ivFileIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            ivFileIcon.clearColorFilter()
            // Every holder can be reused for a different kind of item. Reset view state
            // before applying the directory/file-specific values below.
            fileDetailsRow.visibility = View.VISIBLE
            tvFileSize.visibility = View.VISIBLE
            tvMediaDuration.visibility = View.GONE
            tvFileExtension.visibility = View.VISIBLE
            tvFileModifiedTime.visibility = View.VISIBLE

            // 设置图标
            if (file.isDirectory) {
                ivFileIcon.setImageResource(R.drawable.ic_folder)
                tvMediaDuration.tag = null
                tvMediaDuration.visibility = View.GONE
                tvFileExtension.text = ""
                tvFileExtension.visibility = if (file.name == "..") View.GONE else View.INVISIBLE
                tvFileModifiedTime.text = modifiedTimeFormat.format(Date(file.lastModified()))
                tvFileModifiedTime.visibility = if (file.name == "..") View.GONE else View.VISIBLE
                if (isRestricted || file.name == "..") {
                    fileDetailsRow.visibility = View.GONE
                    tvFileSize.tag = null
                    tvFileSize.visibility = View.GONE
                } else {
                    fileDetailsRow.visibility = View.VISIBLE
                    bindDirectoryItemCount(file, thumbnailKey)
                }
            } else {
                fileDetailsRow.visibility = View.VISIBLE
                tvFileSize.tag = null
                val extension = file.extension.lowercase()
                if (extension in IMAGE_EXTENSIONS) {
                    bindImageThumbnail(file, thumbnailKey)
                } else if (extension == "apk") {
                    ivFileIcon.setImageDrawable(loadApkIcon(file))
                } else {
                    ivFileIcon.setImageResource(
                        when {
                            extension in AUDIO_EXTENSIONS -> R.drawable.ic_file_audio
                            extension in VIDEO_EXTENSIONS -> R.drawable.ic_file_video
                            extension in ArchiveManager.recognizedExtensions -> archiveIcon(file)
                            FileUtils.isSubtitleFile(file) || FileTypePolicy.isText(file) ->
                                R.drawable.ic_file_text
                            else -> R.drawable.ic_file
                        }
                    )
                }
                tvFileSize.text = FileUtils.formatFileSize(file.length())
                tvFileSize.visibility = View.VISIBLE
                tvFileExtension.text = file.extension.uppercase()
                tvFileExtension.visibility = if (file.extension.isEmpty()) View.GONE else View.VISIBLE
                tvFileModifiedTime.text = modifiedTimeFormat.format(Date(file.lastModified()))
                tvFileModifiedTime.visibility = View.VISIBLE

                val isMediaFile = extension in AUDIO_EXTENSIONS || extension in VIDEO_EXTENSIONS
                if (isMediaFile) {
                    bindMediaPreview(file, thumbnailKey, extension in VIDEO_EXTENSIONS)
                } else {
                    tvMediaDuration.tag = null
                    tvMediaDuration.visibility = View.GONE
                }
            }
            ivFileIcon.alpha = if (file.name.startsWith(".") && file.name != "..") 0.5f else 1f

            // 设置文件名
            tvFileName.text = relativePathRoot?.let { root ->
                runCatching { file.relativeTo(root).path }.getOrDefault(file.name)
            } ?: file.name
            tvFileName.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    itemView.context,
                    if (isRestricted) R.color.on_surface_variant else R.color.on_surface
                )
            )
            if (isRestricted) {
                ivFileIcon.setColorFilter(
                    androidx.core.content.ContextCompat.getColor(
                        itemView.context,
                        R.color.on_surface_variant
                    )
                )
            }
            bindSelectionVisual(file)

            // 点击事件
            itemView.setOnClickListener {
                onItemClick(file)
            }
            itemView.setOnLongClickListener {
                if (isRestricted) onItemClick(file) else onItemLongClick(file)
                true
            }
        }

        fun bindSelectionVisual(file: File) {
            val isRestricted = isItemRestricted(file)
            val isSelected = file.absolutePath in selectedPaths
            card.strokeWidth = if (isSelected) 2 else 0
            card.strokeColor = if (isSelected) {
                androidx.core.content.ContextCompat.getColor(itemView.context, R.color.primary)
            } else {
                android.graphics.Color.TRANSPARENT
            }
            itemView.alpha = when {
                // Selection mode is a visual state of the current directory. Apply it to
                // every unselected item, including the synthetic parent-directory row.
                selectionMode && !isSelected -> 0.72f
                isRestricted -> 0.55f
                else -> 1f
            }
        }

        fun clearPendingBindings() {
            ivFileIcon.tag = null
            tvMediaDuration.tag = null
            tvFileSize.tag = null
        }

        private fun bindDirectoryItemCount(file: File, cacheKey: String) {
            tvFileSize.tag = cacheKey
            directoryItemCountCache[cacheKey]?.let { count ->
                showDirectoryItemCount(count)
                return
            }

            tvFileSize.text = ""
            tvFileSize.visibility = View.INVISIBLE
            if (!pendingDirectoryCountKeys.add(cacheKey)) return

            thumbnailExecutor.execute {
                val count = runCatching { file.list()?.size ?: -1 }.getOrDefault(-1)
                directoryItemCountCache[cacheKey] = count
                pendingDirectoryCountKeys.remove(cacheKey)
                mainHandler.post {
                    if (tvFileSize.tag == cacheKey) {
                        showDirectoryItemCount(count)
                    } else {
                        val position = currentList.indexOfFirst { thumbnailKey(it) == cacheKey }
                        if (position >= 0) notifyItemChanged(position)
                    }
                }
            }
        }

        private fun showDirectoryItemCount(count: Int) {
            tvFileSize.text = when {
                count < 0 -> ""
                count == 0 -> itemView.context.getString(R.string.directory_empty)
                else -> itemView.context.getString(R.string.directory_item_count, count)
            }
            tvFileSize.visibility = if (count < 0) View.GONE else View.VISIBLE
        }

        private fun bindMediaPreview(file: File, cacheKey: String, isVideo: Boolean) {
            tvMediaDuration.tag = cacheKey
            val cachedThumbnail = thumbnailCache.get(cacheKey)
            cachedThumbnail?.let(::showThumbnail)
            val cachedDuration = mediaDurationCache[cacheKey]
            if (cachedDuration != null) {
                showMediaDuration(cachedDuration)
            } else {
                tvMediaDuration.text = "00:00"
                tvMediaDuration.visibility = View.INVISIBLE
            }
            // Remember files without artwork as well, so scrolling does not repeatedly scan
            // every coverless audio file. An evicted bitmap can still be loaded again.
            if (cachedDuration != null &&
                (cachedThumbnail != null || missingMediaThumbnailCache.get(cacheKey) == true)
            ) return
            if (!pendingMediaPreviewKeys.add(cacheKey)) return

            val targetSize = (48 * itemView.resources.displayMetrics.density + 0.5f).toInt()
            thumbnailExecutor.execute {
                val preview = readMediaPreview(file, targetSize, isVideo)
                mediaDurationCache[cacheKey] = preview.duration
                if (preview.thumbnail != null) {
                    thumbnailCache.put(cacheKey, preview.thumbnail)
                    missingMediaThumbnailCache.remove(cacheKey)
                } else {
                    missingMediaThumbnailCache.put(cacheKey, true)
                }
                pendingMediaPreviewKeys.remove(cacheKey)
                mainHandler.post {
                    if (ivFileIcon.tag == cacheKey && tvMediaDuration.tag == cacheKey) {
                        showMediaDuration(preview.duration)
                        preview.thumbnail?.let(::showThumbnail)
                    } else {
                        val position = currentList.indexOfFirst { thumbnailKey(it) == cacheKey }
                        if (position >= 0) notifyItemChanged(position)
                    }
                }
            }
        }

        private fun showMediaDuration(duration: String) {
            tvMediaDuration.text = duration
            tvMediaDuration.visibility = if (duration.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun showThumbnail(bitmap: Bitmap) {
            ivFileIcon.scaleType = ImageView.ScaleType.CENTER_CROP
            ivFileIcon.setPadding(0, 0, 0, 0)
            ivFileIcon.setImageBitmap(bitmap)
        }

        private fun bindImageThumbnail(file: File, cacheKey: String) {
            thumbnailCache.get(cacheKey)?.let {
                showThumbnail(it)
                return
            }

            ivFileIcon.scaleType = ImageView.ScaleType.FIT_CENTER
            ivFileIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            ivFileIcon.setImageResource(R.drawable.ic_file)
            val targetSize = (48 * itemView.resources.displayMetrics.density + 0.5f).toInt()
            thumbnailExecutor.execute {
                val bitmap = runCatching { decodeSampledBitmap(file, targetSize) }.getOrNull()
                if (bitmap != null) thumbnailCache.put(cacheKey, bitmap)
                mainHandler.post {
                    if (ivFileIcon.tag != cacheKey) return@post
                    if (bitmap != null) {
                        showThumbnail(bitmap)
                    } else {
                        ivFileIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                        ivFileIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                        ivFileIcon.setImageResource(R.drawable.ic_file)
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        private fun loadApkIcon(file: File): Drawable? {
            val cacheKey = "${file.absolutePath}:${file.lastModified()}"
            if (apkIconCache.containsKey(cacheKey)) return apkIconCache[cacheKey]

            val packageManager = itemView.context.packageManager
            val icon = runCatching {
                val packageInfo = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                packageInfo?.applicationInfo?.let { applicationInfo ->
                    applicationInfo.sourceDir = file.absolutePath
                    applicationInfo.publicSourceDir = file.absolutePath
                    applicationInfo.loadIcon(packageManager)
                }
            }.getOrNull() ?: androidx.core.content.ContextCompat.getDrawable(
                itemView.context,
                R.drawable.ic_file
            )
            apkIconCache[cacheKey] = icon
            return icon
        }

        private fun decodeSampledBitmap(file: File, targetSize: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= targetSize &&
                bounds.outHeight / (sampleSize * 2) >= targetSize) {
                sampleSize *= 2
            }
            return BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }

        private fun readMediaPreview(file: File, targetSize: Int, isVideo: Boolean): MediaPreview {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                val duration = runCatching {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.let(::formatMediaDuration)
                }.getOrNull().orEmpty()
                // Read cover art and duration from one retriever. Artwork decoding errors must
                // not discard an otherwise valid duration or prevent the video-frame fallback.
                val cover = runCatching {
                    retriever.embeddedPicture?.let { decodeEmbeddedCover(it, targetSize) }
                }.getOrNull()
                val thumbnail = cover ?: if (isVideo) {
                    runCatching {
                        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(
                                -1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetSize, targetSize
                            )
                        } else {
                            retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                        frame?.let {
                            ThumbnailUtils.extractThumbnail(
                                it, targetSize, targetSize, ThumbnailUtils.OPTIONS_RECYCLE_INPUT
                            )
                        }
                    }.getOrNull()
                } else null
                MediaPreview(duration, thumbnail)
            } catch (_: Exception) {
                MediaPreview("", null)
            } finally {
                runCatching { retriever.release() }
            }
        }

        private fun decodeEmbeddedCover(data: ByteArray, targetSize: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // Bound decoding by the longer edge, including unusually wide/tall artwork.
            // Only a small square preview belongs in the shared thumbnail cache.
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= targetSize) {
                sampleSize *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(
                data, 0, data.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: return null
            return ThumbnailUtils.extractThumbnail(
                bitmap, targetSize, targetSize, ThumbnailUtils.OPTIONS_RECYCLE_INPUT
            )
        }

        private fun formatMediaDuration(durationMs: Long): String {
            val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = totalSeconds % 3600L / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

        private fun thumbnailKey(file: File): String =
            "${file.absolutePath}:${file.length()}:${file.lastModified()}"

        private fun archiveIcon(file: File): Int {
            val name = file.name.lowercase()
            return when {
                name.endsWith(".7z") -> R.drawable.ic_file_archive_7z
                name.endsWith(".rar") -> R.drawable.ic_file_archive_rar
                name.endsWith(".tar") -> R.drawable.ic_file_archive_tar
                name.endsWith(".gz") || name.endsWith(".tgz") -> R.drawable.ic_file_archive_gz
                name.endsWith(".bz") || name.endsWith(".bz2") ||
                    name.endsWith(".tbz") || name.endsWith(".tbz2") -> R.drawable.ic_file_archive_bz2
                name.endsWith(".xz") || name.endsWith(".txz") -> R.drawable.ic_file_archive_xz
                else -> R.drawable.ic_file_archive
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
                   oldItem.isDirectory == newItem.isDirectory &&
                   oldItem.lastModified() == newItem.lastModified()
        }
    }
}
