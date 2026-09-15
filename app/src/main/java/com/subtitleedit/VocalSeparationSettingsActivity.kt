package com.subtitleedit

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivityVocalSeparationSettingsBinding
import com.subtitleedit.util.SettingsManager

/** 人声分离运行参数；Demucs 模型选择与导入已集中到模型管理页。 */
class VocalSeparationSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVocalSeparationSettingsBinding
    private lateinit var settings: SettingsManager
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVocalSeparationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsManager.getInstance(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "人声分离设置"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        loading = true
        binding.switchGraphOptimization.isChecked = settings.isDemixOrtGraphOptimizationEnabled()
        binding.switchCpuArena.isChecked = settings.isDemixOrtCpuArenaEnabled()
        loading = false
        binding.switchGraphOptimization.setOnCheckedChangeListener { _, checked ->
            if (!loading) settings.setDemixOrtGraphOptimizationEnabled(checked)
        }
        binding.switchCpuArena.setOnCheckedChangeListener { _, checked ->
            if (!loading) settings.setDemixOrtCpuArenaEnabled(checked)
        }
    }
}
