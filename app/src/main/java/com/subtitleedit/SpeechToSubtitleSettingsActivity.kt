package com.subtitleedit

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.subtitleedit.databinding.ActivitySpeechToSubtitleSettingsBinding
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import java.util.Locale

class SpeechToSubtitleSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpeechToSubtitleSettingsBinding
    private lateinit var settingsManager: SettingsManager
    private var loading = false
    private var updatingSecondaryVadMode = false
    private var updatingSecondaryVadValue = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpeechToSubtitleSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ToolCardShadow.removeFrom(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setupToolbar()
        setupListeners()
        loadSettings()
        binding.tvWhisperThreadsTitle.visibility = View.GONE
        binding.tvWhisperThreadsHint.visibility = View.GONE
        binding.layoutWhisperThreads.visibility = View.GONE
        binding.cardHotwords.visibility = View.GONE
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "语音转字幕配置"

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupListeners() {
        binding.switchVadDynamicPadding.setOnCheckedChangeListener { _, checked ->
            if (!loading) settingsManager.setSpeechVadDynamicPaddingEnabled(checked)
        }

        binding.switchSecondaryVadSchemeOne.setOnCheckedChangeListener { _, checked ->
            updateSecondaryVadMode(SettingsManager.SECONDARY_VAD_MODE_UNCOVERED, checked)
        }
        binding.switchSecondaryVadSchemeTwo.setOnCheckedChangeListener { _, checked ->
            updateSecondaryVadMode(SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS, checked)
        }
        binding.switchSecondaryVadMerge.setOnCheckedChangeListener { _, checked ->
            if (!loading) settingsManager.setSpeechSecondaryVadMergeEnabled(checked)
        }
        bindSecondaryVadValue(
            slider = binding.sliderSecondaryVadMergeGap,
            input = binding.etSecondaryVadMergeGap,
            format = "%.0f",
            normalize = { value -> snap(value, 50f, 0f, 5000f) },
            save = { value -> settingsManager.setSpeechSecondaryVadMergeGapMs(value.toInt()) }
        )

        bindSecondaryVadValue(
            slider = binding.sliderSecondaryVadThreshold,
            input = binding.etSecondaryVadThreshold,
            format = "%.2f",
            normalize = ::normalizeSecondaryVadThreshold,
            save = settingsManager::setSpeechSecondaryVadThreshold
        )
        bindSecondaryVadValue(
            slider = binding.sliderSecondaryVadMinSilence,
            input = binding.etSecondaryVadMinSilence,
            format = "%.2f",
            normalize = { value -> snap(value, 0.1f, 0.1f, 2.0f) },
            save = settingsManager::setSpeechSecondaryVadMinSilenceDuration
        )
        bindSecondaryVadValue(
            slider = binding.sliderSecondaryVadMinSpeech,
            input = binding.etSecondaryVadMinSpeech,
            format = "%.2f",
            normalize = { value -> snap(value, 0.05f, 0.05f, 1.0f) },
            save = settingsManager::setSpeechSecondaryVadMinSpeechDuration
        )
        bindSecondaryVadValue(
            slider = binding.sliderSecondaryVadMaxSpeech,
            input = binding.etSecondaryVadMaxSpeech,
            format = "%.1f",
            normalize = { value -> snap(value, 5.0f, 5.0f, 60.0f) },
            save = settingsManager::setSpeechSecondaryVadMaxSpeechDuration
        )

        binding.switchHotwords.setOnCheckedChangeListener { _, checked ->
            if (!loading) settingsManager.setSpeechHotwordsEnabled(checked)
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

        binding.btnSaveHotwords.setOnClickListener {
            settingsManager.setSpeechHotwords(binding.etHotwords.text?.toString().orEmpty())
            OverwritingToast.makeText(this, "热词已保存", Toast.LENGTH_SHORT).show()
        }

        binding.sliderHotwordsScore.addOnChangeListener { _, value, _ ->
            binding.tvHotwordsScore.text = String.format(Locale.getDefault(), "%.1f", value)
            if (!loading) settingsManager.setSpeechHotwordsScore(value)
        }
        binding.sliderWhisperThreads.addOnChangeListener { _, value, _ ->
            binding.tvWhisperThreads.text = String.format(Locale.getDefault(), "%d", value.toInt())
            if (!loading) settingsManager.setSpeechWhisperThreads(value.toInt())
        }
    }

    private fun loadSettings() {
        loading = true

        val segmentSeconds = settingsManager.getSpeechFixedSegmentSeconds()
        binding.sliderFixedSegmentSeconds.value = segmentSeconds.toFloat()
        binding.etFixedSegmentSeconds.setText(String.format(Locale.US, "%d", segmentSeconds))
        binding.switchVadDynamicPadding.isChecked = settingsManager.isSpeechVadDynamicPaddingEnabled()

        when (settingsManager.getSpeechSecondaryVadMode()) {
            SettingsManager.SECONDARY_VAD_MODE_UNCOVERED -> {
                binding.switchSecondaryVadSchemeOne.isChecked = true
                binding.switchSecondaryVadSchemeTwo.isChecked = false
            }
            SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS -> {
                binding.switchSecondaryVadSchemeOne.isChecked = false
                binding.switchSecondaryVadSchemeTwo.isChecked = true
            }
            else -> {
                binding.switchSecondaryVadSchemeOne.isChecked = false
                binding.switchSecondaryVadSchemeTwo.isChecked = false
            }
        }
        binding.switchSecondaryVadMerge.isChecked =
            settingsManager.isSpeechSecondaryVadMergeEnabled()
        loadSecondaryVadValue(
            binding.sliderSecondaryVadMergeGap,
            binding.etSecondaryVadMergeGap,
            settingsManager.getSpeechSecondaryVadMergeGapMs().toFloat(),
            "%.0f"
        )
        loadSecondaryVadValue(
            binding.sliderSecondaryVadThreshold,
            binding.etSecondaryVadThreshold,
            settingsManager.getSpeechSecondaryVadThreshold(),
            "%.2f"
        )
        loadSecondaryVadValue(
            binding.sliderSecondaryVadMinSilence,
            binding.etSecondaryVadMinSilence,
            settingsManager.getSpeechSecondaryVadMinSilenceDuration(),
            "%.2f"
        )
        loadSecondaryVadValue(
            binding.sliderSecondaryVadMinSpeech,
            binding.etSecondaryVadMinSpeech,
            settingsManager.getSpeechSecondaryVadMinSpeechDuration(),
            "%.2f"
        )
        loadSecondaryVadValue(
            binding.sliderSecondaryVadMaxSpeech,
            binding.etSecondaryVadMaxSpeech,
            settingsManager.getSpeechSecondaryVadMaxSpeechDuration(),
            "%.1f"
        )

        binding.switchHotwords.isChecked = settingsManager.isSpeechHotwordsEnabled()
        binding.etHotwords.setText(settingsManager.getSpeechHotwords())

        val hotwordsScore = settingsManager.getSpeechHotwordsScore()
        binding.sliderHotwordsScore.value = hotwordsScore
        binding.tvHotwordsScore.text = String.format(Locale.getDefault(), "%.1f", hotwordsScore)

        val whisperThreads = settingsManager.getSpeechWhisperThreads()
        binding.sliderWhisperThreads.value = whisperThreads.toFloat()
        binding.tvWhisperThreads.text = String.format(Locale.getDefault(), "%d", whisperThreads)

        loading = false
    }

    private fun updateSecondaryVadMode(mode: String, checked: Boolean) {
        if (loading || updatingSecondaryVadMode) return

        updatingSecondaryVadMode = true
        if (checked) {
            if (mode == SettingsManager.SECONDARY_VAD_MODE_UNCOVERED) {
                binding.switchSecondaryVadSchemeTwo.isChecked = false
            } else {
                binding.switchSecondaryVadSchemeOne.isChecked = false
            }
            settingsManager.setSpeechSecondaryVadMode(mode)
        } else {
            val activeMode = when {
                binding.switchSecondaryVadSchemeOne.isChecked ->
                    SettingsManager.SECONDARY_VAD_MODE_UNCOVERED
                binding.switchSecondaryVadSchemeTwo.isChecked ->
                    SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS
                else -> SettingsManager.SECONDARY_VAD_MODE_NONE
            }
            settingsManager.setSpeechSecondaryVadMode(activeMode)
        }
        updatingSecondaryVadMode = false
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

    private fun normalizeSecondaryVadThreshold(value: Float): Float {
        return snap(value, 0.05f, 0.1f, 0.9f)
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
