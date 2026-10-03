package com.subtitleedit

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.lifecycle.viewModelScope
import com.subtitleedit.chat.ChatBackendConfig
import com.subtitleedit.chat.ChatLaunchConfiguration
import com.subtitleedit.chat.ChatReasoningLevel
import com.subtitleedit.feature.ui.AiModelChooserUi
import com.subtitleedit.feature.ui.AiModelTarget
import com.subtitleedit.feature.ui.AiSettingsScreenState
import com.subtitleedit.repository.AiTranslationService
import com.subtitleedit.util.AiKeyAccessSession
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.launch

internal sealed interface AiSettingsEvent {
    /** Ask the Activity to show the biometric / device credential prompt. */
    data object Authenticate : AiSettingsEvent
    data class OpenChat(val configuration: ChatLaunchConfiguration) : AiSettingsEvent
}

internal class AiSettingsViewModel(
    application: Application
) : AppViewModel<AiSettingsScreenState, AiSettingsEvent>(
    application,
    AiSettingsScreenState.from(SettingsManager.getInstance(application))
) {
    private enum class SensitiveAction { REVEAL_KEY, COPY_KEY }

    private val settingsManager = SettingsManager.getInstance(application)
    private val aiTranslationService: AiTranslationService get() = dependencies.aiTranslationService
    private var authenticationInProgress = false
    private var pendingSensitiveAction: SensitiveAction? = null

    /** Applies [transform] and persists the fields it changed. */
    fun updateScreenState(transform: (AiSettingsScreenState) -> AiSettingsScreenState) {
        val current = currentState
        val next = transform(current)
        persistChangedFields(current, next)
        setState { next }
    }

    /** Activity.onStop: hide the key and drop the authorization window. */
    fun onStop() {
        updateScreenState { it.copy(apiKeyVisible = false) }
        AiKeyAccessSession.reset()
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

    fun selectProvider(provider: String) {
        val current = currentState
        if (provider == current.provider) return
        settingsManager.setAiApiKey(current.provider, current.apiKey.trim())
        settingsManager.setAiBaseUrl(current.provider, current.baseUrl.trim())
        settingsManager.setAiProvider(provider)
        setState {
            current.copy(
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
    }

    fun selectTranslationProvider(provider: String) {
        val current = currentState
        if (provider == current.translationProvider) return
        persistDedicatedFields(current)
        settingsManager.setAiTranslationProvider(provider)
        setState {
            current.copy(
                translationProvider = provider,
                translationModel = settingsManager.getAiModel(provider),
                translationReasoning = settingsManager.getAiReasoningLevel(provider),
                targetLanguage = settingsManager.getAiTargetLanguage(),
                translationPrompt = settingsManager.getAiCustomPrompt(),
                modelChooser = null
            )
        }
    }

    fun selectPunctuationProvider(provider: String) {
        val current = currentState
        if (provider == current.punctuationProvider) return
        persistDedicatedFields(current)
        settingsManager.setAiPunctuationProvider(provider)
        setState {
            current.copy(
                punctuationProvider = provider,
                punctuationModel = settingsManager.getAiPunctuationModel(provider),
                punctuationReasoning = settingsManager.getAiPunctuationReasoningLevel(provider),
                punctuationPrompt = settingsManager.getAiPunctuationCustomPrompt(),
                modelChooser = null
            )
        }
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

    fun fetchModels(target: AiModelTarget) {
        val state = currentState
        val provider = when (target) {
            AiModelTarget.TRANSLATION -> state.translationProvider
            AiModelTarget.PUNCTUATION -> state.punctuationProvider
        }
        if (!AiProviderConfig.getProvider(provider).customEndpoint) return

        val baseUrl = settingsManager.getAiBaseUrl(provider)
        if (baseUrl.isBlank()) {
            toast(R.string.ai_base_url_required)
            return
        }

        updateScreenState {
            when (target) {
                AiModelTarget.TRANSLATION -> it.copy(fetchingTranslationModels = true)
                AiModelTarget.PUNCTUATION -> it.copy(fetchingPunctuationModels = true)
            }
        }
        viewModelScope.launch {
            try {
                val models = aiTranslationService.fetchModels(baseUrl, settingsManager.getAiApiKey(provider))
                if (models.isEmpty()) {
                    toast(R.string.ai_models_empty)
                } else {
                    updateScreenState {
                        it.copy(modelChooser = AiModelChooserUi(provider, target, models))
                    }
                }
            } catch (error: Exception) {
                toast(error.message ?: string(R.string.ai_models_fetch_failed))
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

    fun chooseModel(model: String) {
        val chooser = currentState.modelChooser ?: return
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

    fun dismissModelChooser() = updateScreenState { it.copy(modelChooser = null) }

    fun revealApiKey() = requestSensitiveAccess(SensitiveAction.REVEAL_KEY)

    fun requestCopyApiKey() {
        if (currentState.apiKey.isEmpty()) {
            toast(R.string.ai_api_key_empty)
            return
        }
        requestSensitiveAccess(SensitiveAction.COPY_KEY)
    }

    private fun copyApiKey() {
        val apiKey = currentState.apiKey
        if (apiKey.isEmpty()) {
            toast(R.string.ai_api_key_empty)
            return
        }
        val clip = ClipData.newPlainText("API Key", apiKey).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        toast(R.string.ai_api_key_copied)
    }

    private fun runSensitiveAction(action: SensitiveAction) = when (action) {
        SensitiveAction.REVEAL_KEY -> updateScreenState { it.copy(apiKeyVisible = true) }
        SensitiveAction.COPY_KEY -> copyApiKey()
    }

    private fun requestSensitiveAccess(action: SensitiveAction) {
        if (AiKeyAccessSession.isAuthorized) {
            runSensitiveAction(action)
            return
        }
        if (authenticationInProgress) return

        authenticationInProgress = true
        pendingSensitiveAction = action
        sendEvent(AiSettingsEvent.Authenticate)
    }

    fun onAuthenticationSucceeded() {
        authenticationInProgress = false
        AiKeyAccessSession.authorize()
        pendingSensitiveAction.also { pendingSensitiveAction = null }?.let(::runSensitiveAction)
    }

    /** [message] is shown when non-null (user cancellations pass null). */
    fun onAuthenticationError(message: CharSequence?) {
        authenticationInProgress = false
        pendingSensitiveAction = null
        if (message != null) {
            toast(string(R.string.ai_settings_error_with_detail, string(R.string.ai_api_key_auth_error), message))
        }
    }

    /** The prompt could not be shown at all. */
    fun onAuthenticationUnavailable(error: Throwable) {
        authenticationInProgress = false
        pendingSensitiveAction = null
        toast(error.message?.takeIf { it.isNotBlank() } ?: string(R.string.ai_api_key_auth_error))
    }

    fun openAiChat() {
        val state = currentState
        settingsManager.setAiApiKey(state.provider, state.apiKey.trim())
        settingsManager.setAiBaseUrl(state.provider, state.baseUrl.trim())
        persistDedicatedFields(state)

        val chatProvider = settingsManager.getAiTranslationProvider()
        val config = AiProviderConfig.getProvider(chatProvider)
        val apiKey = settingsManager.getAiApiKey(chatProvider)
        if (apiKey.isBlank()) {
            toast(R.string.ai_api_key_empty)
            return
        }
        val baseUrl = settingsManager.getAiBaseUrl(chatProvider)
        if (baseUrl.isBlank()) {
            toast(R.string.ai_base_url_required)
            return
        }
        val model = settingsManager.getAiModel(chatProvider)
        if (model.isBlank()) {
            toast(R.string.ai_model_name_required)
            return
        }
        sendEvent(
            AiSettingsEvent.OpenChat(
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
}
