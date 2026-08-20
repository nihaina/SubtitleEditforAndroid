package com.subtitleedit

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import com.subtitleedit.databinding.ActivityAiSettingsBinding
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.SettingsManager

class AiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiSettingsBinding
    private lateinit var settingsManager: SettingsManager
    private var selectedProvider: String = AiProviderConfig.SILICONFLOW
    private var suppressTextSave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ToolCardShadow.remove(binding.cardAiSettings)

        settingsManager = SettingsManager.getInstance(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AI 翻译设置"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        setupProviderSpinner()
        loadSettings()
        setupSave()
    }

    private fun setupProviderSpinner() {
        val providerNames = AiProviderConfig.providers.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAiProvider.adapter = adapter
        binding.spinnerAiProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = AiProviderConfig.providers[position].id
                if (provider == selectedProvider) return
                saveCurrentProviderFields()
                selectedProvider = provider
                settingsManager.setAiProvider(provider)
                loadProviderFields(provider)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadSettings() {
        selectedProvider = settingsManager.getAiProvider()
        binding.spinnerAiProvider.setSelection(AiProviderConfig.indexOf(selectedProvider))
        loadProviderFields(selectedProvider)
        binding.etSourceLanguage.setText(settingsManager.getAiSourceLanguage())
        binding.etTargetLanguage.setText(settingsManager.getAiTargetLanguage())
        binding.etTranslationPrompt.setText(settingsManager.getAiTranslationPrompt())
    }

    private fun loadProviderFields(provider: String) {
        val config = AiProviderConfig.getProvider(provider)
        suppressTextSave = true
        binding.tvProviderTitle.text = "AI 翻译设置（${config.displayName}）"
        binding.tilApiKey.hint = "${config.displayName} API Key"
        binding.tvProviderWebsite.text = HtmlCompat.fromHtml(
            "官网：<a href=\"${config.websiteUrl}\">${config.websiteUrl}</a>",
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        binding.tvProviderWebsite.movementMethod = LinkMovementMethod.getInstance()
        binding.etApiKey.setText(settingsManager.getAiApiKey(provider))
        binding.tilApiBaseUrl.visibility = if (config.customEndpoint) View.VISIBLE else View.GONE
        binding.etApiBaseUrl.setText(if (config.customEndpoint) settingsManager.getAiBaseUrl(provider) else "")
        val savedModel = settingsManager.getAiModel(provider)
        if (config.models.isNotEmpty()) {
            binding.tilModel.visibility = View.GONE
            binding.tvModelLabel.visibility = View.VISIBLE
            binding.spinnerModel.visibility = View.VISIBLE
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, config.models)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerModel.adapter = adapter
            val index = config.models.indexOf(savedModel).takeIf { it >= 0 } ?: 0
            binding.spinnerModel.setSelection(index)
        } else {
            binding.tilModel.visibility = View.VISIBLE
            binding.tvModelLabel.visibility = View.GONE
            binding.spinnerModel.visibility = View.GONE
            binding.tilModel.hint = "模型名称（默认 ${config.defaultModel}）"
            binding.etModel.setText(savedModel)
        }
        suppressTextSave = false
    }

    private fun saveCurrentProviderFields() {
        settingsManager.setAiApiKey(selectedProvider, binding.etApiKey.text?.toString()?.trim().orEmpty())
        val config = AiProviderConfig.getProvider(selectedProvider)
        if (config.models.isNotEmpty()) {
            val model = binding.spinnerModel.selectedItem?.toString().orEmpty()
            settingsManager.setAiModel(selectedProvider, model)
        } else {
            settingsManager.setAiModel(selectedProvider, binding.etModel.text?.toString()?.trim().orEmpty())
        }
        settingsManager.setAiBaseUrl(selectedProvider, binding.etApiBaseUrl.text?.toString()?.trim().orEmpty())
    }

    private fun setupSave() {
        binding.etApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTextSave) settingsManager.setAiApiKey(selectedProvider, s.toString().trim())
            }
        })
        binding.etModel.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTextSave) settingsManager.setAiModel(selectedProvider, s.toString().trim())
            }
        })
        binding.etApiBaseUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTextSave) settingsManager.setAiBaseUrl(selectedProvider, s.toString().trim())
            }
        })
        binding.spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressTextSave) {
                    val model = parent?.getItemAtPosition(position)?.toString().orEmpty()
                    settingsManager.setAiModel(selectedProvider, model)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
        binding.etTranslationPrompt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                settingsManager.setAiTranslationPrompt(s.toString().trim())
            }
        })
    }
}
