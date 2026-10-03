package com.me2.android.offline

import com.me2.android.data.LocalConversationEntry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cola de mensajes escritos sin red. La cola NO vive en RAM: son las entradas `pendiente` de la conversación local
 * (Room cifrado), así que sobrevive a reinicios. Al volver la red se manda TODO lo pendiente en un único /chat
 * (camino normal del orquestador, en orden cronológico, separado por saltos de línea) → el modelo lo ve junto y
 * responde una sola vez. Solo después de un 200 se desmarcan exactamente esos mensajes (por timestamp); si falla,
 * quedan pendientes para el próximo reintento. Un candado de proceso ([PendingChatGate]) evita dos envíos en
 * paralelo cuando la red parpadea (onAvailable/onCapabilitiesChanged repetidos). Se llama fuera del hilo principal.
 */
class PendingChatFlusher<R>(
    private val loadPending: () -> List<LocalConversationEntry>,
    private val send: (message: String) -> R,
    private val markSent: (timestamps: Set<Long>) -> Unit,
    private val onSending: (Batch) -> Unit = {},
    private val gate: AtomicBoolean = PendingChatGate.inFlight
) {
    data class Batch(val message: String, val timestamps: Set<Long>, val count: Int)

    sealed class Outcome<out R> {
        /** Ya hay un envío de pendientes en curso: no se duplica. */
        object Busy : Outcome<Nothing>()
        object NothingPending : Outcome<Nothing>()
        data class Sent<R>(val batch: Batch, val result: R) : Outcome<R>()
        data class Failed(val batch: Batch, val error: Throwable) : Outcome<Nothing>()
    }

    fun flush(): Outcome<R> {
        if (!gate.compareAndSet(false, true)) return Outcome.Busy
        try {
            val batch = batchOf(runCatching(loadPending).getOrDefault(emptyList())) ?: return Outcome.NothingPending
            runCatching { onSending(batch) }
            val result = try {
                send(batch.message)
            } catch (error: Throwable) {
                return Outcome.Failed(batch, error)
            }
            // Antes de soltar el candado: un segundo flush ya no los ve como pendientes.
            markSent(batch.timestamps)
            return Outcome.Sent(batch, result)
        } finally {
            gate.set(false)
        }
    }

    companion object {
        /** Pendientes del usuario en orden cronológico → un solo mensaje (uno por línea). Null si no hay nada. */
        fun batchOf(entries: List<LocalConversationEntry>): Batch? {
            val pending = entries.filter { it.role == "user" && it.pending && it.text.isNotBlank() }.sortedBy { it.timestamp }
            if (pending.isEmpty()) return null
            return Batch(
                message = pending.joinToString("\n") { it.text.trim() },
                timestamps = pending.map { it.timestamp }.toSet(),
                count = pending.size
            )
        }
    }
}

/** Candado de proceso compartido por todas las instancias de MainActivity. */
object PendingChatGate {
    val inFlight = AtomicBoolean(false)
}
