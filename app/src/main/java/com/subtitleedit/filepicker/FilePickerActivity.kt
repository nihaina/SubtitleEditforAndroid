package com.subtitleedit.filepicker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.subtitleedit.AppComposeActivity
import com.subtitleedit.collectEvents
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

/** App-owned local file selection; only storage permission settings are opened externally. */
class FilePickerActivity : AppComposeActivity() {
    private val model: FilePickerViewModel by viewModels()

    private val storageSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshPermission()
    }
    private val legacyStoragePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.initialize(FilePickerRequest.fromIntent(intent))
        setContent {
            val state by model.state.collectAsState()
            SubtitleEditComposeTheme {
                FilePickerScreen(
                    state = state,
                    onBack = model::back,
                    onCancel = model::cancel,
                    onPermission = ::requestStoragePermission,
                    onNavigate = model::navigate,
                    onFileClick = model::select,
                    onSearch = model::search,
                    onSort = model::sort,
                    onShowHidden = model::setShowHidden,
                    onFilterUnsupported = model::setFilterUnsupported,
                    onNameChanged = model::setName,
                    onConfirm = model::confirm,
                    onClearSelection = model::clearSelection,
                    onSelectAll = model::selectAll,
                    onSelectRange = model::selectRange,
                    onRefresh = model::refresh,
                    onCreateDirectory = model::createDirectory,
                    onOverwrite = model::overwrite,
                    onRename = model::useUniqueName,
                    onDismissOverwrite = model::dismissOverwrite
                )
            }
        }
        collectEvents(model.events) { event ->
            when (event) {
                is FilePickerEvent.Selected -> {
                    val directory = if (event.files.firstOrNull()?.isDirectory == true) event.files.first()
                        else event.files.firstOrNull()?.parentFile
                    directory?.let {
                        getSharedPreferences(FilePickerPolicy.PREFERENCES_NAME, MODE_PRIVATE).edit()
                            .putString(FilePickerPolicy.LAST_DIRECTORY, it.absolutePath).apply()
                    }
                    setResult(RESULT_OK, AppFilePickerContracts.result(event.files))
                    finish()
                }
                FilePickerEvent.Cancelled -> finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
    }

    private fun refreshPermission() {
        model.updateStoragePermission(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        })
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"))
            if (runCatching { storageSettings.launch(specific) }.isFailure) {
                runCatching { storageSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            legacyStoragePermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
}
