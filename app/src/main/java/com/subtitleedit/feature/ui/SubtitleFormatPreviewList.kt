package com.subtitleedit.feature.ui

import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.ui.settings.settingsPrimaryTextColor
import com.subtitleedit.ui.settings.settingsSecondaryTextColor
import com.subtitleedit.view.DraggableRecyclerView

@Composable
internal fun SubtitleFormatPreviewList(
    items: List<SubtitleFormatEditorRow>,
    onSelectionChanged: (Int, Boolean) -> Unit,
    onEdit: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val compositionContext = rememberCompositionContext()
    val currentOnSelectionChanged = rememberUpdatedState(onSelectionChanged)
    val currentOnEdit = rememberUpdatedState(onEdit)
    val previewAdapter = remember(compositionContext) {
        PreviewAdapter(
            compositionContext,
            onSelectionChanged = { index, selected -> currentOnSelectionChanged.value(index, selected) },
            onEdit = { currentOnEdit.value(it) }
        )
    }
    val bottomPadding = with(LocalDensity.current) { 8.dp.roundToPx() }
    AndroidView(
        // Native touch scrolling must stay below the Compose file-info header.
        modifier = modifier.fillMaxWidth().clipToBounds(),
        factory = { context ->
            DraggableRecyclerView(context).apply {
                id = android.R.id.list
                layoutManager = LinearLayoutManager(context)
                adapter = previewAdapter
                itemAnimator = null
                isVerticalScrollBarEnabled = false
                clipToPadding = false
            }
        },
        update = { recyclerView ->
            recyclerView.setPadding(0, 0, 0, bottomPadding)
            previewAdapter.submitItems(items) {
                recyclerView.doOnLayout { recyclerView.showDragThumb() }
            }
        },
        onRelease = { it.adapter = null }
    )
}

private class PreviewAdapter(
    private val compositionContext: CompositionContext,
    private val onSelectionChanged: (Int, Boolean) -> Unit,
    private val onEdit: (Int) -> Unit
) : ListAdapter<IndexedValue<SubtitleFormatEditorRow>, PreviewAdapter.Holder>(
    object : DiffUtil.ItemCallback<IndexedValue<SubtitleFormatEditorRow>>() {
        override fun areItemsTheSame(
            oldItem: IndexedValue<SubtitleFormatEditorRow>,
            newItem: IndexedValue<SubtitleFormatEditorRow>
        ) = oldItem.value.entryPosition == newItem.value.entryPosition

        // The index is part of the row so deletions also refresh the displayed line numbers.
        override fun areContentsTheSame(
            oldItem: IndexedValue<SubtitleFormatEditorRow>,
            newItem: IndexedValue<SubtitleFormatEditorRow>
        ) = oldItem == newItem
    }
) {
    private var submittedItems: List<SubtitleFormatEditorRow>? = null

    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    fun submitItems(items: List<SubtitleFormatEditorRow>, onCommitted: () -> Unit) {
        if (submittedItems === items) return
        submittedItems = items
        submitList(items.withIndex().toList(), onCommitted)
    }

    override fun getItemId(position: Int): Long = getItem(position).value.entryPosition.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ComposeView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setParentCompositionContext(compositionContext)
        })

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.row = getItem(position)
    }

    inner class Holder(view: ComposeView) : RecyclerView.ViewHolder(view) {
        var row by mutableStateOf<IndexedValue<SubtitleFormatEditorRow>?>(null)

        init {
            view.setContent {
                row?.let { (index, item) ->
                    PreviewRow(
                        index = index,
                        item = item,
                        onSelectionChanged = { selected ->
                            val position = bindingAdapterPosition
                            if (position != RecyclerView.NO_POSITION) onSelectionChanged(position, selected)
                        },
                        onEdit = {
                            val position = bindingAdapterPosition
                            if (position != RecyclerView.NO_POSITION) onEdit(position)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(
    index: Int,
    item: SubtitleFormatEditorRow,
    onSelectionChanged: (Boolean) -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${index + 1}.",
            modifier = Modifier.padding(end = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = settingsSecondaryTextColor(),
            maxLines = 1
        )
        Checkbox(
            checked = item.selected,
            onCheckedChange = onSelectionChanged,
            modifier = Modifier.size(36.dp)
        )
        Text(
            text = item.text,
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 36.dp)
                .clickable(onClick = onEdit)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = settingsPrimaryTextColor()
        )
    }
}
