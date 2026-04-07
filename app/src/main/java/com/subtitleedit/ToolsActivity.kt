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

        binding.cardMediaConvert.setOnClickListener {
            startActivity(Intent(this, MediaConvertActivity::class.java))
        }

        binding.cardSpeechToSubtitle.setOnClickListener {
            startActivity(Intent(this, SpeechToSubtitleActivity::class.java))
        }
    }
}
