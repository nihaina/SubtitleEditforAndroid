package com.subtitleedit

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.subtitleedit.feature.ui.ModelImportDialogUi
import com.subtitleedit.util.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * What the model import controllers need from their owner (ModelManagementViewModel).
 * Everything here is Activity-free; Activity-only work is requested through [sendEvent].
 */
internal interface ModelImportHost {
    val app: Application
    val scope: CoroutineScope
    fun text(@StringRes id: Int, vararg args: Any): String
    fun showToast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT)
    fun sendEvent(event: ModelManagementEvent)

    /** Asks the Activity to open the storage permission flow on behalf of [requester]. */
    fun requestStorageAccess(requester: StorageAccessRequester)
}

internal fun ModelImportHost.showToast(@StringRes id: Int, vararg args: Any, duration: Int = Toast.LENGTH_SHORT) =
    showToast(text(id, *args), duration)

internal enum class StorageAccessRequester { MODEL_LIST, ASR_IMPORT, DEMUCS_IMPORT }

/** Single-document pickers; each has its own launcher in the Activity. */
internal enum class ModelPickTarget(val mimeTypes: Array<String>) {
    ASR_ENCODER(arrayOf("*/*")),
    ASR_DECODER(arrayOf("*/*")),
    ASR_JOINER(arrayOf("*/*")),
    ASR_TOKENS(arrayOf("*/*")),
    ASR_VAD(arrayOf("*/*")),
    DEMUCS_GENERAL(DEMUCS_MODEL_MIME_TYPES),
    DEMUCS_VOCALS(DEMUCS_MODEL_MIME_TYPES),
    DEMUCS_DRUMS(DEMUCS_MODEL_MIME_TYPES),
    DEMUCS_BASS(DEMUCS_MODEL_MIME_TYPES),
    DEMUCS_OTHER(DEMUCS_MODEL_MIME_TYPES)
}

private val DEMUCS_MODEL_MIME_TYPES get() = arrayOf("application/octet-stream", "application/onnx", "*/*")

/** One-shot requests from ModelManagementViewModel that need an Activity. */
internal sealed interface ModelManagementEvent {
    data class PickDocument(val target: ModelPickTarget) : ModelManagementEvent
    data object PickQwen3Tokenizer : ModelManagementEvent
    data object PickForcedAligner : ModelManagementEvent
    data object RequestStorageAccess : ModelManagementEvent
    data object RequestNotificationPermission : ModelManagementEvent
    data class OpenScreen(val activity: Class<out android.app.Activity>) : ModelManagementEvent
    data object OpenAsrSettings : ModelManagementEvent

    /** Opens [url]; when [failureMessage] is set, a missing handler shows it instead of crashing. */
    data class OpenUrl(val url: String, val failureMessage: String? = null) : ModelManagementEvent
}

internal fun hasModelStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

internal fun formatModelSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Dialog state shared by the import controllers and the export flow. Thread-safe:
 * progress updates may arrive from background dispatchers.
 */
internal open class ModelImportDialogState(private val host: ModelImportHost) {
    private val _dialog = MutableStateFlow<ModelImportDialogUi?>(null)
    val dialog: StateFlow<ModelImportDialogUi?> = _dialog.asStateFlow()
    private var nextProgressToken = 0L

    protected fun setDialog(value: ModelImportDialogUi?) {
        _dialog.value = value
    }

    protected fun showMessageDialog(
        title: String,
        message: String,
        confirmLabel: String? = host.text(R.string.model_mgmt_ok),
        dismissLabel: String? = null,
        auxiliaryLabel: String? = null,
        destructiveConfirm: Boolean = false,
        onConfirm: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onAuxiliary: () -> Unit = {}
    ) {
        _dialog.value = ModelImportDialogUi.Message(
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            dismissLabel = dismissLabel,
            auxiliaryLabel = auxiliaryLabel,
            destructiveConfirm = destructiveConfirm,
            onConfirm = { _dialog.value = null; onConfirm() },
            onDismiss = { _dialog.value = null; onDismiss() },
            onAuxiliary = { _dialog.value = null; onAuxiliary() }
        )
    }

    protected fun showOptionsDialog(
        title: String,
        options: List<String>,
        selectedIndex: Int? = null,
        onSelect: (Int) -> Unit
    ) {
        _dialog.value = ModelImportDialogUi.Options(
            title = title,
            options = options,
            selectedIndex = selectedIndex,
            onSelect = { index -> _dialog.value = null; onSelect(index) },
            onDismiss = { _dialog.value = null }
        )
    }

    fun showProgressDialog(title: String, onCancel: () -> Unit): Long {
        val token = ++nextProgressToken
        _dialog.value = ModelImportDialogUi.Progress(
            token = token,
            title = title,
            message = host.text(R.string.model_mgmt_preparing_download),
            onCancel = {
                dismissProgressDialog(token)
                onCancel()
            }
        )
        return token
    }

    fun updateProgressDialog(token: Long, progress: ModelDownloader.Progress) {
        val total = progress.totalBytes
        val message = if (total > 0L) {
            val percent = ((progress.downloadedBytes * 100L) / total).coerceIn(0L, 100L)
            host.text(
                R.string.model_mgmt_progress_percent,
                progress.message,
                percent.toInt(),
                formatModelSize(progress.downloadedBytes),
                formatModelSize(total)
            )
        } else {
            host.text(
                R.string.model_mgmt_progress_processed,
                progress.message,
                formatModelSize(progress.downloadedBytes.coerceAtLeast(0L))
            )
        }
        _dialog.update { current ->
            if (current is ModelImportDialogUi.Progress && current.token == token) {
                current.copy(
                    message = message,
                    progress = if (total > 0L) progress.downloadedBytes.toFloat() / total else null
                )
            } else current
        }
    }

    fun dismissProgressDialog(token: Long) {
        _dialog.update { current ->
            if ((current as? ModelImportDialogUi.Progress)?.token == token) null else current
        }
    }
}
