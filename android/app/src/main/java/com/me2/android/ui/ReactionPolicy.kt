package com.me2.android.ui

import com.me2.android.data.ChatMessage

/**
 * Reacción emoji del avatar sobre la burbuja del usuario: sutil y escasa.
 * - Solo emojis de la allowlist (igual que el backend).
 * - Tope en el cliente: nunca en mensajes seguidos; mínimo [MIN_TURNOS] mensajes del usuario desde la última reacción.
 * - Aparece poco después de enviar (mientras corre el clip de tipeo), nunca después de la respuesta.
 */
object ReactionPolicy {
    val PERMITIDOS = listOf("❤️", "😂", "😮", "😢", "👍", "😊", "🥰", "🔥", "🙏", "🎉", "🤔", "😅")
    const val MIN_TURNOS = 4
    /** Demora natural desde que llega la respuesta hasta que "aparece" la reacción (dentro del tipeo). */
    const val DELAY_MS = 650L
    const val POP_MS = 260L
    /** Mensajes cuya reacción ya animó (pop-in una sola vez, aunque se re-bindee). */
    internal val popped = mutableSetOf<Int>()

    fun validar(emoji: String?): String? {
        val t = emoji?.trim().orEmpty()
        if (t.isEmpty()) return null
        val sinVs = t.replace("\uFE0F", "")
        return PERMITIDOS.firstOrNull { it == t || it.replace("\uFE0F", "") == sinVs }
    }

    /** Emoji a aplicar al último mensaje del usuario, o null si no corresponde (allowlist + tope de frecuencia). */
    fun aceptar(conversation: List<ChatMessage>, emoji: String?): String? {
        val e = validar(emoji) ?: return null
        val users = conversation.filter { !it.fromMe2 }
        if (users.isEmpty() || users.last().reaction != null) return null
        val lastReacted = users.indexOfLast { it.reaction != null }
        if (lastReacted >= 0 && (users.size - 1 - lastReacted) < MIN_TURNOS) return null
        return e
    }

    /** Heurística offline: solo agradecimientos/risas/buenas noticias claras, con el mismo tope. */
    fun offline(conversation: List<ChatMessage>, text: String, random: Double): String? {
        val t = text.lowercase()
        val e = when {
            Regex("\\b(gracias|te agradezco)\\b").containsMatchIn(t) -> "❤️"
            Regex("(ja){2,}|jsjs|\\bxd\\b|😂").containsMatchIn(t) -> "😂"
            Regex("\\b(aprob[eé]|lo logr[eé]|consegu[ií]|gan[eé])\\b").containsMatchIn(t) -> "🎉"
            else -> null
        } ?: return null
        return if (random < 0.35) aceptar(conversation, e) else null
    }
}
