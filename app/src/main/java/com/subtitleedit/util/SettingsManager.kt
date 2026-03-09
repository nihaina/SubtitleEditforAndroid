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
}
