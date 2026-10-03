package com.subtitleedit

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

abstract class AppComposeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_SubtitleEditforAndroid_Compose)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    protected fun navigateBack() {
        onBackPressedDispatcher.onBackPressed()
    }
}
