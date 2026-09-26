package com.me2.android.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.me2.android.R
import com.me2.android.data.ChatMessage
import com.me2.android.databinding.ItemChatMessageBinding

/**
 * ME2 bubbles always typewriter on bind (scroll into view) and on tap,
 * including older messages. User bubbles stay static.
 */
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

    override fun onViewRecycled(holder: ChatViewHolder) {
        holder.stopTypewriter()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    class ChatViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val typewriterHandler = Handler(Looper.getMainLooper())
        private var typewriterRunnable: Runnable? = null

        fun bind(message: ChatMessage) {
            stopTypewriter()

            val rootParams = binding.messageContainer.layoutParams as FrameLayout.LayoutParams
            val containerParams = binding.messageText.layoutParams as LinearLayout.LayoutParams

            if (message.fromMe2) {
                rootParams.gravity = Gravity.START
                binding.labelText.text = binding.root.context.getString(R.string.me2_data_stream)
                binding.labelText.visibility = View.VISIBLE
                binding.messageText.background =
                    ContextCompat.getDrawable(binding.root.context, R.drawable.bg_message_me2)
                binding.messageText.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.me2_text_primary)
                )
                containerParams.marginStart = 0
                binding.messageContainer.isClickable = true
                binding.messageContainer.isFocusable = true
                binding.messageText.isClickable = true
                val replay = View.OnClickListener { startTypewriter(message.text) }
                binding.messageContainer.setOnClickListener(replay)
                binding.messageText.setOnClickListener(replay)
                // Siempre: al entrar en pantalla (scroll/bind) y al tocar.
                startTypewriter(message.text)
            } else {
                rootParams.gravity = Gravity.END
                binding.labelText.visibility = View.GONE
                binding.messageText.background =
                    ContextCompat.getDrawable(binding.root.context, R.drawable.bg_message_user)
                binding.messageText.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.me2_text_secondary)
                )
                containerParams.marginStart = 0
                binding.messageContainer.setOnClickListener(null)
                binding.messageText.setOnClickListener(null)
                binding.messageContainer.isClickable = false
                binding.messageText.isClickable = false
                binding.messageText.text = message.text
            }

            binding.messageContainer.layoutParams = rootParams
            binding.messageText.layoutParams = containerParams
        }

        fun stopTypewriter() {
            typewriterRunnable?.let { typewriterHandler.removeCallbacks(it) }
            typewriterRunnable = null
        }

        private fun startTypewriter(fullText: String) {
            stopTypewriter()
            if (fullText.isEmpty()) {
                binding.messageText.text = ""
                return
            }
            binding.messageText.text = ""
            var index = 0
            val stepMs = 16L
            val runnable = object : Runnable {
                override fun run() {
                    if (index > fullText.length) return
                    binding.messageText.text = fullText.substring(0, index)
                    index += 1
                    if (index <= fullText.length) {
                        typewriterHandler.postDelayed(this, stepMs)
                    }
                }
            }
            typewriterRunnable = runnable
            typewriterHandler.post(runnable)
        }
    }
}
