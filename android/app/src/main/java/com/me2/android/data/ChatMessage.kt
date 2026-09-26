package com.me2.android.data

data class ChatMessage(
    val text: String,
    val fromMe2: Boolean,
    val animateTypewriter: Boolean = false
)
