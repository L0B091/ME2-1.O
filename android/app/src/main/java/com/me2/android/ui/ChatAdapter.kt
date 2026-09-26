package com.me2.android.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.me2.android.R
import com.me2.android.data.ChatMessage
import com.me2.android.databinding.ItemChatMessageBinding

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {
    private val items = mutableListOf<ChatMessage>()

    fun submitList(messages: List<ChatMessage>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ChatViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.messageText.text = message.text

            val rootParams = binding.messageContainer.layoutParams as FrameLayout.LayoutParams
            val containerParams = binding.messageText.layoutParams as LinearLayout.LayoutParams

            if (message.fromMe2) {
                rootParams.gravity = Gravity.START
                binding.labelText.text = binding.root.context.getString(R.string.me2_data_stream)
                binding.labelText.visibility = android.view.View.VISIBLE
                binding.messageText.background = ContextCompat.getDrawable(binding.root.context, R.drawable.bg_message_me2)
                binding.messageText.setTextColor(ContextCompat.getColor(binding.root.context, R.color.me2_text_primary))
                containerParams.marginStart = 0
            } else {
                rootParams.gravity = Gravity.END
                binding.labelText.visibility = android.view.View.GONE
                binding.messageText.background = ContextCompat.getDrawable(binding.root.context, R.drawable.bg_message_user)
                binding.messageText.setTextColor(ContextCompat.getColor(binding.root.context, R.color.me2_text_secondary))
                containerParams.marginStart = 0
            }

            binding.messageContainer.layoutParams = rootParams
            binding.messageText.layoutParams = containerParams
        }
    }
}
