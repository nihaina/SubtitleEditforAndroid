package com.subtitleedit.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import com.subtitleedit.ComposeDialogHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val VERSION_URL =
        "https://nihaina.github.io/SubtitleEditforAndroid/version.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val forceUpdate: Boolean
    )

    sealed interface CheckResult {
        data class UpdateAvailable(val update: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data object Failure : CheckResult
    }

    suspend fun check(activity: Activity): UpdateInfo? {
        return when (val result = checkResult(activity)) {
            is CheckResult.UpdateAvailable -> result.update
            CheckResult.UpToDate, CheckResult.Failure -> null
        }
    }

    suspend fun checkResult(activity: Activity): CheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(VERSION_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "SubtitleEdit-Android")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext CheckResult.Failure
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val remoteVersionCode = json.getLong("versionCode")
                val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                if (remoteVersionCode <= currentVersionCode) {
                    return@withContext CheckResult.UpToDate
                }

                CheckResult.UpdateAvailable(
                    UpdateInfo(
                        versionCode = remoteVersionCode,
                        versionName = json.getString("versionName"),
                        downloadUrl = json.getString("downloadUrl"),
                        releaseNotes = json.optString("releaseNotes", "发现新版本"),
                        forceUpdate = json.optBoolean("forceUpdate", false)
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CheckResult.Failure
        }
    }

    fun showUpdateDialog(activity: Activity, update: UpdateInfo) {
        ComposeDialogHost.show(activity) { dialog ->
            UpdateAvailableDialog(
                update = update,
                onDismiss = dialog::dismiss,
                onDownload = {
                    dialog.dismiss()
                    runCatching {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                    }
                }
            )
        }
    }
}
