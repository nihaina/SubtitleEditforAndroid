package com.subtitleedit

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.subtitleedit.chat.ChatActivity
import com.subtitleedit.chat.ChatBackendConfig
import com.subtitleedit.chat.ChatLaunchConfiguration
import com.subtitleedit.chat.ChatReasoningLevel
import com.subtitleedit.databinding.ActivityAiSettingsBinding
import com.subtitleedit.util.AiKeyAccessSession
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.AiModelClient
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager

class AiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiSettingsBinding
    private lateinit var settingsManager: SettingsManager
    private var selectedProvider: String = AiProviderConfig.SILICONFLOW
    private var suppressTextSave = false
    private var isApiKeyVisible = false
    private var authenticationInProgress = false
    private var pendingSensitiveAction: (() -> Unit)? = null

    private val biometricPrompt by lazy {
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    authenticationInProgress = false
                    AiKeyAccessSession.authorize()
                    pendingSensitiveAction.also { pendingSensitiveAction = null }?.invoke()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    authenticationInProgress = false
                    pendingSensitiveAction = null
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        showToast("${getString(R.string.ai_api_key_auth_error)}：$errString")
                    }
                }
            }
        )
    }

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

        setupApiKeyActions()
        setupProviderSpinner()
        setupReasoningSettings()
        setupModelListAction()
        binding.btnOpenAiChat.setOnClickListener { openAiChat() }
        loadSettings()
        setupSave()
    }

    override fun onStop() {
        setApiKeyVisible(false)
        AiKeyAccessSession.reset()
        super.onStop()
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
        binding.etTargetLanguage.setText(settingsManager.getAiTargetLanguage())
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
        setApiKeyVisible(false)
        binding.tilApiBaseUrl.visibility = if (config.customEndpoint) View.VISIBLE else View.GONE
        binding.btnFetchModels.visibility = if (config.customEndpoint) View.VISIBLE else View.GONE
        binding.etApiBaseUrl.setText(if (config.customEndpoint) settingsManager.getAiBaseUrl(provider) else "")
        binding.etContextWindowTokens.setText(
            settingsManager.getAiContextWindowTokens(provider).toString()
        )
        binding.spinnerReasoningLevel.setSelection(
            AiProviderConfig.ReasoningLevel.entries.indexOf(
                settingsManager.getAiReasoningLevel(provider)
            )
        )
        binding.etCustomPrompt.setText(settingsManager.getAiCustomPrompt())
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

    private fun setupApiKeyActions() {
        binding.tilApiKey.setEndIconOnClickListener {
            if (isApiKeyVisible) {
                setApiKeyVisible(false)
            } else {
                requestSensitiveAccess { setApiKeyVisible(true) }
            }
        }
        binding.btnCopyApiKey.setOnClickListener {
            if (binding.etApiKey.text.isNullOrEmpty()) {
                showToast(getString(R.string.ai_api_key_empty))
                return@setOnClickListener
            }
            requestSensitiveAccess(::copyApiKey)
        }
    }

    private fun setupReasoningSettings() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            AiProviderConfig.ReasoningLevel.entries.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerReasoningLevel.adapter = adapter
        binding.spinnerReasoningLevel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressTextSave) {
                    settingsManager.setAiReasoningLevel(
                        AiProviderConfig.ReasoningLevel.entries[position]
                    )
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupModelListAction() {
        binding.btnFetchModels.setOnClickListener {
            val baseUrl = binding.etApiBaseUrl.text?.toString()?.trim().orEmpty()
            if (baseUrl.isBlank()) {
                showToast("请先填写 API 请求地址")
                return@setOnClickListener
            }
            val apiKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
            binding.btnFetchModels.isEnabled = false
            lifecycleScope.launch {
                try {
                    val models = AiModelClient.fetchModels(baseUrl, apiKey)
                    if (models.isEmpty()) {
                        showToast("模型列表为空")
                    } else {
                        showModelChooser(models)
                    }
                } catch (error: Exception) {
                    showToast(error.message ?: "获取模型列表失败")
                } finally {
                    binding.btnFetchModels.isEnabled = true
                }
            }
        }
    }

    private fun showModelChooser(models: List<String>) {
        val current = binding.etModel.text?.toString().orEmpty()
        val checked = models.indexOf(current).takeIf { it >= 0 } ?: -1
        var selected = checked
        AlertDialog.Builder(this)
            .setTitle("选择模型（${models.size}）")
            .setSingleChoiceItems(models.toTypedArray(), checked) { _, which -> selected = which }
            .setPositiveButton("使用模型") { _, _ ->
                if (selected >= 0) binding.etModel.setText(models[selected])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openAiChat() {
        saveCurrentProviderFields()
        val config = AiProviderConfig.getProvider(selectedProvider)
        val apiKey = settingsManager.getAiApiKey(selectedProvider)
        if (apiKey.isBlank()) {
            showToast(getString(R.string.ai_api_key_empty))
            return
        }
        val baseUrl = settingsManager.getAiBaseUrl(selectedProvider)
        if (baseUrl.isBlank()) {
            showToast("请先填写 API 请求地址")
            return
        }
        val model = settingsManager.getAiModel(selectedProvider)
        if (model.isBlank()) {
            showToast("请先填写模型名称")
            return
        }
        startActivity(
            ChatActivity.createIntent(
                this,
                ChatLaunchConfiguration(
                    providerName = config.displayName,
                    backendConfig = ChatBackendConfig(
                        providerId = selectedProvider,
                        apiKey = apiKey,
                        model = model,
                        baseUrl = baseUrl,
                        contextWindowTokens = settingsManager.getAiContextWindowTokens(selectedProvider),
                        reasoningLevel = ChatReasoningLevel.valueOf(
                            settingsManager.getAiReasoningLevel(selectedProvider).name
                        ),
                        modelSupportsReasoning = AiProviderConfig
                            .modelCapabilities(selectedProvider, model)
                            .reasoning
                    )
                )
            )
        )
    }

    private fun setApiKeyVisible(visible: Boolean) {
        isApiKeyVisible = visible
        val selection = binding.etApiKey.selectionStart.coerceAtLeast(0)
        binding.etApiKey.transformationMethod = if (visible) {
            null
        } else {
            PasswordTransformationMethod.getInstance()
        }
        binding.tilApiKey.setEndIconDrawable(
            if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        )
        binding.tilApiKey.setEndIconContentDescription(
            if (visible) R.string.ai_api_key_hide else R.string.ai_api_key_show
        )
        binding.etApiKey.setSelection(selection.coerceAtMost(binding.etApiKey.length()))
    }

    private fun requestSensitiveAccess(action: () -> Unit) {
        if (AiKeyAccessSession.isAuthorized) {
            action()
            return
        }
        if (authenticationInProgress) return

        authenticationInProgress = true
        pendingSensitiveAction = action
        runCatching {
            biometricPrompt.authenticate(createPromptInfo())
        }.onFailure { error ->
            authenticationInProgress = false
            pendingSensitiveAction = null
            showToast(
                error.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.ai_api_key_auth_error)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun createPromptInfo(): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.ai_api_key_auth_title))
            .setSubtitle(getString(R.string.ai_api_key_auth_subtitle))
            .setConfirmationRequired(false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            builder.setDeviceCredentialAllowed(true)
        }
        return builder.build()
    }

    private fun copyApiKey() {
        val apiKey = binding.etApiKey.text?.toString().orEmpty()
        if (apiKey.isEmpty()) {
            showToast(getString(R.string.ai_api_key_empty))
            return
        }
        val clip = ClipData.newPlainText("API Key", apiKey).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        showToast(getString(R.string.ai_api_key_copied))
    }

    private fun showToast(message: String) {
        OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        binding.etContextWindowTokens.text?.toString()?.toIntOrNull()?.let {
            settingsManager.setAiContextWindowTokens(it, selectedProvider)
        }
        AiProviderConfig.ReasoningLevel.entries
            .getOrNull(binding.spinnerReasoningLevel.selectedItemPosition)
            ?.let { settingsManager.setAiReasoningLevel(it, selectedProvider) }
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
        binding.etTargetLanguage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                settingsManager.setAiTargetLanguage(s.toString().trim())
            }
        })
        binding.etCustomPrompt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTextSave) settingsManager.setAiCustomPrompt(s.toString())
            }
        })
        binding.etContextWindowTokens.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.toIntOrNull()?.let(settingsManager::setAiContextWindowTokens)
            }
        })
        binding.etContextWindowTokens.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                binding.etContextWindowTokens.setText(
                    settingsManager.getAiContextWindowTokens().toString()
                )
            }
        }
    }
}
