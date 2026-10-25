package com.me2.android.media

/**
 * Presentación (PRESENTACION_001: presentación v5 en un solo archivo) SOLO en el primer contacto. Una vez vista queda marcada en prefs
 * (commit) y en la memoria local (que viaja en el respaldo cifrado): nunca se repite, ni tras reiniciar ni tras
 * reinstalar + restaurar.
 */
object PresentationGate {
    /** Igual en Google y en demo/sin login ([isDemo] no la saltea: el primer contacto es el mismo). */
    @Suppress("UNUSED_PARAMETER")
    fun shouldPlay(isDemo: Boolean, prefsCompleted: Boolean, memoryCompletedAt: Long, conversationEmpty: Boolean): Boolean =
        !prefsCompleted && memoryCompletedAt <= 0L && conversationEmpty

    /** Ya hubo relación (flag en memoria restaurada o conversación previa): marcar como vista. */
    fun alreadySeen(memoryCompletedAt: Long, conversationEmpty: Boolean): Boolean = memoryCompletedAt > 0L || !conversationEmpty
}
