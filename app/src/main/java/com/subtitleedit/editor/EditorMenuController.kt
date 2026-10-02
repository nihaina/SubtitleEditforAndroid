package com.subtitleedit.editor

import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.R
import com.subtitleedit.EditorEditHistory

/** Owns editor menu presentation and maps menu commands to host actions. */
internal class EditorMenuController(
    private val activity: AppCompatActivity,
    private val onAction: (Action) -> Unit
) {
    enum class Action {
        UNDO, REDO, NEW, OPEN, SAVE, SAVE_AS, ENCODING, SOURCE_VIEW,
        MERGE, SEARCH, SELECT_ALL, SELECT_RANGE, SAVE_DRAFT, DRAFTS
    }

    fun prepare(
        menu: Menu,
        sourceMode: Boolean,
        selectedCount: Int,
        undo: EditorEditHistory.Operation?,
        redo: EditorEditHistory.Operation?,
        sourceTransitioning: Boolean
    ): Boolean {
        menu.clear()
        if (!sourceMode && selectedCount > 0) {
            addAction(menu, MENU_SELECT_ALL, "全选", R.drawable.ic_select_all)
            addAction(menu, MENU_SELECT_RANGE, "区间选择", R.drawable.ic_select_range)
            addCommand(menu, MENU_UNDO, R.string.menu_undo, undo != null)
            addCommand(menu, MENU_REDO, R.string.menu_redo, redo != null)
        } else {
            activity.menuInflater.inflate(R.menu.menu_editor, menu)
            menu.findItem(R.id.menu_undo)?.apply {
                isEnabled = undo != null
                contentDescription = undo?.description ?: activity.getString(R.string.menu_undo)
                tooltipText = contentDescription
            }
            menu.findItem(R.id.menu_redo)?.apply {
                isEnabled = redo != null
                contentDescription = redo?.description ?: activity.getString(R.string.menu_redo)
                tooltipText = contentDescription
            }
            menu.findItem(R.id.menu_source_view)?.isEnabled = !sourceTransitioning
        }
        return true
    }

    fun handle(item: MenuItem): Boolean {
        val action = when (item.itemId) {
            R.id.menu_undo, MENU_UNDO -> Action.UNDO
            R.id.menu_redo, MENU_REDO -> Action.REDO
            R.id.menu_new -> Action.NEW
            R.id.menu_open -> Action.OPEN
            R.id.menu_save -> Action.SAVE
            R.id.menu_save_as -> Action.SAVE_AS
            R.id.menu_encoding -> Action.ENCODING
            R.id.menu_source_view -> Action.SOURCE_VIEW
            R.id.menu_merge_subtitles -> Action.MERGE
            R.id.menu_search -> Action.SEARCH
            MENU_SELECT_ALL -> Action.SELECT_ALL
            MENU_SELECT_RANGE -> Action.SELECT_RANGE
            R.id.menu_save_draft -> Action.SAVE_DRAFT
            R.id.menu_drafts -> Action.DRAFTS
            else -> return false
        }
        onAction(action)
        return true
    }

    private fun addAction(menu: Menu, id: Int, title: String, icon: Int) {
        menu.add(Menu.NONE, id, Menu.NONE, title).apply {
            setIcon(icon)
            contentDescription = title
            tooltipText = title
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    private fun addCommand(menu: Menu, id: Int, titleRes: Int, enabled: Boolean) {
        menu.add(Menu.NONE, id, Menu.NONE, titleRes).apply {
            isEnabled = enabled
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }

    private companion object {
        const val MENU_SELECT_ALL = 0x20001
        const val MENU_SELECT_RANGE = 0x20002
        const val MENU_UNDO = 0x20003
        const val MENU_REDO = 0x20004
    }
}
