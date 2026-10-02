package com.me2.android.data

data class ChatMessage(
    val text: String,
    val fromMe2: Boolean,
    val animateTypewriter: Boolean = false,
    /** Reacción emoji del avatar (solo burbujas del usuario). */
    val reaction: String? = null,
    /** GIF inline: solo burbujas del avatar y solo en modo adulto (el usuario no envía GIFs). */
    val gifUrl: String? = null
)
