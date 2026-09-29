package com.subtitleedit.editor

import android.content.Context
import com.subtitleedit.EditorEditHistory
import com.subtitleedit.R

/** Builds the editor menu model consumed directly by the Compose toolbar. */
internal class EditorMenuController(
    private val context: Context,
    private val onAction: (Action) -> Unit
) {
    enum class Action {
        UNDO, REDO, NEW, OPEN, SAVE, SAVE_AS, ENCODING, SOURCE_VIEW,
        MERGE, SEARCH, SELECT_ALL, SELECT_RANGE, SAVE_DRAFT, DRAFTS
    }

    fun build(
        sourceMode: Boolean,
        selectedCount: Int,
        undo: EditorEditHistory.Operation?,
        redo: EditorEditHistory.Operation?,
        sourceTransitioning: Boolean
    ): List<EditorMenuGroupModel> {
        if (!sourceMode && selectedCount > 0) {
            return listOf(
                EditorMenuGroupModel(
                    title = null,
                    actions = listOf(
                        action(Action.SELECT_ALL, "全选"),
                        action(Action.SELECT_RANGE, "区间选择"),
                        action(Action.UNDO, context.getString(R.string.menu_undo), undo != null),
                        action(Action.REDO, context.getString(R.string.menu_redo), redo != null)
                    )
                )
            )
        }

        return listOf(
            EditorMenuGroupModel(
                title = context.getString(R.string.menu_file),
                actions = listOf(
                    action(Action.NEW, R.string.menu_new),
                    action(Action.OPEN, R.string.menu_open),
                    action(Action.SAVE, R.string.menu_save),
                    action(Action.SAVE_AS, R.string.menu_save_as),
                    action(Action.ENCODING, R.string.menu_encoding)
                )
            ),
            EditorMenuGroupModel(
                title = context.getString(R.string.menu_edit),
                actions = listOf(
                    action(Action.UNDO, R.string.menu_undo, undo != null),
                    action(Action.REDO, R.string.menu_redo, redo != null),
                    action(Action.SEARCH, R.string.menu_search),
                    action(Action.SOURCE_VIEW, R.string.menu_source_view, !sourceTransitioning),
                    action(Action.MERGE, R.string.menu_merge_subtitles),
                    action(Action.SAVE_DRAFT, R.string.menu_save_draft),
                    action(Action.DRAFTS, R.string.menu_drafts)
                )
            )
        )
    }

    fun handle(action: Action): Boolean {
        onAction(action)
        return true
    }

    private fun action(action: Action, titleRes: Int, enabled: Boolean = true) =
        EditorMenuActionModel(action, context.getString(titleRes), enabled)

    private fun action(action: Action, title: String, enabled: Boolean = true) =
        EditorMenuActionModel(action, title, enabled)
}

internal data class EditorMenuGroupModel(
    val title: String?,
    val actions: List<EditorMenuActionModel>
)

internal data class EditorMenuActionModel(
    val action: EditorMenuController.Action,
    val title: String,
    val enabled: Boolean = true
)
