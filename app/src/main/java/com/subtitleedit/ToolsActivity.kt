package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import android.view.View
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
        // 批量转换卡片点击事件
        binding.cardBatchConvert.setOnClickListener {
            startActivity(Intent(this, BatchConvertActivity::class.java))
        }
        
        // 其他功能卡片（预留位置）
        // 暂不实现
    }
}
