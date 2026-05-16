package com.subtitleedit

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivitySpeechToSubtitleSettingsBinding
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager

class SpeechToSubtitleSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpeechToSubtitleSettingsBinding
    private lateinit var settingsManager: SettingsManager
    private var loading = false

    private val keywordModelOptions = listOf(
        "内置中英 KWS - Zipformer 3M"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpeechToSubtitleSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setupToolbar()
        setupKeywordModelSpinner()
        setupListeners()
        loadSettings()
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

    private fun setupKeywordModelSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            keywordModelOptions
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerKeywordModel.adapter = adapter
    }

    private fun setupListeners() {
        binding.switchKeywordSpotting.setOnCheckedChangeListener { _, checked ->
            if (!loading) settingsManager.setKeywordSpottingEnabled(checked)
            if (checked && !loading) {
                OverwritingToast.makeText(
                    this,
                    "关键词发现开启后，只输出关键词和对应时间戳",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.spinnerKeywordModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!loading) settingsManager.setKeywordSpottingModelType(position)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        binding.sliderFixedSegmentSeconds.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.etFixedSegmentSeconds.setText(value.toInt().toString())
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

        binding.btnSaveKeywords.setOnClickListener {
            settingsManager.setKeywordSpottingKeywords(binding.etKeywords.text?.toString().orEmpty())
            OverwritingToast.makeText(this, "关键词已保存", Toast.LENGTH_SHORT).show()
        }

        binding.sliderKeywordScore.addOnChangeListener { _, value, _ ->
            binding.tvKeywordScore.text = String.format("%.1f", value)
            if (!loading) settingsManager.setKeywordSpottingScore(value)
        }
        binding.sliderKeywordThreshold.addOnChangeListener { _, value, _ ->
            binding.tvKeywordThreshold.text = String.format("%.2f", value)
            if (!loading) settingsManager.setKeywordSpottingThreshold(value)
        }
        binding.sliderTrailingBlanks.addOnChangeListener { _, value, _ ->
            binding.tvTrailingBlanks.text = value.toInt().toString()
            if (!loading) settingsManager.setKeywordSpottingNumTrailingBlanks(value.toInt())
        }
        binding.sliderWhisperThreads.addOnChangeListener { _, value, _ ->
            binding.tvWhisperThreads.text = value.toInt().toString()
            if (!loading) settingsManager.setSpeechWhisperThreads(value.toInt())
        }
    }

    private fun loadSettings() {
        loading = true

        val segmentSeconds = settingsManager.getSpeechFixedSegmentSeconds()
        binding.sliderFixedSegmentSeconds.value = segmentSeconds.toFloat()
        binding.etFixedSegmentSeconds.setText(segmentSeconds.toString())

        binding.switchKeywordSpotting.isChecked = settingsManager.isKeywordSpottingEnabled()
        binding.spinnerKeywordModel.setSelection(settingsManager.getKeywordSpottingModelType())
        binding.etKeywords.setText(settingsManager.getKeywordSpottingKeywords())

        val keywordScore = settingsManager.getKeywordSpottingScore()
        binding.sliderKeywordScore.value = keywordScore
        binding.tvKeywordScore.text = String.format("%.1f", keywordScore)

        val keywordThreshold = settingsManager.getKeywordSpottingThreshold()
        binding.sliderKeywordThreshold.value = keywordThreshold
        binding.tvKeywordThreshold.text = String.format("%.2f", keywordThreshold)

        val trailingBlanks = settingsManager.getKeywordSpottingNumTrailingBlanks()
        binding.sliderTrailingBlanks.value = trailingBlanks.toFloat()
        binding.tvTrailingBlanks.text = trailingBlanks.toString()

        val whisperThreads = settingsManager.getSpeechWhisperThreads()
        binding.sliderWhisperThreads.value = whisperThreads.toFloat()
        binding.tvWhisperThreads.text = whisperThreads.toString()

        loading = false
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
