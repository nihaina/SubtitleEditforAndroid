package com.subtitleedit.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.databinding.ItemChatAssistantBinding
import com.subtitleedit.databinding.ItemChatStatusBinding
import com.subtitleedit.databinding.ItemChatUserBinding

internal sealed class ChatUiMessage {
    data class User(val text: String, val messageId: String = "") : ChatUiMessage()
    data class Assistant(
        var text: String = "",
        var reasoning: String = "",
        var streaming: Boolean = true,
        var reasoningExpanded: Boolean = false,
        val messageId: String = ""
    ) : ChatUiMessage()
    data class Status(val text: String) : ChatUiMessage()
}

internal class ChatMessageAdapter(
    private val items: List<ChatUiMessage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ChatUiMessage.User -> USER
        is ChatUiMessage.Assistant -> ASSISTANT
        is ChatUiMessage.Status -> STATUS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        USER -> UserHolder(ItemChatUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        ASSISTANT -> AssistantHolder(ItemChatAssistantBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        else -> StatusHolder(ItemChatStatusBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is UserHolder -> holder.bind(items[position] as ChatUiMessage.User)
            is AssistantHolder -> holder.bind(items[position] as ChatUiMessage.Assistant)
            is StatusHolder -> holder.bind(items[position] as ChatUiMessage.Status)
        }
    }

    override fun getItemCount(): Int = items.size

    private class UserHolder(private val binding: ItemChatUserBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatUiMessage.User) { binding.tvMessage.text = message.text }
    }

    private class AssistantHolder(private val binding: ItemChatAssistantBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatUiMessage.Assistant) {
            val hasReasoning = message.reasoning.isNotBlank()
            binding.reasoningHeader.visibility = if (hasReasoning) View.VISIBLE else View.GONE
            binding.tvReasoning.visibility = if (hasReasoning && message.reasoningExpanded) {
                View.VISIBLE
            } else {
                View.GONE
            }
            binding.tvReasoning.text = message.reasoning
            binding.tvReasoningToggle.text = if (message.reasoningExpanded) "收起思考" else "展开思考"
            binding.reasoningHeader.setOnClickListener {
                message.reasoningExpanded = !message.reasoningExpanded
                val hasContents = message.reasoning.isNotBlank()
                binding.tvReasoning.visibility = if (hasContents && message.reasoningExpanded) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                binding.tvReasoningToggle.text = if (message.reasoningExpanded) "收起思考" else "展开思考"
            }
            binding.tvMessage.text = message.text.ifBlank { if (message.streaming) "正在生成..." else "" }
        }
    }

    private class StatusHolder(private val binding: ItemChatStatusBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatUiMessage.Status) { binding.tvStatus.text = message.text }
    }

    private companion object {
        const val USER = 0
        const val ASSISTANT = 1
        const val STATUS = 2
    }
}
