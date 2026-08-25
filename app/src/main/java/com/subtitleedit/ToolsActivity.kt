package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivityToolsBinding

/**
 * 工具页面 - 二级页面，包含各种工具功能入口
 */
class ToolsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityToolsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ToolCardShadow.remove(
            binding.cardBatchConvert,
            binding.cardSubtitleFormat,
            binding.cardAutoTranslate,
            binding.cardVocalSeparation,
            binding.cardSpeechToSubtitle,
            binding.cardMediaConvert,
            binding.cardAutoTimestamp
        )
        
        setupToolbar()
        setupButtons()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "工具"
        
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }
    
    private fun setupButtons() {
        binding.cardBatchConvert.setOnClickListener {
            startActivity(Intent(this, BatchConvertActivity::class.java))
        }

        binding.cardSubtitleFormat.setOnClickListener {
            startActivity(Intent(this, SubtitleFormatSelectActivity::class.java))
        }

        binding.cardAutoTranslate.setOnClickListener {
            startActivity(Intent(this, AutoTranslateActivity::class.java))
        }

        binding.cardMediaConvert.setOnClickListener {
            startActivity(Intent(this, MediaConvertActivity::class.java))
        }

        binding.cardSpeechToSubtitle.setOnClickListener {
            startActivity(Intent(this, SpeechToSubtitleActivity::class.java))
        }

        binding.cardVocalSeparation.setOnClickListener {
            startActivity(Intent(this, VocalSeparationActivity::class.java))
        }

        binding.cardAutoTimestamp.setOnClickListener {
            startActivity(Intent(this, AutoTimestampActivity::class.java))
        }
    }
}
