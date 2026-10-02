package com.subtitleedit.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.ui.components.AppCard
import com.subtitleedit.util.SettingsManager

enum class AiModelTarget {
    TRANSLATION,
    PUNCTUATION
}

data class AiModelChooserUi(
    val provider: String,
    val target: AiModelTarget,
    val models: List<String>
)

data class AiSettingsScreenState(
    val provider: String,
    val apiKey: String,
    val baseUrl: String,
    val apiKeyVisible: Boolean,
    val translationProvider: String,
    val translationModel: String,
    val targetLanguage: String,
    val translationReasoning: AiProviderConfig.ReasoningLevel,
    val translationPrompt: String,
    val punctuationProvider: String,
    val punctuationModel: String,
    val punctuationReasoning: AiProviderConfig.ReasoningLevel,
    val punctuationPrompt: String,
    val fetchingTranslationModels: Boolean = false,
    val fetchingPunctuationModels: Boolean = false,
    val modelChooser: AiModelChooserUi? = null
) {
    companion object {
        fun from(settings: SettingsManager): AiSettingsScreenState {
            val provider = settings.getAiProvider()
            val translationProvider = settings.getAiTranslationProvider()
            val punctuationProvider = settings.getAiPunctuationProvider()
            return AiSettingsScreenState(
                provider = provider,
                apiKey = settings.getAiApiKey(provider),
                baseUrl = if (AiProviderConfig.getProvider(provider).customEndpoint) {
                    settings.getAiBaseUrl(provider)
                } else {
                    ""
                },
                apiKeyVisible = false,
                translationProvider = translationProvider,
                translationModel = settings.getAiModel(translationProvider),
                targetLanguage = settings.getAiTargetLanguage(),
                translationReasoning = settings.getAiReasoningLevel(translationProvider),
                translationPrompt = settings.getAiCustomPrompt(),
                punctuationProvider = punctuationProvider,
                punctuationModel = settings.getAiPunctuationModel(punctuationProvider),
                punctuationReasoning = settings.getAiPunctuationReasoningLevel(punctuationProvider),
                punctuationPrompt = settings.getAiPunctuationCustomPrompt()
            )
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    state: AiSettingsScreenState,
    onStateChange: ((AiSettingsScreenState) -> AiSettingsScreenState) -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectTranslationProvider: (String) -> Unit,
    onSelectPunctuationProvider: (String) -> Unit,
    onRevealApiKey: () -> Unit,
    onCopyApiKey: () -> Unit,
    onFetchModels: (AiModelTarget) -> Unit,
    onChooseModel: (String) -> Unit,
    onDismissModelChooser: () -> Unit,
    onOpenChat: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                modifier = Modifier.height(56.dp),
                title = { Text("AI 设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.tools_navigate_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenChat) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ai_chat),
                            contentDescription = stringResource(R.string.activity_ai_settings_open_chat),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProviderCard(
                state = state,
                onSelectProvider = onSelectProvider,
                onApiKeyChange = { value -> onStateChange { it.copy(apiKey = value) } },
                onBaseUrlChange = { value -> onStateChange { it.copy(baseUrl = value) } },
                onRevealApiKey = onRevealApiKey,
                onHideApiKey = { onStateChange { it.copy(apiKeyVisible = false) } },
                onCopyApiKey = onCopyApiKey
            )
            TranslationSettingsCard(
                state = state,
                onSelectProvider = onSelectTranslationProvider,
                onModelChange = { value -> onStateChange { it.copy(translationModel = value) } },
                onTargetLanguageChange = { value -> onStateChange { it.copy(targetLanguage = value) } },
                onReasoningChange = { value -> onStateChange { it.copy(translationReasoning = value) } },
                onPromptChange = { value -> onStateChange { it.copy(translationPrompt = value) } },
                onFetchModels = { onFetchModels(AiModelTarget.TRANSLATION) }
            )
            PunctuationSettingsCard(
                state = state,
                onSelectProvider = onSelectPunctuationProvider,
                onModelChange = { value -> onStateChange { it.copy(punctuationModel = value) } },
                onReasoningChange = { value -> onStateChange { it.copy(punctuationReasoning = value) } },
                onPromptChange = { value -> onStateChange { it.copy(punctuationPrompt = value) } },
                onFetchModels = { onFetchModels(AiModelTarget.PUNCTUATION) }
            )
        }
    }

    state.modelChooser?.let { chooser ->
        AlertDialog(
            onDismissRequest = onDismissModelChooser,
            title = { Text("选择模型（${chooser.models.size}）") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(chooser.models) { model ->
                        TextButton(
                            onClick = { onChooseModel(model) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = model,
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissModelChooser) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun ProviderCard(
    state: AiSettingsScreenState,
    onSelectProvider: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onRevealApiKey: () -> Unit,
    onHideApiKey: () -> Unit,
    onCopyApiKey: () -> Unit
) {
    val provider = AiProviderConfig.getProvider(state.provider)
    val uriHandler = LocalUriHandler.current
    SettingsCard {
        SectionHeading("AI 平台设置（${provider.displayName}）")
        ProviderSelector(
            label = stringResource(R.string.activity_ai_settings_text_02),
            selectedProvider = state.provider,
            onSelect = onSelectProvider
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.weight(1f),
                label = { Text("${provider.displayName} API Key") },
                singleLine = true,
                visualTransformation = if (state.apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = if (state.apiKeyVisible) onHideApiKey else onRevealApiKey
                    ) {
                        Icon(
                            painter = painterResource(
                                if (state.apiKeyVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
                            ),
                            contentDescription = stringResource(
                                if (state.apiKeyVisible) R.string.ai_api_key_hide else R.string.ai_api_key_show
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
            IconButton(
                onClick = onCopyApiKey,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_content_copy),
                    contentDescription = stringResource(R.string.ai_api_key_copy),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = buildAnnotatedString {
                append("官网：")
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )
                ) {
                    append(provider.websiteUrl)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { runCatching { uriHandler.openUri(provider.websiteUrl) } }
                .padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (provider.customEndpoint) {
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = onBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API 请求地址（Base URL 或完整 Chat Completions 地址）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }
    }
}

@Composable
private fun TranslationSettingsCard(
    state: AiSettingsScreenState,
    onSelectProvider: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onTargetLanguageChange: (String) -> Unit,
    onReasoningChange: (AiProviderConfig.ReasoningLevel) -> Unit,
    onPromptChange: (String) -> Unit,
    onFetchModels: () -> Unit
) {
    val provider = AiProviderConfig.getProvider(state.translationProvider)
    SettingsCard {
        SectionHeading(stringResource(R.string.activity_ai_settings_translation_title))
        ProviderSelector(
            label = "平台",
            selectedProvider = state.translationProvider,
            onSelect = onSelectProvider
        )
        ModelField(
            label = stringResource(R.string.activity_ai_settings_hint_02),
            provider = provider,
            model = state.translationModel,
            onModelChange = onModelChange
        )
        FetchModelsButton(
            visible = provider.customEndpoint,
            loading = state.fetchingTranslationModels,
            onClick = onFetchModels
        )
        OutlinedTextField(
            value = state.targetLanguage,
            onValueChange = onTargetLanguageChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.activity_ai_settings_hint_04)) },
            singleLine = true
        )
        ReasoningSelector(
            level = state.translationReasoning,
            onSelect = onReasoningChange
        )
        OutlinedTextField(
            value = state.translationPrompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.activity_ai_settings_custom_prompt_hint)) },
            minLines = 3
        )
    }
}

@Composable
private fun PunctuationSettingsCard(
    state: AiSettingsScreenState,
    onSelectProvider: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onReasoningChange: (AiProviderConfig.ReasoningLevel) -> Unit,
    onPromptChange: (String) -> Unit,
    onFetchModels: () -> Unit
) {
    val provider = AiProviderConfig.getProvider(state.punctuationProvider)
    SettingsCard {
        SectionHeading(stringResource(R.string.activity_ai_settings_punctuation_title))
        Text(
            text = stringResource(R.string.activity_ai_settings_punctuation_prompt_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ProviderSelector(
            label = "平台",
            selectedProvider = state.punctuationProvider,
            onSelect = onSelectProvider
        )
        ModelField(
            label = stringResource(R.string.activity_ai_settings_hint_02),
            provider = provider,
            model = state.punctuationModel,
            onModelChange = onModelChange
        )
        FetchModelsButton(
            visible = provider.customEndpoint,
            loading = state.fetchingPunctuationModels,
            onClick = onFetchModels
        )
        ReasoningSelector(
            level = state.punctuationReasoning,
            onSelect = onReasoningChange
        )
        OutlinedTextField(
            value = state.punctuationPrompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.activity_ai_settings_custom_prompt_hint)) },
            minLines = 3
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(
    label: String,
    provider: AiProviderConfig.Provider,
    model: String,
    onModelChange: (String) -> Unit
) {
    if (provider.models.isNotEmpty()) {
        val selectedModel = model.takeIf(provider.models::contains) ?: provider.models.first()
        ChoiceField(
            // The legacy fixed-model spinner was preceded by a plain
            // "模型" label; editable providers used the TextInput hint.
            label = stringResource(R.string.activity_ai_settings_text_03),
            selected = selectedModel,
            options = provider.models,
            onSelect = onModelChange
        )
    } else {
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            placeholder = { Text("默认 ${provider.defaultModel}") }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningSelector(
    level: AiProviderConfig.ReasoningLevel,
    onSelect: (AiProviderConfig.ReasoningLevel) -> Unit
) {
    ChoiceField(
        label = stringResource(R.string.activity_ai_settings_reasoning_title),
        selected = level.displayName,
        options = AiProviderConfig.ReasoningLevel.entries.map { it.displayName },
        onSelect = { selected ->
            AiProviderConfig.ReasoningLevel.entries
                .firstOrNull { it.displayName == selected }
                ?.let(onSelect)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSelector(
    label: String,
    selectedProvider: String,
    onSelect: (String) -> Unit
) {
    ChoiceField(
        label = label,
        selected = AiProviderConfig.getProvider(selectedProvider).displayName,
        options = AiProviderConfig.providers.map { it.displayName },
        onSelect = { name ->
            AiProviderConfig.providers.firstOrNull { it.displayName == name }?.let { onSelect(it.id) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun FetchModelsButton(
    visible: Boolean,
    loading: Boolean,
    onClick: () -> Unit
) {
    if (!visible) return
    TextButton(
        onClick = onClick,
        enabled = !loading,
        modifier = Modifier.padding(start = 4.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = stringResource(R.string.activity_ai_settings_fetch_models),
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = { content() }
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}
