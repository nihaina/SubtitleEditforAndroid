package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivityModelSettingsBinding

/** 语音转录通用高级配置入口；模型选择与导入已集中到模型管理页。 */
class ModelSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityModelSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "语音转录设置"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSpeechAdvancedSettings.setOnClickListener {
            startActivity(Intent(this, SpeechToSubtitleSettingsActivity::class.java))
        }
    }
}
