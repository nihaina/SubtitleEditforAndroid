package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivitySubtitleFormatSelectBinding
import com.subtitleedit.util.OverwritingToast

class SubtitleFormatSelectActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySubtitleFormatSelectBinding
    private var selectedUri: Uri? = null
    private var selectedName = ""

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = queryDisplayName(uri) ?: "未知文件"
        if (name.substringAfterLast('.', "").lowercase() !in setOf("srt", "lrc", "txt", "vtt")) {
            OverwritingToast.makeText(this, "请选择 SRT、LRC、TXT 或 VTT 字幕文件", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        selectedUri = uri
        selectedName = name
        binding.tvSelectedFile.text = name
        binding.btnConfirm.isEnabled = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubtitleFormatSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSelectFile.setOnClickListener {
            filePicker.launch(arrayOf("text/*", "application/*"))
        }
        binding.btnConfirm.setOnClickListener {
            val uri = selectedUri ?: return@setOnClickListener
            startActivity(Intent(this, SubtitleFormatEditorActivity::class.java).apply {
                putExtra(SubtitleFormatEditorActivity.EXTRA_URI, uri.toString())
                putExtra(SubtitleFormatEditorActivity.EXTRA_FILE_NAME, selectedName)
            })
        }
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}
