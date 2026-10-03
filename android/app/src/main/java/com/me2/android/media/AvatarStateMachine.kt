package com.me2.android.media

/** Estados del avatar: PRESENTACION → LOOP_NEUTRAL → REACCION → LOOP_NEUTRAL (+ conversación, despertador, sistema). */
enum class AvatarState { PRESENTACION, LOOP_NEUTRAL, REACCION, CONVERSACION, DESPERTADOR, SISTEMA }

/** Transiciones puras (testeables sin dispositivo). El fin de clip llega del evento real del reproductor (STATE_ENDED). */
object AvatarStateMachine {
    data class Transition(val next: AvatarState, val presentationCompleted: Boolean = false, val advancePresentation: Boolean = false)

    fun inputEnabled(state: AvatarState): Boolean = state != AvatarState.PRESENTACION

    fun onClipEnded(state: AvatarState, presentationHasNext: Boolean = false, alarmPending: Boolean = false): Transition = when (state) {
        AvatarState.PRESENTACION ->
            if (presentationHasNext) Transition(AvatarState.PRESENTACION, advancePresentation = true)
            else Transition(AvatarState.LOOP_NEUTRAL, presentationCompleted = true)
        AvatarState.REACCION, AvatarState.SISTEMA -> Transition(AvatarState.LOOP_NEUTRAL)
        // Mientras espera la respuesta del backend sigue en conversación (otra variante o la misma).
        AvatarState.CONVERSACION -> Transition(AvatarState.CONVERSACION)
        AvatarState.DESPERTADOR -> Transition(if (alarmPending) AvatarState.DESPERTADOR else AvatarState.LOOP_NEUTRAL)
        AvatarState.LOOP_NEUTRAL -> Transition(AvatarState.LOOP_NEUTRAL)
    }

    /** Al llegar la respuesta: si hay reacción válida → REACCION; si no, vuelve a neutral. */
    fun onReply(hasReaction: Boolean): AvatarState = if (hasReaction) AvatarState.REACCION else AvatarState.LOOP_NEUTRAL
}
