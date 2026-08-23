package com.subtitleedit.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.R

data class TranslationPreviewItem(
    val entryPosition: Int,
    val originalText: String,
    var translatedText: String,
    var apply: Boolean = true,
    var suspectedProblem: Boolean = false
)

class TranslationPreviewAdapter(
    private val items: List<TranslationPreviewItem>,
    private val onEditRequested: (TranslationPreviewItem, () -> Unit) -> Unit
) : RecyclerView.Adapter<TranslationPreviewAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_translation_preview, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position], position)

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(parent: android.view.View) : RecyclerView.ViewHolder(parent) {
        private val checkApply: CheckBox = parent.findViewById(R.id.checkApplyTranslation)
        private val tvOriginal: TextView = parent.findViewById(R.id.tvOriginalTranslation)
        private val tvTranslated: TextView = parent.findViewById(R.id.tvTranslatedText)
        private var item: TranslationPreviewItem? = null

        init {
            checkApply.setOnCheckedChangeListener { _, checked -> item?.apply = checked }
            tvTranslated.setOnClickListener {
                val previewItem = item ?: return@setOnClickListener
                onEditRequested(previewItem) {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
                }
            }
        }

        fun bind(previewItem: TranslationPreviewItem, position: Int) {
            item = null
            checkApply.isChecked = previewItem.apply
            tvOriginal.text = "${position + 1}. ${previewItem.originalText}"
            tvTranslated.text = previewItem.translatedText
            item = previewItem
        }
    }
}
