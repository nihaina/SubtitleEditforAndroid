package com.subtitleedit

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.model.FileBrowserOrder

/** Owns file-browser creation and sorting dialogs while delegating state changes. */
internal class FileBrowserDialogController(
    private val activity: AppCompatActivity,
    private val dp: (Int) -> Int,
    private val currentDirectory: () -> java.io.File?,
    private val onCreated: () -> Unit,
    private val onSortChanged: (FileSortField?, FileSortDirection?) -> Unit,
    private val onOpenSettings: () -> Unit
) {
    data class SortOptionRow(val container: LinearLayout, val radio: AppCompatRadioButton)

    fun showCreateMenu(anchor: android.view.View) {
        PopupMenu(activity, anchor).apply {
            menu.add("新建文件夹")
            menu.add("新建文件")
            setOnMenuItemClickListener { item ->
                if (item.title == "新建文件") showCreateFile() else showCreateFolder()
                true
            }
            show()
        }
    }

    private fun showCreateFolder() {
        val input = EditText(activity).apply {
            hint = "文件夹名称"
            setSingleLine(true)
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val dialog = AlertDialog.Builder(activity).setTitle("新建文件夹").setView(input)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val validation = FileBrowserOrder.validateName(input.text.toString())
                if (validation != null) { input.error = validation; return@setOnClickListener }
                val directory = currentDirectory() ?: return@setOnClickListener
                val target = java.io.File(directory, input.text.toString().trim())
                if (target.exists()) { input.error = "同名项目已存在"; return@setOnClickListener }
                if (!runCatching { target.mkdir() }.getOrDefault(false)) {
                    input.error = "创建失败，请检查目录写入权限"; return@setOnClickListener
                }
                dialog.dismiss(); onCreated()
            }
        }
        dialog.show()
    }

    private fun showCreateFile() {
        val nameInput = EditText(activity).apply { hint = "文件名"; setSingleLine(true) }
        val extensionInput = EditText(activity).apply { hint = "扩展名"; setText("txt"); setSingleLine(true) }
        val inputs = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(nameInput, LinearLayout.LayoutParams(-1, dp(56)))
            addView(extensionInput, LinearLayout.LayoutParams(-1, dp(56)))
        }
        val dialog = AlertDialog.Builder(activity).setTitle("新建文件").setView(inputs)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                nameInput.error = null; extensionInput.error = null
                FileBrowserOrder.validateName(nameInput.text.toString())?.let { nameInput.error = it; return@setOnClickListener }
                FileBrowserOrder.validateExtension(extensionInput.text.toString())?.let { extensionInput.error = it; return@setOnClickListener }
                val directory = currentDirectory() ?: return@setOnClickListener
                val name = FileBrowserOrder.composeFileName(nameInput.text.toString(), extensionInput.text.toString())
                val target = java.io.File(directory, name)
                if (target.exists()) { nameInput.error = "同名项目已存在"; return@setOnClickListener }
                if (!runCatching { target.createNewFile() }.getOrDefault(false)) {
                    nameInput.error = "创建失败，请检查目录写入权限"; return@setOnClickListener
                }
                dialog.dismiss(); onCreated()
            }
        }
        dialog.show(); nameInput.requestFocus()
    }

    fun showMoreMenu(anchor: android.view.View, onSort: () -> Unit) {
        PopupMenu(activity, anchor).apply {
            menu.add("排序")
            menu.add("设置")
            setOnMenuItemClickListener { item -> if (item.title == "排序") onSort() else onOpenSettings(); true }
            show()
        }
    }

    fun showSortDialog(currentField: () -> FileSortField, currentDirection: () -> FileSortDirection) {
        val fields = listOf("名称" to FileSortField.NAME, "类型" to FileSortField.TYPE, "大小" to FileSortField.SIZE, "日期" to FileSortField.DATE)
        val directions = listOf("升序" to FileSortDirection.ASCENDING, "降序" to FileSortDirection.DESCENDING)
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val fieldRows = mutableListOf<Pair<SortOptionRow, FileSortField>>(); val directionRows = mutableListOf<Pair<SortOptionRow, FileSortDirection>>()
        lateinit var refresh: () -> Unit
        fields.forEach { (label, value) -> val row = sortRow(label) { onSortChanged(value, null); refresh() }; fieldRows += row to value; container.addView(row.container) }
        directions.forEach { (label, value) -> val row = sortRow(label) { onSortChanged(null, value); refresh() }; directionRows += row to value; container.addView(row.container) }
        refresh = { fieldRows.forEach { setSelected(it.first, currentField() == it.second) }; directionRows.forEach { setSelected(it.first, currentDirection() == it.second) } }
        refresh()
        AlertDialog.Builder(activity).setTitle("排序").setView(container).setNegativeButton(R.string.cancel, null).show()
    }

    private fun sortRow(label: String, onClick: () -> Unit): SortOptionRow {
        val radio = AppCompatRadioButton(activity).apply {
            isClickable = false; isFocusable = false; layoutParams = LinearLayout.LayoutParams(dp(40), -1)
            buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ContextCompat.getColor(activity, R.color.primary), ContextCompat.getColor(activity, R.color.on_surface_variant)))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(8), 0); layoutParams = LinearLayout.LayoutParams(-1, dp(48))
            addView(TextView(activity).apply { text = label; textSize = 16f; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -1, 1f) }); addView(radio); setOnClickListener { onClick() }
        }
        return SortOptionRow(row, radio)
    }

    private fun setSelected(row: SortOptionRow, selected: Boolean) {
        row.container.setBackgroundColor(
            if (selected) ContextCompat.getColor(activity, R.color.primary_container)
            else android.graphics.Color.TRANSPARENT
        )
        row.radio.isChecked = selected
    }
}
