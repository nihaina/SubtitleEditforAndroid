package com.subtitleedit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.adapter.FileListAdapter
import com.subtitleedit.databinding.ActivityDraftsBinding
import com.subtitleedit.databinding.ActivitySettingsBinding
import com.subtitleedit.databinding.ActivityToolsBinding
import com.subtitleedit.databinding.FragmentFavoritesBinding
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.DraftManager
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import java.io.File
import java.util.Locale

interface TopLevelBackHandler {
    fun handleTopLevelBack(): Boolean
}

class FavoritesFragment : Fragment() {
    private var binding: FragmentFavoritesBinding? = null
    private lateinit var adapter: FileListAdapter
    private val preferences: SharedPreferences by lazy {
        requireContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addDirectory(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val viewBinding = FragmentFavoritesBinding.inflate(inflater, container, false)
        binding = viewBinding
        adapter = FileListAdapter(
            onItemClick = { directory ->
                (activity as? MainActivity)?.openDirectoryFromFavorites(directory)
            },
            onItemLongClick = ::confirmRemoveDirectory
        )
        viewBinding.rvFavoriteDirectories.layoutManager = LinearLayoutManager(requireContext())
        viewBinding.rvFavoriteDirectories.adapter = adapter
        viewBinding.btnAddFavoriteDirectory.setOnClickListener { directoryPicker.launch(null) }
        loadDirectories()
        return viewBinding.root
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) loadDirectories()
    }

    private fun addDirectory(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        val directory = File(DirectoryDisplayPath.fromUri(requireContext(), uri))
        if (!directory.isDirectory || !directory.canRead()) {
            Toast.makeText(requireContext(), R.string.favorite_directory_access_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val paths = savedPaths().toMutableSet()
        if (!paths.add(directory.absolutePath)) {
            Toast.makeText(requireContext(), R.string.favorite_directory_already_added, Toast.LENGTH_SHORT).show()
            return
        }
        preferences.edit().putStringSet(KEY_PATHS, paths).apply()
        loadDirectories()
    }

    private fun confirmRemoveDirectory(directory: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_favorite_directory)
            .setMessage(getString(R.string.remove_favorite_directory_confirm, directory.name))
            .setPositiveButton(R.string.confirm) { _, _ ->
                val paths = savedPaths().toMutableSet()
                paths.remove(directory.absolutePath)
                preferences.edit().putStringSet(KEY_PATHS, paths).apply()
                loadDirectories()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun savedPaths(): Set<String> = preferences.getStringSet(KEY_PATHS, emptySet())?.toSet().orEmpty()

    private fun loadDirectories() {
        val directories = savedPaths().map(::File).sortedBy { it.name.lowercase(Locale.getDefault()) }
        adapter.submitList(directories)
        binding?.emptyFavoriteDirectories?.visibility = if (directories.isEmpty()) View.VISIBLE else View.GONE
        binding?.rvFavoriteDirectories?.visibility = if (directories.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        binding?.rvFavoriteDirectories?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val PREFERENCES_NAME = "favorite_directories"
        const val KEY_PATHS = "paths"
    }
}

class ToolsFragment : Fragment() {
    private var binding: ActivityToolsBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val viewBinding = ActivityToolsBinding.inflate(inflater, container, false)
        binding = viewBinding
        val content = viewBinding.toolsContent
        (content.parent as ViewGroup).removeView(content)
        content.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        ToolCardShadow.remove(
            viewBinding.cardBatchConvert,
            viewBinding.cardSubtitleFormat,
            viewBinding.cardVocalSeparation,
            viewBinding.cardSpeechToSubtitle,
            viewBinding.cardMediaConvert,
            viewBinding.cardAutoTimestamp
        )
        viewBinding.cardBatchConvert.setOnClickListener { open(BatchConvertActivity::class.java) }
        viewBinding.cardSubtitleFormat.setOnClickListener { open(SubtitleFormatSelectActivity::class.java) }
        viewBinding.cardMediaConvert.setOnClickListener { open(MediaConvertActivity::class.java) }
        viewBinding.cardSpeechToSubtitle.setOnClickListener { open(SpeechToSubtitleActivity::class.java) }
        viewBinding.cardVocalSeparation.setOnClickListener { open(VocalSeparationActivity::class.java) }
        viewBinding.cardAutoTimestamp.setOnClickListener { open(AutoTimestampActivity::class.java) }
        return content
    }

    private fun open(type: Class<*>) = startActivity(Intent(requireContext(), type))
    override fun onDestroyView() { binding = null; super.onDestroyView() }
}

class SettingsFragment : Fragment() {
    private var binding: ActivitySettingsBinding? = null
    private val settings by lazy { SettingsManager.getInstance(requireContext()) }
    private var loadingSettings = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val viewBinding = ActivitySettingsBinding.inflate(inflater, container, false)
        binding = viewBinding
        viewBinding.toolbar.visibility = View.GONE
        setup(viewBinding)
        load(viewBinding)
        return viewBinding.root
    }

    override fun onResume() {
        super.onResume()
        binding?.let(::load)
    }

    private fun setup(b: ActivitySettingsBinding) {
        b.layoutEncoding.setOnClickListener { showEncodingDialog() }
        b.layoutTheme.setOnClickListener { showThemeDialog() }
        b.layoutAiSettings.setOnClickListener { open(AiSettingsActivity::class.java) }
        b.layoutModelSettings.setOnClickListener { open(ModelSettingsActivity::class.java) }
        b.layoutTtsSettings.setOnClickListener { open(TtsSettingsActivity::class.java) }
        b.layoutModelManagement.setOnClickListener { open(ModelManagementActivity::class.java) }
        b.layoutLog.setOnClickListener { open(LogActivity::class.java) }
        b.layoutAbout.setOnClickListener { open(AboutActivity::class.java) }
        b.layoutClearCache.setOnClickListener { showClearCacheDialog() }
        b.switchLoopSelectedSubtitle.setOnCheckedChangeListener { _, checked ->
            if (!loadingSettings) settings.setLoopSelectedSubtitleEnabled(checked)
        }
        b.switchCheckUpdatesOnStartup.setOnCheckedChangeListener { _, checked ->
            if (!loadingSettings) settings.setCheckUpdatesOnStartup(checked)
        }
        b.switchPreserveOutputDirectories.setOnCheckedChangeListener { _, checked ->
            if (!loadingSettings) settings.setOutputDirectoryPersistenceEnabled(checked)
        }
    }

    private fun load(b: ActivitySettingsBinding) {
        loadingSettings = true
        updateEncodingLabel()
        b.switchLoopSelectedSubtitle.isChecked = settings.isLoopSelectedSubtitleEnabled()
        b.switchCheckUpdatesOnStartup.isChecked = settings.shouldCheckUpdatesOnStartup()
        b.switchPreserveOutputDirectories.isChecked = settings.isOutputDirectoryPersistenceEnabled()
        updateThemeLabel()
        refreshCacheSize()
        loadingSettings = false
    }

    private fun showThemeDialog() {
        val modes = arrayOf("亮色主题", "深色主题", "跟随系统")
        val selected = when (settings.getThemeMode()) {
            SettingsManager.THEME_LIGHT -> 0
            SettingsManager.THEME_DARK -> 1
            else -> 2
        }
        AlertDialog.Builder(requireContext()).setTitle("主题").setSingleChoiceItems(modes, selected) { dialog, which ->
            val mode = when (which) {
                0 -> SettingsManager.THEME_LIGHT
                1 -> SettingsManager.THEME_DARK
                else -> SettingsManager.THEME_SYSTEM
            }
            settings.setThemeMode(mode)
            AppCompatDelegate.setDefaultNightMode(when (mode) {
                SettingsManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                SettingsManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            })
            updateThemeLabel()
            dialog.dismiss()
        }.show()
    }

    private fun showEncodingDialog() {
        val current = FileUtils.SUPPORTED_ENCODINGS.indexOfFirst {
            it.charset == settings.getDefaultEncoding()
        }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.activity_settings_text_01)
            .setSingleChoiceItems(
                FileUtils.SUPPORTED_ENCODINGS.map { it.displayName }.toTypedArray(),
                current
            ) { dialog, which ->
                settings.setDefaultEncoding(FileUtils.SUPPORTED_ENCODINGS[which].charset)
                updateEncodingLabel()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateEncodingLabel() {
        binding?.tvEncoding?.text = FileUtils.SUPPORTED_ENCODINGS
            .firstOrNull { it.charset == settings.getDefaultEncoding() }
            ?.displayName
            ?: settings.getDefaultEncoding().displayName()
    }

    private fun updateThemeLabel() {
        binding?.tvThemeMode?.text = when (settings.getThemeMode()) {
            SettingsManager.THEME_LIGHT -> "亮色"
            SettingsManager.THEME_DARK -> "深色"
            else -> "跟随系统"
        }
    }

    private fun cacheGroups(): List<Pair<String, List<File>>> {
        val waveform = File(requireContext().cacheDir, "waveform").walkTopDown().filter { it.isFile }.toList()
        val quick = requireContext().cacheDir.walkTopDown().filter {
            it.isFile && it.name.startsWith("quick_transcribe_") && it.name.endsWith("_16k.wav")
        }.toList()
        return listOf(
            "波形图缓存" to waveform.filter { it.extension == "wave" },
            "频谱图缓存" to waveform.filter { it.extension == "png" && it.name.contains(".spec_") },
            "快速转录音频缓存" to quick
        )
    }

    private fun showClearCacheDialog() {
        val groups = cacheGroups()
        AlertDialog.Builder(requireContext()).setTitle("清除缓存")
            .setItems(groups.map { "${it.first}（${formatSize(it.second.sumOf(File::length))}）" }.toTypedArray()) { _, which ->
                val count = groups[which].second.count { it.delete() }
                Toast.makeText(requireContext(), "已清除 $count 个缓存文件", Toast.LENGTH_SHORT).show()
                refreshCacheSize()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun refreshCacheSize() {
        val total = cacheGroups().sumOf { group -> group.second.sumOf(File::length) }
        binding?.tvTotalCacheSize?.text = if (total > 0) formatSize(total) else ""
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(Locale.getDefault(), bytes / 1024.0)} KB"
        else -> "${"%.2f".format(Locale.getDefault(), bytes / 1024.0 / 1024.0)} MB"
    }

    private fun open(type: Class<*>) = startActivity(Intent(requireContext(), type))
    override fun onDestroyView() { binding = null; super.onDestroyView() }
}

class DraftsFragment : Fragment(), TopLevelBackHandler {
    private var binding: ActivityDraftsBinding? = null
    private lateinit var adapter: DraftAdapter
    private var currentFolder = ""
    private var draftToExport: DraftItem? = null
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val draft = draftToExport
        if (uri != null && draft != null) runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use {
                it.write(DraftManager.readDraft(requireContext(), draft.folder, draft.file).toByteArray())
            }
        }.onSuccess { toast("导出成功") }.onFailure { toast("导出失败：${it.message}") }
        draftToExport = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val b = ActivityDraftsBinding.inflate(inflater, container, false)
        binding = b
        b.toolbar.visibility = View.GONE
        adapter = DraftAdapter(::onClick, ::showLongMenu, ::confirmDelete)
        b.rvDrafts.layoutManager = LinearLayoutManager(requireContext())
        b.rvDrafts.adapter = adapter
        loadDrafts()
        return b.root
    }

    override fun onResume() { super.onResume(); updateToolbar(); loadDrafts() }

    private fun onClick(item: DraftItem) {
        if (item.folderItem) {
            currentFolder = item.folder
            loadDrafts()
            updateToolbar()
        } else showPreview(item)
    }

    private fun loadDrafts() {
        val items = if (currentFolder.isEmpty()) {
            DraftManager.getAllDraftFolders(requireContext()).map { DraftItem(it.name, "", it.name, "文件夹", true) }
        } else {
            DraftManager.getDraftsInFolder(requireContext(), currentFolder).map {
                DraftItem(currentFolder, it.name, it.name, DraftManager.getFormattedDate(it), false)
            }
        }
        adapter.submit(items)
        binding?.tvEmpty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateToolbar() {
        (activity as? MainActivity)?.updateTopLevelToolbar(
            if (currentFolder.isEmpty()) getString(R.string.drafts) else currentFolder,
            currentFolder.isNotEmpty()
        ) {
            currentFolder = ""
            loadDrafts()
            updateToolbar()
        }
    }

    override fun handleTopLevelBack(): Boolean {
        if (currentFolder.isEmpty()) return false
        currentFolder = ""
        loadDrafts()
        updateToolbar()
        return true
    }

    private fun showPreview(item: DraftItem) {
        val content = DraftManager.readDraft(requireContext(), item.folder, item.file)
        val scroll = android.widget.ScrollView(requireContext()).apply {
            setPadding(50, 40, 50, 40)
            addView(TextView(requireContext()).apply { text = content; textSize = 14f; setLineSpacing(0f, 1.3f) })
        }
        AlertDialog.Builder(requireContext()).setTitle("预览：${item.name}").setView(scroll)
            .setNeutralButton("复制全文") { _, _ -> copy(content) }
            .setPositiveButton(R.string.confirm, null).show()
    }

    private fun showLongMenu(item: DraftItem) {
        AlertDialog.Builder(requireContext()).setTitle(item.name)
            .setItems(arrayOf("导出草稿", "复制全文", "删除草稿")) { _, which ->
                when (which) {
                    0 -> { draftToExport = item; exporter.launch(item.file) }
                    1 -> copy(DraftManager.readDraft(requireContext(), item.folder, item.file))
                    2 -> confirmDelete(item)
                }
            }.show()
    }

    private fun confirmDelete(item: DraftItem) {
        AlertDialog.Builder(requireContext()).setTitle(R.string.delete)
            .setMessage(if (item.folderItem) "确定要删除此文件夹及其所有内容吗？" else getString(R.string.delete_draft_confirm))
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (item.folderItem) DraftManager.deleteDraftFolder(requireContext(), item.folder)
                else DraftManager.deleteDraft(requireContext(), item.folder, item.file)
                loadDrafts()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun copy(content: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("draft", content))
        toast("已复制到剪贴板")
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    override fun onDestroyView() { binding = null; super.onDestroyView() }

    private data class DraftItem(val folder: String, val file: String, val name: String, val date: String, val folderItem: Boolean)

    private class DraftAdapter(
        val click: (DraftItem) -> Unit,
        val longClick: (DraftItem) -> Unit,
        val delete: (DraftItem) -> Unit
    ) : RecyclerView.Adapter<DraftAdapter.Holder>() {
        private var items = emptyList<DraftItem>()
        fun submit(value: List<DraftItem>) { items = value; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_draft, parent, false)
        )
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.tvDraftName)
            private val date: TextView = view.findViewById(R.id.tvDraftDate)
            private val remove: ImageView = view.findViewById(R.id.btnDelete)
            fun bind(item: DraftItem) {
                name.text = item.name
                date.text = item.date
                itemView.setOnClickListener { click(item) }
                itemView.setOnLongClickListener { if (!item.folderItem) longClick(item); true }
                remove.setOnClickListener { delete(item) }
            }
        }
    }
}
