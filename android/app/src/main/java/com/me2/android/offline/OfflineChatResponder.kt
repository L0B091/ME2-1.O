package com.me2.android.offline

import com.me2.android.media.AudiovisualCue
import com.me2.android.media.OfflineAvatarPool

/**
 * INPUT del chat sin red: primero se persiste el mensaje del usuario como pendiente (todo queda guardado localmente;
 * la frase no lo menciona), después ME2 responde con una frase corta del banco offline "sin_red_input" (rotación
 * persistida). La pista audiovisual es siempre del pool neutral/escribiendo ([OfflineAvatarPool]).
 */
class OfflineChatResponder(
    private val pick: (String, Map<String, String?>) -> OfflinePhraseBank.Phrase?,
    private val markPending: () -> Unit
) {
    data class Reply(val text: String, val cue: AudiovisualCue, val phraseId: String?)

    fun respond(nombre: String?, fallbackText: String): Reply {
        runCatching(markPending)
        val phrase = runCatching { pick(OfflinePhraseBank.SIN_RED_INPUT, mapOf("nombre" to nombre)) }.getOrNull()
        val cue = phrase?.cue?.takeIf(OfflineAvatarPool::esCueNeutral) ?: FALLBACK_CUE
        return Reply(phrase?.text ?: fallbackText, cue, phrase?.id)
    }

    companion object {
        val FALLBACK_CUE = AudiovisualCue("LOOP_NEUTRAL")
    }
}
