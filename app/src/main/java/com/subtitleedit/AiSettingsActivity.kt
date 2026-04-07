package com.subtitleedit

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivityAiSettingsBinding
import com.subtitleedit.util.SettingsManager

class AiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiSettingsBinding
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AI 翻译设置"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        loadSettings()
        setupSave()
    }

    private fun loadSettings() {
        binding.etApiKey.setText(settingsManager.getAiApiKey())
        binding.etModel.setText(settingsManager.getAiModel())
        binding.etSourceLanguage.setText(settingsManager.getAiSourceLanguage())
        binding.etTargetLanguage.setText(settingsManager.getAiTargetLanguage())
    }

    private fun setupSave() {
        binding.etApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                settingsManager.setAiApiKey(s.toString().trim())
            }
        })
        binding.etModel.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                settingsManager.setAiModel(s.toString().trim())
            }
        })
        binding.etSourceLanguage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                settingsManager.setAiSourceLanguage(s.toString().trim())
            }
        })
        binding.etTargetLanguage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                settingsManager.setAiTargetLanguage(s.toString().trim())
            }
        })
    }
}
