package com.subtitleedit

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.databinding.ActivityDraftsBinding
import com.subtitleedit.util.DraftManager
import java.io.File

/**
 * 草稿箱列表界面
 * 主界面模式：显示文件夹列表，点击文件夹进入查看草稿，支持预览和导出
 * 编辑器模式：直接显示所有草稿文件，点击返回草稿内容
 */
class DraftsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDraftsBinding
    private lateinit var adapter: DraftAdapter
    private val drafts = mutableListOf<DraftItem>()
    
    // 当前文件夹路径（为空表示在根目录）
    private var currentFolder: String = ""

    // 是否从编辑器打开（用于直接加载草稿到编辑器）
    private var fromEditor = false

    // 文件选择器（用于导出）
    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let { exportToUri(it) }
    }
    
    // 待导出的草稿
    private var draftToExport: DraftItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDraftsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fromEditor = intent.getBooleanExtra(EXTRA_FROM_EDITOR, false)

        setupToolbar()
        setupRecyclerView()
        loadDrafts()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (currentFolder.isNotEmpty()) {
            menuInflater.inflate(R.menu.menu_drafts, menu)
        }
        return super.onCreateOptionsMenu(menu)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_back_to_root -> {
                currentFolder = ""
                loadDrafts()
                invalidateOptionsMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        updateToolbarTitle()

        binding.toolbar.setNavigationOnClickListener {
            if (currentFolder.isNotEmpty()) {
                // 在非根目录时，返回根目录
                currentFolder = ""
                loadDrafts()
                invalidateOptionsMenu()
                updateToolbarTitle()
            } else {
                // 在根目录时，返回上一个 Activity
                onBackPressed()
            }
        }
    }
    
    private fun updateToolbarTitle() {
        binding.toolbar.title = if (currentFolder.isEmpty()) {
            getString(R.string.drafts)
        } else {
            currentFolder
        }
    }

    private fun setupRecyclerView() {
        adapter = DraftAdapter(
            onItemClick = { draft ->
                // 编辑器和主页模式都使用文件夹视图
                if (draft.isFolder) {
                    // 点击文件夹，进入查看
                    currentFolder = draft.folderName
                    loadDrafts()
                    invalidateOptionsMenu()
                    updateToolbarTitle()
                } else {
                    // 点击文件，显示预览对话框
                    showPreviewDialog(draft)
                }
            },
            onItemLongClick = { draft ->
                if (!draft.isFolder) {
                    showLongClickMenu(draft)
                }
            },
            onDeleteClick = { draft ->
                if (draft.isFolder) {
                    confirmDeleteFolder(draft)
                } else {
                    confirmDelete(draft)
                }
            }
        )

        binding.rvDrafts.apply {
            layoutManager = LinearLayoutManager(this@DraftsActivity)
            adapter = this@DraftsActivity.adapter
        }
    }

    private fun loadDrafts() {
        drafts.clear()
        
        // 编辑器和主页模式都使用文件夹视图
        if (currentFolder.isEmpty()) {
            // 根目录：显示文件夹列表
            val folders = DraftManager.getAllDraftFolders(this)
            drafts.addAll(folders.map { DraftItem(it.name, "", it.name, "", true) })
        } else {
            // 文件夹内：显示草稿文件列表
            val folderDrafts = DraftManager.getDraftsInFolder(this, currentFolder)
            drafts.addAll(folderDrafts.map { 
                DraftItem(currentFolder, it.name, it.name, DraftManager.getFormattedDate(it), false) 
            })
        }
        
        adapter.submitList(drafts.toList())

        binding.tvEmpty.visibility = if (drafts.isEmpty()) View.VISIBLE else View.GONE
    }
    
    /**
     * 显示草稿预览对话框
     */
    private fun showPreviewDialog(draft: DraftItem) {
        val content = DraftManager.readDraft(this, draft.folderName, draft.fileName)
        
        // 创建可滚动的文本视图
        val scrollView = android.widget.ScrollView(this).apply {
            setPadding(50, 40, 50, 40)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        val textView = android.widget.TextView(this).apply {
            text = content
            textSize = 14f
            setLineSpacing(0f, 1.3f)
        }
        
        scrollView.addView(textView)
        
        val builder = AlertDialog.Builder(this)
            .setTitle("预览：${draft.displayName}")
            .setView(scrollView)
            .setNeutralButton("复制全文") { _, _ ->
                copyToClipboard(content)
            }
        
        if (fromEditor) {
            // 编辑器模式：添加"加载此草稿"按钮
            builder.setPositiveButton("加载此草稿") { _, _ ->
                returnDraftToEditor(draft)
            }
            builder.setNegativeButton(R.string.cancel, null)
        } else {
            // 主页模式：只有确认按钮
            builder.setPositiveButton(R.string.confirm, null)
        }
        
        builder.show()
    }
    
    /**
     * 显示长按菜单
     */
    private fun showLongClickMenu(draft: DraftItem) {
        val items = arrayOf("导出草稿", "复制全文", "删除草稿")
        
        AlertDialog.Builder(this)
            .setTitle(draft.displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exportDraft(draft)
                    1 -> copyToClipboard(DraftManager.readDraft(this, draft.folderName, draft.fileName))
                    2 -> confirmDelete(draft)
                }
            }
            .show()
    }
    
    /**
     * 复制文本到剪贴板
     */
    private fun copyToClipboard(content: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("draft", content))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 导出草稿
     */
    private fun exportDraft(draft: DraftItem) {
        draftToExport = draft
        exportFileLauncher.launch(draft.fileName)
    }
    
    /**
     * 导出到指定 URI
     */
    private fun exportToUri(uri: android.net.Uri) {
        draftToExport?.let { draft ->
            try {
                val content = DraftManager.readDraft(this, draft.folderName, draft.fileName)
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
                Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        draftToExport = null
    }

    private fun returnDraftToEditor(draft: DraftItem) {
        val content = DraftManager.readDraft(this, draft.folderName, draft.fileName)
        val resultIntent = Intent()
        resultIntent.putExtra(EXTRA_DRAFT_CONTENT, content)
        resultIntent.putExtra(EXTRA_DRAFT_FILE_NAME, draft.fileName)
        resultIntent.putExtra(EXTRA_DRAFT_FOLDER_NAME, draft.folderName)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun confirmDelete(draft: DraftItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_draft_confirm))
            .setPositiveButton(R.string.confirm) { _, _ ->
                val success = DraftManager.deleteDraft(this, draft.folderName, draft.fileName)
                if (success) {
                    Toast.makeText(this, R.string.draft_deleted, Toast.LENGTH_SHORT).show()
                    loadDrafts()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun confirmDeleteFolder(draft: DraftItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage("确定要删除此文件夹及其所有内容吗？")
            .setPositiveButton(R.string.confirm) { _, _ ->
                val success = DraftManager.deleteDraftFolder(this, draft.folderName)
                if (success) {
                    Toast.makeText(this, R.string.draft_deleted, Toast.LENGTH_SHORT).show()
                    if (currentFolder == draft.folderName) {
                        currentFolder = ""
                        updateToolbarTitle()
                        invalidateOptionsMenu()
                    }
                    loadDrafts()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_FROM_EDITOR = "extra_from_editor"
        const val EXTRA_DRAFT_CONTENT = "extra_draft_content"
        const val EXTRA_DRAFT_FILE_NAME = "extra_draft_file_name"
        const val EXTRA_DRAFT_FOLDER_NAME = "extra_draft_folder_name"
    }
    
    data class DraftItem(
        val folderName: String,
        val fileName: String,
        val displayName: String,
        val formattedDate: String,
        val isFolder: Boolean
    )

    inner class DraftAdapter(
        private val onItemClick: (DraftItem) -> Unit,
        private val onItemLongClick: (DraftItem) -> Unit,
        private val onDeleteClick: (DraftItem) -> Unit
    ) : RecyclerView.Adapter<DraftAdapter.DraftViewHolder>() {

        private var items: List<DraftItem> = emptyList()

        fun submitList(newItems: List<DraftItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DraftViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_draft, parent, false)
            return DraftViewHolder(view)
        }

        override fun onBindViewHolder(holder: DraftViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class DraftViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvDraftName)
            private val tvDate: TextView = itemView.findViewById(R.id.tvDraftDate)
            private val btnDelete: ImageView = itemView.findViewById(R.id.btnDelete)

            fun bind(draft: DraftItem) {
                tvName.text = draft.displayName
                tvDate.text = if (draft.isFolder) "文件夹" else draft.formattedDate

                itemView.setOnClickListener {
                    onItemClick(draft)
                }
                
                itemView.setOnLongClickListener {
                    if (!draft.isFolder) {
                        onItemLongClick(draft)
                    }
                    true
                }

                btnDelete.setOnClickListener {
                    onDeleteClick(draft)
                }
            }
        }
    }
}
