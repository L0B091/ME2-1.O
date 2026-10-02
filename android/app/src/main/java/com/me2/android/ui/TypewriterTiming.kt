package com.me2.android.ui

import kotlin.random.Random

/**
 * Ritmo "humano" del typewriter: base por letra + pausas largas tras coma/punto + jitter leve.
 * Puro (sin Android) para poder testearlo.
 */
object TypewriterTiming {
    const val BASE_MS = 34L
    const val JITTER_MS = 14L
    const val PAUSE_COMMA_MS = 180L
    const val PAUSE_PERIOD_MS = 380L
    const val PAUSE_NEWLINE_MS = 260L

    /** Demora antes de mostrar el carácter siguiente a [c]. */
    fun delayAfter(c: Char, random: Random): Long {
        val jitter = random.nextLong(-JITTER_MS / 2, JITTER_MS / 2 + 1)
        val pause = when (c) {
            ',', ';', ':' -> PAUSE_COMMA_MS
            '.', '!', '?', '…' -> PAUSE_PERIOD_MS
            '\n' -> PAUSE_NEWLINE_MS
            ' ' -> 6L
            else -> 0L
        }
        return (BASE_MS + jitter + pause).coerceAtLeast(10L)
    }

    enum class TapAction { FINISH, RESTART }

    /** Tocar la burbuja: si está escribiendo, completa; si ya terminó, reinicia la animación. */
    fun tapAction(running: Boolean): TapAction = if (running) TapAction.FINISH else TapAction.RESTART

    /** Animar al aparecer solo la primera vez (luego solo al tocar), y nunca burbujas del usuario. */
    fun animateOnBind(fromMe2: Boolean, wantsAnimation: Boolean, alreadyPlayed: Boolean): Boolean =
        fromMe2 && wantsAnimation && !alreadyPlayed

    /** Demoras para cada carácter del texto (índice i = demora tras mostrar text[i]). */
    fun schedule(text: String, random: Random = Random.Default): List<Long> = text.map { delayAfter(it, random) }
}
