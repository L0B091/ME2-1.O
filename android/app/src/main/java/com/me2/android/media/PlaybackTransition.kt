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

/**
 * Secuenciador de transiciones (lógica pura, testeable). Garantiza a lo sumo UN video + UNA foto en fundido:
 * mientras hay un fundido en curso (foto puesta, esperando primer cuadro o desvaneciéndose) ningún pedido nuevo
 * arranca; queda como pendiente "último gana" y se despacha al terminar el fundido. Así nunca se compone una
 * foto de una foto a medio fundir con otro clip (el "doble avatar" superpuesto).
 */
class TransitionSequencer<T> {
    sealed class Cmd<out T> {
        object None : Cmd<Nothing>()
        data class Start<T>(val clip: T) : Cmd<T>()
        data class Crossfade<T>(val clip: T) : Cmd<T>()
        data class Wait<T>(val clip: T) : Cmd<T>()
    }
    enum class Phase { IDLE, AWAITING_FRAME, FADING }

    var phase = Phase.IDLE; private set
    private val pending = PlaybackTransition.Pending<T>()
    val hasPending get() = pending.hasPending
    val overlayVisible get() = phase != Phase.IDLE

    fun request(clip: T, isPlaying: Boolean, remainingMs: Long?): Cmd<T> {
        if (phase != Phase.IDLE) { pending.offer(clip); return Cmd.None }
        pending.clear()
        return when (PlaybackTransition.decide(isPlaying, remainingMs)) {
            PlaybackTransition.Action.START_NOW -> Cmd.Start(clip)
            PlaybackTransition.Action.WAIT_FOR_END -> { pending.offer(clip); Cmd.Wait(clip) }
            PlaybackTransition.Action.CROSSFADE -> { phase = Phase.AWAITING_FRAME; Cmd.Crossfade(clip) }
        }
    }
    /** La foto no se pudo tomar: se arrancó directo. */
    fun crossfadeAborted() { phase = Phase.IDLE }
    /** Primer cuadro del clip nuevo (o respaldo por tiempo): empieza a desvanecerse la foto. */
    fun onFirstFrame(): Boolean { if (phase != Phase.AWAITING_FRAME) return false; phase = Phase.FADING; return true }
    /** Foto totalmente oculta. Despacha el último pedido que llegó durante el fundido. */
    fun onFadeEnd(isPlaying: Boolean, remainingMs: Long?): Cmd<T> {
        phase = Phase.IDLE
        val next = pending.take() ?: return Cmd.None
        return request(next, isPlaying, remainingMs)
    }
    /** Terminó el clip: si hay pedido en espera y no hay fundido, arranca ya (empiezan/terminan en la base). */
    fun onClipEnded(): Cmd<T> {
        if (phase != Phase.IDLE) return Cmd.None
        return pending.take()?.let { Cmd.Start(it) } ?: Cmd.None
    }
    fun reset() { phase = Phase.IDLE; pending.clear() }
}
