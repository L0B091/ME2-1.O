package com.me2.android.media

/**
 * Decisión de transición cuando llega un clip nuevo mientras otro suena. Nunca corte seco:
 * - queda poco del actual (<= [WAIT_THRESHOLD_MS]) → esperar a que termine y luego arrancar el nuevo;
 * - si no → fundido (~[CROSSFADE_MS]) desde el cuadro actual al nuevo (todos empiezan/terminan en la base).
 * Solo se conserva el último pedido: los intermedios se descartan.
 */
object PlaybackTransition {
    const val WAIT_THRESHOLD_MS = 1200L
    const val CROSSFADE_MS = 400L

    enum class Action { START_NOW, WAIT_FOR_END, CROSSFADE }

    /** [remainingMs] null = duración desconocida. */
    fun decide(isPlaying: Boolean, remainingMs: Long?): Action = when {
        !isPlaying -> Action.START_NOW
        remainingMs != null && remainingMs <= 0L -> Action.START_NOW
        remainingMs != null && remainingMs <= WAIT_THRESHOLD_MS -> Action.WAIT_FOR_END
        else -> Action.CROSSFADE
    }

    /** Pedido pendiente "último gana". */
    class Pending<T> {
        private var value: T? = null
        val hasPending: Boolean get() = value != null
        fun offer(v: T) { value = v }
        fun take(): T? = value.also { value = null }
        fun clear() { value = null }
    }
}
