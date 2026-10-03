package com.subtitleedit

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.chat.ChatActivity
import com.subtitleedit.chat.ChatBackendConfig
import com.subtitleedit.chat.ChatLaunchConfiguration
import com.subtitleedit.chat.ChatReasoningLevel
import com.subtitleedit.feature.ui.AiModelChooserUi
import com.subtitleedit.feature.ui.AiModelTarget
import com.subtitleedit.feature.ui.AiSettingsScreen
import com.subtitleedit.feature.ui.AiSettingsScreenState
import com.subtitleedit.repository.AiTranslationService
import com.subtitleedit.util.AiKeyAccessSession
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.launch

class AiSettingsActivity : AppComposeActivity() {

    private lateinit var settingsManager: SettingsManager
    private val aiTranslationService: AiTranslationService
        get() = (application as SubtitleEditApplication).dependencies.aiTranslationService
    private var screenState by mutableStateOf<AiSettingsScreenState?>(null)
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
        settingsManager = SettingsManager.getInstance(this)
        screenState = loadScreenState()
        setContent {
            com.subtitleedit.ui.theme.SubtitleEditComposeTheme {
                screenState?.let { state ->
                    AiSettingsScreen(
                        state = state,
                        onStateChange = ::updateScreenState,
                        onSelectProvider = ::selectProvider,
                        onSelectTranslationProvider = ::selectTranslationProvider,
                        onSelectPunctuationProvider = ::selectPunctuationProvider,
                        onRevealApiKey = { requestSensitiveAccess { updateScreenState { it.copy(apiKeyVisible = true) } } },
                        onCopyApiKey = ::requestCopyApiKey,
                        onFetchModels = ::fetchModels,
                        onChooseModel = ::chooseModel,
                        onDismissModelChooser = { updateScreenState { it.copy(modelChooser = null) } },
                        onOpenChat = ::openAiChat,
                        onNavigateBack = { onBackPressedDispatcher.onBackPressed() }
                    )
                }
            }
        }
    }

    override fun onStop() {
        updateScreenState { it.copy(apiKeyVisible = false) }
        AiKeyAccessSession.reset()
        super.onStop()
    }

    private fun updateScreenState(transform: (AiSettingsScreenState) -> AiSettingsScreenState) {
        val current = screenState ?: return
        val next = transform(current)
        persistChangedFields(current, next)
        screenState = next
    }

    private fun loadScreenState(): AiSettingsScreenState {
        return AiSettingsScreenState.from(settingsManager)
    }

    private fun persistChangedFields(current: AiSettingsScreenState, next: AiSettingsScreenState) {
        if (current.provider == next.provider) {
            if (current.apiKey != next.apiKey) {
                settingsManager.setAiApiKey(current.provider, next.apiKey.trim())
            }
            if (current.baseUrl != next.baseUrl) {
                settingsManager.setAiBaseUrl(current.provider, next.baseUrl.trim())
            }
        }

        if (current.translationProvider == next.translationProvider) {
            val provider = current.translationProvider
            if (current.translationModel != next.translationModel) {
                settingsManager.setAiModel(provider, next.translationModel.trim())
            }
            if (current.targetLanguage != next.targetLanguage) {
                settingsManager.setAiTargetLanguage(next.targetLanguage.trim())
            }
            if (current.translationReasoning != next.translationReasoning) {
                settingsManager.setAiReasoningLevel(next.translationReasoning, provider)
            }
            if (current.translationPrompt != next.translationPrompt) {
                settingsManager.setAiCustomPrompt(next.translationPrompt)
            }
        }

        if (current.punctuationProvider == next.punctuationProvider) {
            val provider = current.punctuationProvider
            if (current.punctuationModel != next.punctuationModel) {
                settingsManager.setAiPunctuationModel(provider, next.punctuationModel.trim())
            }
            if (current.punctuationReasoning != next.punctuationReasoning) {
                settingsManager.setAiPunctuationReasoningLevel(next.punctuationReasoning, provider)
            }
            if (current.punctuationPrompt != next.punctuationPrompt) {
                settingsManager.setAiPunctuationCustomPrompt(next.punctuationPrompt)
            }
        }
    }

    private fun selectProvider(provider: String) {
        val current = screenState ?: return
        if (provider == current.provider) return
        settingsManager.setAiApiKey(current.provider, current.apiKey.trim())
        settingsManager.setAiBaseUrl(current.provider, current.baseUrl.trim())
        settingsManager.setAiProvider(provider)
        screenState = current.copy(
            provider = provider,
            apiKey = settingsManager.getAiApiKey(provider),
            baseUrl = if (AiProviderConfig.getProvider(provider).customEndpoint) {
                settingsManager.getAiBaseUrl(provider)
            } else {
                ""
            },
            apiKeyVisible = false
        )
    }

    private fun selectTranslationProvider(provider: String) {
        val current = screenState ?: return
        if (provider == current.translationProvider) return
        persistDedicatedFields(current)
        settingsManager.setAiTranslationProvider(provider)
        screenState = current.copy(
            translationProvider = provider,
            translationModel = settingsManager.getAiModel(provider),
            translationReasoning = settingsManager.getAiReasoningLevel(provider),
            targetLanguage = settingsManager.getAiTargetLanguage(),
            translationPrompt = settingsManager.getAiCustomPrompt(),
            modelChooser = null
        )
    }

    private fun selectPunctuationProvider(provider: String) {
        val current = screenState ?: return
        if (provider == current.punctuationProvider) return
        persistDedicatedFields(current)
        settingsManager.setAiPunctuationProvider(provider)
        screenState = current.copy(
            punctuationProvider = provider,
            punctuationModel = settingsManager.getAiPunctuationModel(provider),
            punctuationReasoning = settingsManager.getAiPunctuationReasoningLevel(provider),
            punctuationPrompt = settingsManager.getAiPunctuationCustomPrompt(),
            modelChooser = null
        )
    }

    private fun persistDedicatedFields(state: AiSettingsScreenState) {
        val translationProvider = state.translationProvider
        if (AiProviderConfig.getProvider(translationProvider).models.isEmpty()) {
            settingsManager.setAiModel(translationProvider, state.translationModel.trim())
        }
        settingsManager.setAiTargetLanguage(state.targetLanguage.trim())
        settingsManager.setAiReasoningLevel(state.translationReasoning, translationProvider)
        settingsManager.setAiCustomPrompt(state.translationPrompt)

        val punctuationProvider = state.punctuationProvider
        if (AiProviderConfig.getProvider(punctuationProvider).models.isEmpty()) {
            settingsManager.setAiPunctuationModel(punctuationProvider, state.punctuationModel.trim())
        }
        settingsManager.setAiPunctuationReasoningLevel(state.punctuationReasoning, punctuationProvider)
        settingsManager.setAiPunctuationCustomPrompt(state.punctuationPrompt)
    }

    private fun fetchModels(target: AiModelTarget) {
        val state = screenState ?: return
        val provider = when (target) {
            AiModelTarget.TRANSLATION -> state.translationProvider
            AiModelTarget.PUNCTUATION -> state.punctuationProvider
        }
        if (!AiProviderConfig.getProvider(provider).customEndpoint) return

        val baseUrl = settingsManager.getAiBaseUrl(provider)
        if (baseUrl.isBlank()) {
            showToast(getString(R.string.ai_base_url_required))
            return
        }

        updateScreenState {
            when (target) {
                AiModelTarget.TRANSLATION -> it.copy(fetchingTranslationModels = true)
                AiModelTarget.PUNCTUATION -> it.copy(fetchingPunctuationModels = true)
            }
        }
        lifecycleScope.launch {
            try {
                val models = aiTranslationService.fetchModels(baseUrl, settingsManager.getAiApiKey(provider))
                if (models.isEmpty()) {
                    showToast(getString(R.string.ai_models_empty))
                } else {
                    updateScreenState {
                        it.copy(modelChooser = AiModelChooserUi(provider, target, models))
                    }
                }
            } catch (error: Exception) {
                showToast(error.message ?: getString(R.string.ai_models_fetch_failed))
            } finally {
                updateScreenState {
                    when (target) {
                        AiModelTarget.TRANSLATION -> it.copy(fetchingTranslationModels = false)
                        AiModelTarget.PUNCTUATION -> it.copy(fetchingPunctuationModels = false)
                    }
                }
            }
        }
    }

    private fun chooseModel(model: String) {
        val chooser = screenState?.modelChooser ?: return
        when (chooser.target) {
            AiModelTarget.TRANSLATION -> {
                settingsManager.setAiModel(chooser.provider, model)
                updateScreenState {
                    it.copy(
                        modelChooser = null,
                        translationModel = if (it.translationProvider == chooser.provider) model else it.translationModel
                    )
                }
            }
            AiModelTarget.PUNCTUATION -> {
                settingsManager.setAiPunctuationModel(chooser.provider, model)
                updateScreenState {
                    it.copy(
                        modelChooser = null,
                        punctuationModel = if (it.punctuationProvider == chooser.provider) model else it.punctuationModel
                    )
                }
            }
        }
    }

    private fun requestCopyApiKey() {
        val apiKey = screenState?.apiKey.orEmpty()
        if (apiKey.isEmpty()) {
            showToast(getString(R.string.ai_api_key_empty))
            return
        }
        requestSensitiveAccess(::copyApiKey)
    }

    private fun copyApiKey() {
        val apiKey = screenState?.apiKey.orEmpty()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            builder.setDeviceCredentialAllowed(true)
        }
        return builder.build()
    }

    private fun openAiChat() {
        val state = screenState ?: return
        settingsManager.setAiApiKey(state.provider, state.apiKey.trim())
        settingsManager.setAiBaseUrl(state.provider, state.baseUrl.trim())
        persistDedicatedFields(state)

        val chatProvider = settingsManager.getAiTranslationProvider()
        val config = AiProviderConfig.getProvider(chatProvider)
        val apiKey = settingsManager.getAiApiKey(chatProvider)
        if (apiKey.isBlank()) {
            showToast(getString(R.string.ai_api_key_empty))
            return
        }
        val baseUrl = settingsManager.getAiBaseUrl(chatProvider)
        if (baseUrl.isBlank()) {
            showToast(getString(R.string.ai_base_url_required))
            return
        }
        val model = settingsManager.getAiModel(chatProvider)
        if (model.isBlank()) {
            showToast(getString(R.string.ai_model_name_required))
            return
        }
        startActivity(
            ChatActivity.createIntent(
                this,
                ChatLaunchConfiguration(
                    providerName = config.displayName,
                    backendConfig = ChatBackendConfig(
                        providerId = chatProvider,
                        apiKey = apiKey,
                        model = model,
                        baseUrl = baseUrl,
                        reasoningLevel = ChatReasoningLevel.valueOf(
                            settingsManager.getAiReasoningLevel(chatProvider).name
                        ),
                        modelSupportsReasoning = AiProviderConfig
                            .modelCapabilities(chatProvider, model)
                            .reasoning
                    )
                )
            )
        )
    }

    private fun showToast(message: String) {
        OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
