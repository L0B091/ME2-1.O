package com.me2.android.ui

import android.os.Handler
import android.os.Looper
import android.text.util.Linkify
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.me2.android.R
import com.me2.android.data.ChatMessage
import com.me2.android.databinding.ItemChatMessageBinding
import kotlin.random.Random

/**
 * Burbujas del avatar: typewriter letra por letra a ritmo humano (pausas en comas/puntos + jitter)
 * la primera vez que aparecen; al terminar, tocar la burbuja reinicia la animación (también en mensajes viejos).
 * Burbujas del usuario: estáticas. Links (p. ej. pago) clickeables.
 */
class ChatAdapter(
    /** Carga un GIF (autenticado) en el ImageView; solo se usa para burbujas del avatar. */
    private val gifLoader: ((url: String, target: ImageView) -> Unit)? = null
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {
    private val items = mutableListOf<ChatMessage>()
    /** Mensajes ya animados (no se re-animan al hacer scroll; solo al tocarlos). */
    private val played = mutableSetOf<String>()

    private fun keyOf(position: Int, message: ChatMessage) = "$position:${message.text.hashCode()}"

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
        return ChatViewHolder(binding, gifLoader)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = items[position]
        val key = keyOf(position, message)
        val animate = TypewriterTiming.animateOnBind(message.fromMe2, message.animateTypewriter, key in played)
        if (animate) played.add(key)
        holder.bind(message, animate)
    }

    override fun onViewRecycled(holder: ChatViewHolder) {
        holder.stopTypewriter()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    class ChatViewHolder(
        private val binding: ItemChatMessageBinding,
        private val gifLoader: ((String, ImageView) -> Unit)? = null
    ) : RecyclerView.ViewHolder(binding.root) {
        private val typewriterHandler = Handler(Looper.getMainLooper())
        private var typewriterRunnable: Runnable? = null

        private var running = false

        fun bind(message: ChatMessage, animate: Boolean = false) {
            stopTypewriter()
            binding.messageText.autoLinkMask = Linkify.WEB_URLS
            binding.messageText.linksClickable = true
            val reaction = ChatMediaRouting.reactionFor(message.fromMe2, message.reaction)
            binding.reactionPill.text = reaction.orEmpty()
            binding.reactionPill.visibility = if (reaction == null) View.GONE else View.VISIBLE
            val gif = message.gifUrl?.takeIf { message.fromMe2 }
            binding.gifView.setImageDrawable(null)
            binding.gifView.visibility = if (gif == null) View.GONE else View.VISIBLE
            binding.messageText.visibility = if (gif != null && message.text.isBlank()) View.GONE else View.VISIBLE
            if (gif != null) gifLoader?.invoke(gif, binding.gifView)

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
                // Tocar: si está escribiendo, completa; si terminó, reinicia la animación.
                val replay = View.OnClickListener {
                    when (TypewriterTiming.tapAction(running)) {
                        TypewriterTiming.TapAction.FINISH -> finish(message.text)
                        TypewriterTiming.TapAction.RESTART -> startTypewriter(message.text)
                    }
                }
                binding.messageContainer.setOnClickListener(replay)
                binding.messageText.setOnClickListener(replay)
                if (animate) startTypewriter(message.text) else binding.messageText.text = message.text
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
            running = false
        }

        private fun finish(fullText: String) {
            stopTypewriter()
            binding.messageText.text = fullText
        }

        private fun startTypewriter(fullText: String) {
            stopTypewriter()
            binding.messageText.text = ""
            if (fullText.isEmpty()) return
            val delays = TypewriterTiming.schedule(fullText, Random(System.nanoTime()))
            var index = 0
            running = true
            val runnable = object : Runnable {
                override fun run() {
                    index += 1
                    binding.messageText.text = fullText.substring(0, index)
                    if (index < fullText.length) {
                        typewriterHandler.postDelayed(this, delays[index - 1])
                    } else {
                        running = false
                        typewriterRunnable = null
                    }
                }
            }
            typewriterRunnable = runnable
            typewriterHandler.post(runnable)
        }
    }
}
