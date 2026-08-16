package com.subtitleedit

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivityWhisperSettingsBinding
import com.subtitleedit.util.SettingsManager
import java.util.Locale

/** Whisper-specific controls. Shared recognition-flow settings are configured globally. */
class WhisperSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWhisperSettingsBinding
    private lateinit var settings: SettingsManager
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhisperSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ToolCardShadow.removeFrom(binding.root)
        settings = SettingsManager.getInstance(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.activity_whisper_settings_title)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.sliderWhisperThreads.addOnChangeListener { _, value, _ ->
            binding.tvWhisperThreads.text = String.format(Locale.getDefault(), "%d", value.toInt())
            if (!loading) settings.setSpeechWhisperThreads(value.toInt())
        }
        binding.switchHotwords.setOnCheckedChangeListener { _, checked ->
            if (!loading) settings.setSpeechHotwordsEnabled(checked)
        }
        binding.sliderHotwordsScore.addOnChangeListener { _, value, _ ->
            binding.tvHotwordsScore.text = String.format(Locale.getDefault(), "%.1f", value)
            if (!loading) settings.setSpeechHotwordsScore(value)
        }
        binding.btnSaveHotwords.setOnClickListener {
            settings.setSpeechHotwords(binding.etHotwords.text?.toString().orEmpty())
        }

        loading = true
        binding.sliderWhisperThreads.value = settings.getSpeechWhisperThreads().toFloat()
        binding.tvWhisperThreads.text = String.format(
            Locale.getDefault(),
            "%d",
            settings.getSpeechWhisperThreads()
        )
        binding.switchHotwords.isChecked = settings.isSpeechHotwordsEnabled()
        binding.etHotwords.setText(settings.getSpeechHotwords())
        binding.sliderHotwordsScore.value = settings.getSpeechHotwordsScore()
        binding.tvHotwordsScore.text = String.format(Locale.getDefault(), "%.1f", settings.getSpeechHotwordsScore())
        loading = false
    }
}
