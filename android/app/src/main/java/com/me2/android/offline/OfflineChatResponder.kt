package com.me2.android.offline

import com.me2.android.media.AudiovisualCue

/**
 * INPUT del chat sin red: primero se persiste el mensaje del usuario como pendiente (no se pierde; no se reenvía
 * solo), después ME2 responde con una frase del banco offline "sin_red_input" (rotación persistida) y su pista
 * audiovisual para el MediaSelector (con fallback hasta neutral).
 */
class OfflineChatResponder(
    private val pick: (String, Map<String, String?>) -> OfflinePhraseBank.Phrase?,
    private val markPending: () -> Unit
) {
    data class Reply(val text: String, val cue: AudiovisualCue, val phraseId: String?)

    fun respond(nombre: String?, fallbackText: String): Reply {
        runCatching(markPending)
        val phrase = runCatching { pick(OfflinePhraseBank.SIN_RED_INPUT, mapOf("nombre" to nombre)) }.getOrNull()
        return Reply(phrase?.text ?: fallbackText, phrase?.cue ?: FALLBACK_CUE, phrase?.id)
    }

    companion object {
        val FALLBACK_CUE = AudiovisualCue("SISTEMA", "SIN_CONEXION", "NORMAL")
    }
}
