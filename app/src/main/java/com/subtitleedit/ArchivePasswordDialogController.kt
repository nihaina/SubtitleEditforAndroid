package com.subtitleedit

import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.DialogArchivePasswordBinding
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
        val binding = DialogArchivePasswordBinding.inflate(activity.layoutInflater)
        binding.btnPasswordBook.setOnClickListener { showPasswordBook(binding.etArchivePassword) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("输入压缩包密码")
            .setMessage(archive.name)
            .setView(binding.root)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnCancelListener { onCancelled() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                onCancelled()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = binding.etArchivePassword.text?.toString().orEmpty()
                if (password.isEmpty()) binding.etArchivePassword.error = "请输入密码"
                else {
                    dialog.dismiss()
                    onPassword(password)
                }
            }
        }
        dialog.show()
    }

    fun showPasswordBook(target: EditText) {
        val vault = ArchivePasswordVault(activity)
        val passwords = runCatching(vault::getPasswords).getOrElse {
            showToast("无法读取密码本")
            return
        }
        val labels = passwords.mapIndexed { index, password ->
            "密码 ${index + 1}（${"•".repeat(password.length.coerceIn(1, 8))}）"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("密码本")
            .apply {
                if (labels.isEmpty()) setMessage("密码本为空，可保存当前输入的密码。")
                else setItems(labels) { _, which ->
                    target.setText(passwords[which])
                    target.setSelection(target.text?.length ?: 0)
                }
            }
            .setPositiveButton("保存当前密码") { _, _ ->
                val password = target.text?.toString().orEmpty()
                if (password.isEmpty()) showToast("请先输入密码")
                else runCatching { vault.savePassword(password) }
                    .onSuccess { showToast("密码已保存") }
                    .onFailure { showToast("密码保存失败") }
            }
            .apply {
                if (passwords.isNotEmpty()) {
                    setNeutralButton("清空密码本") { _, _ ->
                        runCatching(vault::clear)
                        showToast("密码本已清空")
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
