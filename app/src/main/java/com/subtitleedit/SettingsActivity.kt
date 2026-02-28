package com.subtitleedit

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivitySettingsBinding
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager

/**
 * 设置界面
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setupToolbar()
        setupEncodingSpinner()
        loadSettings()
        setupSaveButton()
        setupGithubLink()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupEncodingSpinner() {
        val encodings = FileUtils.SUPPORTED_ENCODINGS.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, encodings)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerEncoding.adapter = adapter
    }

    private fun loadSettings() {
        // 加载默认编码
        val currentEncoding = settingsManager.getDefaultEncoding()
        val encodingIndex = FileUtils.SUPPORTED_ENCODINGS.indexOfFirst { it.charset == currentEncoding }
        if (encodingIndex >= 0) {
            binding.spinnerEncoding.setSelection(encodingIndex)
        }

        // 加载 AI 设置
        binding.etApiKey.setText(settingsManager.getAiApiKey())
        binding.etModel.setText(settingsManager.getAiModel())
        binding.etSourceLanguage.setText(settingsManager.getAiSourceLanguage())
        binding.etTargetLanguage.setText(settingsManager.getAiTargetLanguage())
    }

    private fun setupSaveButton() {
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }
    
    private fun setupGithubLink() {
        binding.tvGithub.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/nihaina/SubtitleEditforAndroid"))
            startActivity(intent)
        }
    }

    private fun saveSettings() {
        // 保存默认编码
        val selectedEncoding = FileUtils.SUPPORTED_ENCODINGS[binding.spinnerEncoding.selectedItemPosition]
        settingsManager.setDefaultEncoding(selectedEncoding.charset)

        // 保存 AI 设置
        val apiKey = binding.etApiKey.text.toString().trim()
        val model = binding.etModel.text.toString().trim()
        val sourceLanguage = binding.etSourceLanguage.text.toString().trim()
        val targetLanguage = binding.etTargetLanguage.text.toString().trim()

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "请输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        if (model.isEmpty()) {
            Toast.makeText(this, "请输入模型名称", Toast.LENGTH_SHORT).show()
            return
        }

        if (targetLanguage.isEmpty()) {
            Toast.makeText(this, "请输入翻译目标语言", Toast.LENGTH_SHORT).show()
            return
        }

        settingsManager.setAiApiKey(apiKey)
        settingsManager.setAiModel(model)
        settingsManager.setAiSourceLanguage(sourceLanguage)
        settingsManager.setAiTargetLanguage(targetLanguage)

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }
}
