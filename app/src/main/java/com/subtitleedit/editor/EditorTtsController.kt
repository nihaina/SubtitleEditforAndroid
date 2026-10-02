package com.subtitleedit.editor

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.view.View
import com.subtitleedit.util.SettingsManager
import java.util.Locale

/** 使用设置中选定的系统 TTS 引擎，按字幕顺序朗读传入文本。 */
internal class EditorTtsController(
    private val activity: Activity,
    private val rootView: View,
    private val showMessage: (String) -> Unit
) {
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitializing = false
    private var activeEnginePreference: String? = null
    private var activeLanguagePreference: String? = null
    private var defaultLocale: Locale? = null
    private var pendingTexts: List<String> = emptyList()
    private var generation = 0

    fun speak(texts: List<String>) {
        val settings = SettingsManager.getInstance(activity)
        val requestedEngine = settings.getTtsEngine()
        val requestedLanguage = settings.getTtsLanguage()
        if (ttsReady && activeEnginePreference == requestedEngine &&
            activeLanguagePreference == requestedLanguage
        ) {
            speakSubtitleTexts(texts, requestedLanguage)
            return
        }

        pendingTexts = texts
        if (ttsInitializing && activeEnginePreference == requestedEngine &&
            activeLanguagePreference == requestedLanguage
        ) return
        initializeTts(requestedEngine, requestedLanguage)
    }

    fun release() {
        generation++
        pendingTexts = emptyList()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private fun initializeTts(requestedEngine: String, requestedLanguage: String) {
        if (requestedEngine.isNotBlank() && !isTtsEngineInstalled(requestedEngine)) {
            generation++
            pendingTexts = emptyList()
            textToSpeech?.shutdown()
            textToSpeech = null
            ttsReady = false
            ttsInitializing = false
            activeEnginePreference = null
            activeLanguagePreference = null
            showMessage("所选 TTS 引擎已不可用，请在设置中重新选择")
            return
        }
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
        ttsInitializing = true
        activeEnginePreference = requestedEngine
        activeLanguagePreference = requestedLanguage
        defaultLocale = null
        val currentGeneration = ++generation

        val listener = TextToSpeech.OnInitListener { status ->
            // post 确保构造函数已返回且 textToSpeech 字段已经完成赋值。
            rootView.post {
                if (currentGeneration != generation || activity.isDestroyed) return@post
                ttsInitializing = false
                // defaultEngine 表示系统默认引擎，并不表示构造函数指定的当前引擎。
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    defaultLocale = textToSpeech?.voice?.locale
                    val texts = pendingTexts
                    pendingTexts = emptyList()
                    speakSubtitleTexts(texts, requestedLanguage)
                } else {
                    pendingTexts = emptyList()
                    textToSpeech?.shutdown()
                    textToSpeech = null
                    activeEnginePreference = null
                    activeLanguagePreference = null
                    showMessage(
                        if (requestedEngine.isBlank()) "系统默认 TTS 引擎初始化失败"
                        else "所选 TTS 引擎不可用，请在设置中重新选择"
                    )
                }
            }
        }

        textToSpeech = if (requestedEngine.isBlank()) {
            TextToSpeech(activity, listener)
        } else {
            TextToSpeech(activity, listener, requestedEngine)
        }
    }

    @Suppress("DEPRECATION")
    private fun isTtsEngineInstalled(packageName: String): Boolean {
        return activity.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.MATCH_ALL
        ).any { it.serviceInfo?.packageName == packageName }
    }

    private fun speakSubtitleTexts(texts: List<String>, languagePreference: String) {
        val tts = textToSpeech ?: return
        val maxLength = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1)

        tts.stop()
        val utterancePrefix = "quick_tts_${System.currentTimeMillis()}"
        var utteranceIndex = 0
        texts.forEach { subtitleText ->
            val locale = resolveTtsLocale(subtitleText, languagePreference) ?: defaultLocale
            if (locale != null) {
                val availability = tts.setLanguage(locale)
                if (availability == TextToSpeech.LANG_MISSING_DATA ||
                    availability == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    showMessage("所选 TTS 引擎不支持 ${locale.displayLanguage}，请安装对应语音包")
                    tts.stop()
                    return
                }
            }

            subtitleText.chunked(maxLength).forEach { chunk ->
                val result = tts.speak(
                    chunk,
                    if (utteranceIndex == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    "${utterancePrefix}_${utteranceIndex++}"
                )
                if (result == TextToSpeech.ERROR) {
                    showMessage("TTS 朗读启动失败")
                    tts.stop()
                    return
                }
            }
        }
    }

    private fun resolveTtsLocale(text: String, preference: String): Locale? {
        return when (preference) {
            SettingsManager.TTS_LANGUAGE_JAPANESE -> Locale.JAPAN
            SettingsManager.TTS_LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
            SettingsManager.TTS_LANGUAGE_ENGLISH -> Locale.US
            SettingsManager.TTS_LANGUAGE_AUTO -> when {
                // 假名可以可靠地区分日语；纯汉字在中日文之间本身具有歧义。
                text.any { it.code in 0x3040..0x30ff || it.code in 0xff66..0xff9f } -> Locale.JAPAN
                text.any { it.code in 0x3400..0x9fff } -> {
                    if (Locale.getDefault().language == Locale.JAPANESE.language) Locale.JAPAN
                    else Locale.SIMPLIFIED_CHINESE
                }
                text.any { it in 'A'..'Z' || it in 'a'..'z' } -> Locale.US
                else -> null
            }
            else -> null
        }
    }
}
