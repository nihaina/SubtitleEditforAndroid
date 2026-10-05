package com.subtitleedit

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.subtitleedit.chat.ChatActivity
import com.subtitleedit.feature.ui.AiSettingsScreen

class AiSettingsActivity : AppComposeActivity() {
    private val viewModel: AiSettingsViewModel by viewModels()

    // Created in onCreate (not lazily) so a prompt that survives recreation reports to this instance.
    private lateinit var biometricPrompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    viewModel.onAuthenticationSucceeded()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    val userCancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    viewModel.onAuthenticationError(if (userCancelled) null else errString)
                }
            }
        )
        setContent {
            val state by viewModel.state.collectAsState()
            com.subtitleedit.ui.theme.SubtitleEditComposeTheme {
                AiSettingsScreen(
                    state = state,
                    onStateChange = viewModel::updateScreenState,
                    onSelectProvider = viewModel::selectProvider,
                    onSelectTranslationProvider = viewModel::selectTranslationProvider,
                    onSelectPunctuationProvider = viewModel::selectPunctuationProvider,
                    onRevealApiKey = viewModel::revealApiKey,
                    onCopyApiKey = viewModel::requestCopyApiKey,
                    onLoadLocalModel = viewModel::loadLocalModel,
                    onUnloadLocalModel = viewModel::unloadLocalModel,
                    onFetchModels = viewModel::fetchModels,
                    onChooseModel = viewModel::chooseModel,
                    onDismissModelChooser = viewModel::dismissModelChooser,
                    onOpenChat = viewModel::openAiChat,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
        collectEvents(viewModel.events) { event ->
            when (event) {
                AiSettingsEvent.Authenticate -> runCatching {
                    biometricPrompt.authenticate(createPromptInfo())
                }.onFailure(viewModel::onAuthenticationUnavailable)
                is AiSettingsEvent.OpenChat ->
                    startActivity(ChatActivity.createIntent(this, event.configuration))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onStop() {
        viewModel.onStop()
        super.onStop()
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
}
