package com.subtitleedit

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivityVadModelSettingsBinding
import com.subtitleedit.util.SettingsManager

class VadModelSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVadModelSettingsBinding
    private lateinit var settings: SettingsManager

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivityVadModelSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsManager.getInstance(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}
