package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivityQwen3AsrSettingsBinding
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.TokenTimestampGenerator

class Qwen3AsrSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQwen3AsrSettingsBinding
    private lateinit var settingsManager: SettingsManager
    private var loading = false
    private var updatingGap = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQwen3AsrSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ToolCardShadow.removeFrom(binding.root)
        settingsManager = SettingsManager.getInstance(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        setupListeners()
        loadSettings()
    }

    private fun setupListeners() {
        binding.btnVadSettings.setOnClickListener {
            startActivity(Intent(this, VadModelSettingsActivity::class.java))
        }
        binding.switchUseVadTimestamp.setOnCheckedChangeListener { _, enabled ->
            if (loading) return@setOnCheckedChangeListener
            settingsManager.setAsrVadTimestampEnabled(SettingsManager.ASR_MODEL_QWEN3_ASR, enabled)
            if (!enabled) {
                loading = true
                binding.switchForcedAlignment.isChecked =
                    binding.switchForcedAlignment.isEnabled &&
                        settingsManager.isSpeechTokenTimestampEnabled()
                loading = false
            }
            updateControls()
        }
        binding.switchForcedAlignment.setOnCheckedChangeListener { _, enabled ->
            if (loading) return@setOnCheckedChangeListener
            settingsManager.setSpeechTokenTimestampEnabled(enabled)
            updateControls()
        }
        binding.switchMergeSegments.setOnCheckedChangeListener { _, enabled ->
            if (loading) return@setOnCheckedChangeListener
            settingsManager.setSpeechTokenTimestampMergeEnabled(enabled)
            updateControls()
        }
        binding.sliderMergeGap.addOnChangeListener { _, value, fromUser ->
            if (loading || updatingGap || !fromUser) return@addOnChangeListener
            updatingGap = true
            binding.etMergeGap.setText(value.toInt().toString())
            binding.etMergeGap.setSelection(binding.etMergeGap.text?.length ?: 0)
            updatingGap = false
            settingsManager.setSpeechTokenTimestampMergeGapMs(value.toInt())
        }
        binding.etMergeGap.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (loading || updatingGap) return
                val value = s?.toString()?.toIntOrNull() ?: return
                val snapped = ((value.coerceIn(0, 5000) + 25) / 50) * 50
                updatingGap = true
                binding.sliderMergeGap.value = snapped.toFloat()
                if (s.toString() != snapped.toString()) {
                    binding.etMergeGap.setText(snapped.toString())
                    binding.etMergeGap.setSelection(binding.etMergeGap.text?.length ?: 0)
                }
                updatingGap = false
                settingsManager.setSpeechTokenTimestampMergeGapMs(snapped)
            }
        })
    }

    private fun loadSettings() {
        loading = true
        binding.switchUseVadTimestamp.isChecked =
            settingsManager.isAsrVadTimestampEnabled(SettingsManager.ASR_MODEL_QWEN3_ASR)
        val alignerAvailable = settingsManager.getAsrModelType() == SettingsManager.ASR_MODEL_QWEN3_ASR &&
            TokenTimestampGenerator.isConfigured(this)
        binding.switchForcedAlignment.isEnabled = alignerAvailable
        binding.switchForcedAlignment.isChecked =
            alignerAvailable && settingsManager.isSpeechTokenTimestampEnabled()
        binding.tvForcedAlignmentModelMissing.visibility =
            if (alignerAvailable) View.GONE else View.VISIBLE
        binding.switchMergeSegments.isChecked = settingsManager.isSpeechTokenTimestampMergeEnabled()
        val gap = settingsManager.getSpeechTokenTimestampMergeGapMs()
        val snappedGap = ((gap + 25) / 50) * 50
        binding.sliderMergeGap.value = snappedGap.toFloat()
        binding.etMergeGap.setText(snappedGap.toString())
        loading = false
        updateControls()
    }

    private fun updateControls() {
        binding.btnVadSettings.visibility =
            if (binding.switchUseVadTimestamp.isChecked) View.VISIBLE else View.GONE
        binding.cardAsrTranscription.visibility =
            if (binding.switchUseVadTimestamp.isChecked) View.GONE else View.VISIBLE
        val alignerEnabled = binding.switchForcedAlignment.isEnabled &&
            binding.switchForcedAlignment.isChecked
        binding.switchMergeSegments.isEnabled = alignerEnabled
        binding.switchMergeSegments.alpha = if (alignerEnabled) 1f else 0.5f
        binding.tvMergeSegmentsHint.alpha = if (alignerEnabled) 1f else 0.5f
        val gapEnabled = alignerEnabled && binding.switchMergeSegments.isChecked
        binding.layoutMergeGap.alpha = if (gapEnabled) 1f else 0.5f
        binding.sliderMergeGap.isEnabled = gapEnabled
        binding.etMergeGap.isEnabled = gapEnabled
    }
}
