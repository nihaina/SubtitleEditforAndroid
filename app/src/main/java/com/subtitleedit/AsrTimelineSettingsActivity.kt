package com.subtitleedit

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.subtitleedit.databinding.ActivityAsrTimelineSettingsBinding
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.TokenTimestampGenerator
import java.util.Locale

abstract class AsrTimelineSettingsActivity : AppCompatActivity() {

    protected abstract val modelType: String
    protected abstract val modelName: String

    private lateinit var binding: ActivityAsrTimelineSettingsBinding
    private lateinit var settingsManager: SettingsManager
    private var loading = false
    private var updatingSecondaryVadValue = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAsrTimelineSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ToolCardShadow.removeFrom(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setupToolbar()
        setupListeners()
        loadSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "$modelName 配置"

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupListeners() {
        binding.switchUseVadTimestamp.setOnCheckedChangeListener { _, checked ->
            if (!loading) settingsManager.setAsrVadTimestampEnabled(modelType, checked)
            if (!checked && !loading) {
                loading = true
                binding.switchSenseVoiceTimestampExperiment.isChecked =
                    binding.switchSenseVoiceTimestampExperiment.isEnabled &&
                        settingsManager.isSpeechTokenTimestampEnabled()
                loading = false
            }
            updateSenseVoiceTimestampControls()
            updateTimelineSections()
        }

        binding.sliderFixedSegmentSeconds.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.etFixedSegmentSeconds.setText(String.format(Locale.US, "%d", value.toInt()))
            if (!loading) settingsManager.setSpeechFixedSegmentSeconds(value.toInt())
        }
        binding.etFixedSegmentSeconds.addTextChangedListener(simpleTextWatcher {
            val value = it.toIntOrNull() ?: return@simpleTextWatcher
            val clamped = value.coerceIn(5, 120)
            val snapped = ((clamped + 2) / 5) * 5
            if (binding.sliderFixedSegmentSeconds.value.toInt() != snapped) {
                binding.sliderFixedSegmentSeconds.value = snapped.toFloat()
            }
            if (!loading) settingsManager.setSpeechFixedSegmentSeconds(clamped)
        })

        binding.switchSenseVoiceTimestampExperiment.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            if (checked && !hasUsableTokenTimestampModel()) {
                binding.switchSenseVoiceTimestampExperiment.isChecked = false
                OverwritingToast.makeText(
                    this,
                    getString(R.string.activity_speech_to_subtitle_settings_text_47),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnCheckedChangeListener
            }
            settingsManager.setSpeechTokenTimestampEnabled(checked)
            updateSenseVoiceTimestampControls()
            updateTimelineSections()
        }
        binding.switchSenseVoiceTimestampMerge.setOnCheckedChangeListener { _, checked ->
            if (!loading) settingsManager.setSpeechTokenTimestampMergeEnabled(checked)
            updateSenseVoiceTimestampControls()
        }
        bindSecondaryVadValue(
            slider = binding.sliderSenseVoiceTimestampMergeGap,
            input = binding.etSenseVoiceTimestampMergeGap,
            format = "%.0f",
            normalize = { value -> snap(value, 50f, 0f, 5000f) },
            save = { value -> settingsManager.setSpeechTokenTimestampMergeGapMs(value.toInt()) }
        )
    }

    private fun loadSettings() {
        loading = true

        binding.switchUseVadTimestamp.isChecked = settingsManager.isAsrVadTimestampEnabled(modelType)

        val segmentSeconds = settingsManager.getSpeechFixedSegmentSeconds()
        binding.sliderFixedSegmentSeconds.value = segmentSeconds.toFloat()
        binding.etFixedSegmentSeconds.setText(String.format(Locale.US, "%d", segmentSeconds))
        val tokenTimestampModelAvailable = hasUsableTokenTimestampModel()
        binding.switchSenseVoiceTimestampExperiment.isEnabled = tokenTimestampModelAvailable
        binding.switchSenseVoiceTimestampExperiment.isChecked =
            tokenTimestampModelAvailable &&
                settingsManager.isSpeechTokenTimestampEnabled()
        binding.tvSenseVoiceTimestampModelMissing.visibility =
            if (tokenTimestampModelAvailable) View.GONE else View.VISIBLE
        binding.switchSenseVoiceTimestampMerge.isChecked =
            settingsManager.isSpeechTokenTimestampMergeEnabled()
        loadSecondaryVadValue(
            binding.sliderSenseVoiceTimestampMergeGap,
            binding.etSenseVoiceTimestampMergeGap,
            settingsManager.getSpeechTokenTimestampMergeGapMs().toFloat(),
            "%.0f"
        )
        loading = false
        updateSenseVoiceTimestampControls()
        updateTimelineSections()
    }

    private fun updateTimelineSections() {
        val useVad = binding.switchUseVadTimestamp.isChecked
        binding.cardSenseVoiceTimestampExperiment.visibility = if (useVad) View.GONE else View.VISIBLE
    }

    private fun updateSenseVoiceTimestampControls() {
        val enabled = binding.switchSenseVoiceTimestampExperiment.isEnabled &&
            binding.switchSenseVoiceTimestampExperiment.isChecked
        val mergeEnabled = enabled && binding.switchSenseVoiceTimestampMerge.isChecked
        binding.switchSenseVoiceTimestampMerge.isEnabled = enabled
        binding.switchSenseVoiceTimestampMerge.alpha = if (enabled) 1f else 0.5f
        binding.tvSenseVoiceTimestampMergeHint.alpha = if (enabled) 1f else 0.5f
        binding.layoutSenseVoiceTimestampMergeGap.alpha = if (mergeEnabled) 1f else 0.5f
        binding.sliderSenseVoiceTimestampMergeGap.isEnabled = mergeEnabled
        binding.etSenseVoiceTimestampMergeGap.isEnabled = mergeEnabled
    }

    private fun hasUsableTokenTimestampModel(): Boolean {
        return settingsManager.getAsrModelType() == modelType && TokenTimestampGenerator.isConfigured(this)
    }

    private fun bindSecondaryVadValue(
        slider: Slider,
        input: TextInputEditText,
        format: String,
        normalize: (Float) -> Float,
        save: (Float) -> Unit
    ) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (updatingSecondaryVadValue) return@addOnChangeListener
            val normalized = normalize(value)
            updatingSecondaryVadValue = true
            if (fromUser) {
                input.setText(String.format(Locale.US, format, normalized))
                input.setSelection(input.text?.length ?: 0)
            }
            updatingSecondaryVadValue = false
            if (!loading) save(normalized)
        }
        input.addTextChangedListener(simpleTextWatcher { text ->
            if (updatingSecondaryVadValue || text.isBlank() || text.endsWith(".")) {
                return@simpleTextWatcher
            }
            val value = text.toFloatOrNull() ?: return@simpleTextWatcher
            val normalized = normalize(value)
            updatingSecondaryVadValue = true
            if (kotlin.math.abs(slider.value - normalized) >= 0.0001f) {
                slider.value = normalized
            }
            val normalizedText = String.format(Locale.US, format, normalized)
            if (text != normalizedText) {
                input.setText(normalizedText)
                input.setSelection(input.text?.length ?: 0)
            }
            updatingSecondaryVadValue = false
            if (!loading) save(normalized)
        })
    }

    private fun loadSecondaryVadValue(
        slider: Slider,
        input: TextInputEditText,
        value: Float,
        format: String
    ) {
        slider.value = value
        input.setText(String.format(Locale.US, format, value))
    }

    private fun snap(value: Float, step: Float, min: Float, max: Float): Float {
        val clamped = value.coerceIn(min, max)
        return (Math.round((clamped - min) / step) * step + min).coerceIn(min, max)
    }

    private fun simpleTextWatcher(afterChanged: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                afterChanged(s?.toString().orEmpty())
            }
        }
    }
}

class SenseVoiceSettingsActivity : AsrTimelineSettingsActivity() {
    override val modelType = SettingsManager.ASR_MODEL_SENSEVOICE
    override val modelName = "SenseVoice"
}

class ParakeetSettingsActivity : AsrTimelineSettingsActivity() {
    override val modelType: String by lazy {
        SettingsManager.getInstance(this).getAsrModelType()
    }
    override val modelName = "Parakeet"
}
