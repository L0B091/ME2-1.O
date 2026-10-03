package com.me2.android.media

/**
 * Presentación (00_PRESENTACION, los 3 HOLA) SOLO en el primer contacto. Una vez vista queda marcada en prefs
 * (commit) y en la memoria local (que viaja en el respaldo cifrado): nunca se repite, ni tras reiniciar ni tras
 * reinstalar + restaurar.
 */
object PresentationGate {
    fun shouldPlay(isDemo: Boolean, prefsCompleted: Boolean, memoryCompletedAt: Long, conversationEmpty: Boolean): Boolean =
        !isDemo && !prefsCompleted && memoryCompletedAt <= 0L && conversationEmpty

    /** Ya hubo relación (flag en memoria restaurada o conversación previa): marcar como vista. */
    fun alreadySeen(memoryCompletedAt: Long, conversationEmpty: Boolean): Boolean = memoryCompletedAt > 0L || !conversationEmpty
}
