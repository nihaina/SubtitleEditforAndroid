package com.subtitleedit

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.subtitleedit.util.ArchivePasswordVault
import java.io.File

/** Owns archive password entry and password-book UI. */
internal class ArchivePasswordDialogController(
    private val activity: AppCompatActivity,
    private val showToast: (String) -> Unit
) {
    fun showPasswordDialog(
        archive: File,
        onPassword: (String) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        val vault = ArchivePasswordVault(activity)
        ComposeDialogHost.show(activity) { dialog ->
            var password by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            var passwordBook by remember { mutableStateOf<List<String>?>(null) }
            var settled by remember { mutableStateOf(false) }

            fun dismissAsCancelled() {
                if (settled) return
                settled = true
                dialog.dismiss()
                onCancelled()
            }

            fun openPasswordBook() {
                val result = runCatching(vault::getPasswords)
                result.onSuccess { passwordBook = it }.onFailure { showToast("无法读取密码本") }
            }

            ArchivePasswordDialog(
                archiveName = archive.name,
                password = password,
                error = error,
                passwords = passwordBook,
                onPasswordChange = {
                    password = it
                    error = null
                },
                onDismiss = ::dismissAsCancelled,
                onSubmit = {
                    if (password.isEmpty()) {
                        error = "请输入密码"
                    } else if (!settled) {
                        settled = true
                        dialog.dismiss()
                        onPassword(password)
                    }
                },
                onOpenPasswordBook = ::openPasswordBook,
                onSelectPassword = {
                    password = it
                    passwordBook = null
                },
                onSavePassword = {
                    passwordBook = savePassword(vault, password, passwordBook.orEmpty())
                },
                onClearPasswordBook = {
                    passwordBook = clearPasswordBook(vault, passwordBook.orEmpty())
                },
                onClosePasswordBook = { passwordBook = null }
            )
        }
    }

    private fun savePassword(
        vault: ArchivePasswordVault,
        password: String,
        previousPasswords: List<String>
    ): List<String> {
        if (password.isEmpty()) {
            showToast("请先输入密码")
            return previousPasswords
        }
        return runCatching {
            vault.savePassword(password)
            vault.getPasswords()
        }.onSuccess { showToast("密码已保存") }
            .onFailure { showToast("密码保存失败") }
            .getOrElse { previousPasswords }
    }

    private fun clearPasswordBook(
        vault: ArchivePasswordVault,
        previousPasswords: List<String>
    ): List<String> {
        return runCatching {
            vault.clear()
            emptyList<String>()
        }.onSuccess { showToast("密码本已清空") }
            .onFailure { showToast("密码本清空失败") }
            .getOrElse { previousPasswords }
    }
}
