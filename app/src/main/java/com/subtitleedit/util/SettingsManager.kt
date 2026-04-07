package com.subtitleedit.util

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 设置管理器 - 保存和读取用户设置
 */
class SettingsManager private constructor(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFS_NAME = "subtitle_edit_settings"
        
        private const val KEY_DEFAULT_ENCODING = "default_encoding"
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_AI_SOURCE_LANGUAGE = "ai_source_language"
        private const val KEY_AI_TARGET_LANGUAGE = "ai_target_language"
        private const val KEY_WAVEFORM_CACHE_LOCATION = "waveform_cache_location"
        private const val KEY_LOOP_SELECTED_SUBTITLE = "loop_selected_subtitle"
        private const val KEY_WHISPER_ENCODER_PATH = "whisper_encoder_path"
        private const val KEY_WHISPER_DECODER_PATH = "whisper_decoder_path"
        private const val KEY_WHISPER_TOKENS_PATH = "whisper_tokens_path"
        private const val KEY_VAD_MODEL_PATH = "vad_model_path"
        private const val KEY_VAD_THRESHOLD = "vad_threshold"
        private const val KEY_VAD_MIN_SILENCE_DURATION = "vad_min_silence_duration"
        private const val KEY_VAD_MIN_SPEECH_DURATION = "vad_min_speech_duration"
        private const val KEY_VAD_MAX_SPEECH_DURATION = "vad_max_speech_duration"

        const val WAVEFORM_CACHE_APP = "app_cache"
        const val WAVEFORM_CACHE_SOURCE = "source_dir"
        
        @Volatile private var instance: SettingsManager? = null
        
        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * 获取默认编码
     */
    fun getDefaultEncoding(): Charset {
        val encodingName = prefs.getString(KEY_DEFAULT_ENCODING, StandardCharsets.UTF_8.name())
        return try {
            Charset.forName(encodingName ?: StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            StandardCharsets.UTF_8
        }
    }
    
    /**
     * 设置默认编码
     */
    fun setDefaultEncoding(charset: Charset) {
        prefs.edit().putString(KEY_DEFAULT_ENCODING, charset.name()).apply()
    }
    
    /**
     * 获取 AI API Key
     */
    fun getAiApiKey(): String {
        return prefs.getString(KEY_AI_API_KEY, "") ?: ""
    }
    
    /**
     * 设置 AI API Key
     */
    fun setAiApiKey(apiKey: String) {
        prefs.edit().putString(KEY_AI_API_KEY, apiKey).apply()
    }
    
    /**
     * 获取 AI 模型名称
     */
    fun getAiModel(): String {
        return prefs.getString(KEY_AI_MODEL, "deepseek-ai/DeepSeek-V3.2-Exp") ?: "deepseek-ai/DeepSeek-V3.2-Exp"
    }
    
    /**
     * 设置 AI 模型名称
     */
    fun setAiModel(model: String) {
        prefs.edit().putString(KEY_AI_MODEL, model).apply()
    }
    
    /**
     * 获取 AI 翻译源语言
     */
    fun getAiSourceLanguage(): String {
        return prefs.getString(KEY_AI_SOURCE_LANGUAGE, "自动检测") ?: "自动检测"
    }
    
    /**
     * 设置 AI 翻译源语言
     */
    fun setAiSourceLanguage(language: String) {
        prefs.edit().putString(KEY_AI_SOURCE_LANGUAGE, language).apply()
    }
    
    /**
     * 获取 AI 翻译目标语言
     */
    fun getAiTargetLanguage(): String {
        return prefs.getString(KEY_AI_TARGET_LANGUAGE, "中文") ?: "中文"
    }
    
    /**
     * 设置 AI 翻译目标语言
     */
    fun setAiTargetLanguage(language: String) {
        prefs.edit().putString(KEY_AI_TARGET_LANGUAGE, language).apply()
    }
    
    /**
     * 获取波形缓存存放位置
     */
    fun getWaveformCacheLocation(): String =
        prefs.getString(KEY_WAVEFORM_CACHE_LOCATION, WAVEFORM_CACHE_APP) ?: WAVEFORM_CACHE_APP
    
    /**
     * 设置波形缓存存放位置
     */
    fun setWaveformCacheLocation(location: String) {
        prefs.edit().putString(KEY_WAVEFORM_CACHE_LOCATION, location).apply()
    }
    
    /**
     * 获取是否启用选中字幕循环播放
     */
    fun isLoopSelectedSubtitleEnabled(): Boolean =
        prefs.getBoolean(KEY_LOOP_SELECTED_SUBTITLE, false)
    
    /**
     * 设置是否启用选中字幕循环播放
     */
    fun setLoopSelectedSubtitleEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_LOOP_SELECTED_SUBTITLE, enabled).apply()

    /**
     * 获取 Whisper Encoder 文件路径
     */
    fun getWhisperEncoderPath(): String {
        return prefs.getString(KEY_WHISPER_ENCODER_PATH, "") ?: ""
    }

    /**
     * 设置 Whisper Encoder 文件路径
     */
    fun setWhisperEncoderPath(path: String) {
        prefs.edit().putString(KEY_WHISPER_ENCODER_PATH, path).apply()
    }

    /**
     * 获取 Whisper Decoder 文件路径
     */
    fun getWhisperDecoderPath(): String {
        return prefs.getString(KEY_WHISPER_DECODER_PATH, "") ?: ""
    }

    /**
     * 设置 Whisper Decoder 文件路径
     */
    fun setWhisperDecoderPath(path: String) {
        prefs.edit().putString(KEY_WHISPER_DECODER_PATH, path).apply()
    }

    /**
     * 获取 Whisper Tokens 文件路径
     */
    fun getWhisperTokensPath(): String {
        return prefs.getString(KEY_WHISPER_TOKENS_PATH, "") ?: ""
    }

    /**
     * 设置 Whisper Tokens 文件路径
     */
    fun setWhisperTokensPath(path: String) {
        prefs.edit().putString(KEY_WHISPER_TOKENS_PATH, path).apply()
    }

    /**
     * 获取 VAD 模型文件路径
     */
    fun getVadModelPath(): String {
        return prefs.getString(KEY_VAD_MODEL_PATH, "") ?: ""
    }

    /**
     * 设置 VAD 模型文件路径
     */
    fun setVadModelPath(path: String) {
        prefs.edit().putString(KEY_VAD_MODEL_PATH, path).apply()
    }

    /**
     * 获取 VAD 阈值
     */
    fun getVadThreshold(): Float {
        return prefs.getFloat(KEY_VAD_THRESHOLD, 0.3f)
    }

    /**
     * 设置 VAD 阈值
     */
    fun setVadThreshold(threshold: Float) {
        prefs.edit().putFloat(KEY_VAD_THRESHOLD, threshold).apply()
    }

    /**
     * 获取 VAD 最小静音时长
     */
    fun getVadMinSilenceDuration(): Float {
        return prefs.getFloat(KEY_VAD_MIN_SILENCE_DURATION, 0.3f)
    }

    /**
     * 设置 VAD 最小静音时长
     */
    fun setVadMinSilenceDuration(duration: Float) {
        prefs.edit().putFloat(KEY_VAD_MIN_SILENCE_DURATION, duration).apply()
    }

    /**
     * 获取 VAD 最小语音时长
     */
    fun getVadMinSpeechDuration(): Float {
        return prefs.getFloat(KEY_VAD_MIN_SPEECH_DURATION, 0.25f)
    }

    /**
     * 设置 VAD 最小语音时长
     */
    fun setVadMinSpeechDuration(duration: Float) {
        prefs.edit().putFloat(KEY_VAD_MIN_SPEECH_DURATION, duration).apply()
    }

    /**
     * 获取 VAD 最大语音时长
     */
    fun getVadMaxSpeechDuration(): Float {
        return prefs.getFloat(KEY_VAD_MAX_SPEECH_DURATION, 10.0f)
    }

    /**
     * 设置 VAD 最大语音时长
     */
    fun setVadMaxSpeechDuration(duration: Float) {
        prefs.edit().putFloat(KEY_VAD_MAX_SPEECH_DURATION, duration).apply()
    }
}
