package com.subtitleedit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.adapter.FileListAdapter
import com.subtitleedit.databinding.ActivityMainBinding
import com.subtitleedit.util.FileUtils
import java.io.File

/**
 * 主界面 - 文件浏览器
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileAdapter: FileListAdapter
    
    private var currentDirectory: File? = null
    private val directoryHistory = mutableListOf<File>()
    
    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            loadDirectory(getDefaultDirectory())
        } else {
            showPermissionDeniedDialog()
        }
    }
    
    // 管理外部存储权限请求
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Environment.isExternalStorageManager()) {
            loadDirectory(getDefaultDirectory())
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupRecyclerView()
        setupButtons()
        checkPermissions()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_tools -> {
                    startActivity(Intent(this, ToolsActivity::class.java))
                    true
                }
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    
    private fun setupRecyclerView() {
        fileAdapter = FileListAdapter { file ->
            onFileClicked(file)
        }
        
        binding.rvFileList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
        }
    }
    
    private fun setupButtons() {
        binding.btnUpLevel.setOnClickListener {
            goUpLevel()
        }
        
        binding.btnDrafts.setOnClickListener {
            openDrafts()
        }
    }
    
    private fun openDrafts() {
        val intent = Intent(this, DraftsActivity::class.java)
        intent.putExtra(DraftsActivity.EXTRA_FROM_EDITOR, false)
        startActivity(intent)
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE
            if (Environment.isExternalStorageManager()) {
                loadDirectory(getDefaultDirectory())
            } else {
                requestManageStoragePermission()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10 需要 READ_EXTERNAL_STORAGE
            val readPermission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, readPermission) 
                == PackageManager.PERMISSION_GRANTED) {
                loadDirectory(getDefaultDirectory())
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        readPermission,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        } else {
            // Android 5.x 不需要运行时权限
            loadDirectory(getDefaultDirectory())
        }
    }
    
    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStorageLauncher.launch(intent)
            }
        }
    }
    
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.error)
            .setMessage("需要存储权限才能访问字幕文件。请在设置中授予权限。")
            .setPositiveButton(R.string.confirm) { _, _ ->
                checkPermissions()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun getDefaultDirectory(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
    
    private fun loadDirectory(directory: File) {
        if (!directory.exists() || !directory.canRead()) {
            Toast.makeText(this, "无法访问目录：${directory.name}", Toast.LENGTH_SHORT).show()
            return
        }
        
        currentDirectory = directory
        updatePathDisplay()
        
        val files = mutableListOf<File>()
        
        // 添加父目录（如果不是根目录）
        val parent = directory.parentFile
        if (parent != null && parent.canRead()) {
            // 使用特殊标记表示父目录
            files.add(File(directory.absolutePath + "/.."))
        }
        
        // 添加子目录
        val subDirs = directory.listFiles { file ->
            file.isDirectory && !file.name.startsWith(".")
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        files.addAll(subDirs)
        
        // 添加字幕文件
        val subtitleFiles = directory.listFiles { file ->
            file.isFile && FileUtils.isSubtitleFile(file)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        files.addAll(subtitleFiles)
        
        // 添加音频文件
        val audioFiles = directory.listFiles { file ->
            file.isFile && FileUtils.isAudioFile(file)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        files.addAll(audioFiles)
        
        fileAdapter.submitList(files)
        
        // 更新空状态
        binding.emptyState.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.rvFileList.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
    }
    
    private fun updatePathDisplay() {
        currentDirectory?.let {
            binding.tvCurrentPath.text = it.absolutePath
        }
    }
    
    private fun onFileClicked(file: File) {
        // 处理父目录导航
        if (file.name == "..") {
            goUpLevel()
            return
        }
        
        if (file.isDirectory) {
            // 进入子目录
            directoryHistory.add(currentDirectory!!)
            loadDirectory(file)
        } else if (FileUtils.isSubtitleFile(file)) {
            // 打开字幕文件进行编辑
            openFileForEdit(file)
        } else if (FileUtils.isAudioFile(file)) {
            // 打开音频文件进行编辑（自动查找同名字幕）
            openAudioFileForEdit(file)
        } else {
            Toast.makeText(this, "不支持的文件格式", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 打开音频文件进行编辑
     * 自动查找同文件夹下同名的字幕文件
     */
    private fun openAudioFileForEdit(audioFile: File) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, audioFile.absolutePath)
        intent.putExtra(EditorActivity.EXTRA_IS_AUDIO_FILE, true)
        
        // 查找可能的字幕文件
        val possibleSubtitleFiles = FileUtils.getPossibleSubtitleFiles(audioFile)
        if (possibleSubtitleFiles.isNotEmpty()) {
            // 使用找到的第一个字幕文件（优先级：srt > lrc > ass > ...）
            intent.putExtra(EditorActivity.EXTRA_SUBTITLE_FILE_PATH, possibleSubtitleFiles[0].absolutePath)
        }
        
        startActivity(intent)
    }
    
    private fun goUpLevel() {
        if (directoryHistory.isNotEmpty()) {
            val parent = directoryHistory.removeAt(directoryHistory.size - 1)
            loadDirectory(parent)
        } else {
            currentDirectory?.parentFile?.let { parent ->
                if (parent.exists() && parent.canRead()) {
                    loadDirectory(parent)
                }
            }
        }
    }
    
    private fun openFileForEdit(file: File) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, file.absolutePath)
        startActivity(intent)
    }
    
    override fun onBackPressed() {
        if (directoryHistory.isNotEmpty()) {
            goUpLevel()
        } else {
            super.onBackPressed()
        }
    }
}
