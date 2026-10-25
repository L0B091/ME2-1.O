package com.me2.android.media

/**
 * Toda respuesta de ME2 pasa primero por el clip de tipeo (03_CONVERSACION/ESCRIBIENDO_*): el texto aparece recién
 * cuando el clip termina (o tras [timeoutMs] como respaldo). Respuesta corta → clip corto; larga → clip largo.
 */
object TypingGate {
    const val RAPIDO = "ESCRIBIENDO_RAPIDO"
    const val MEDIO = "ESCRIBIENDO_MEDIO"
    const val LENTO = "ESCRIBIENDO_LENTO"
    const val timeoutMs = 20_000L

    fun subcategoriaPara(reply: String): String = when {
        reply.length <= 80 -> RAPIDO
        reply.length <= 240 -> MEDIO
        else -> LENTO
    }

    /** Variante del backend si es válida; si no, la local por largo. */
    fun elegir(reply: String, backend: String?): String =
        backend?.takeIf { it.startsWith("ESCRIBIENDO") } ?: subcategoriaPara(reply)

    /** Respuesta retenida hasta que termine el tipeo. Se entrega una sola vez (clip terminado o timeout). */
    class Pending {
        private var action: (() -> Unit)? = null
        val activo: Boolean get() = action != null
        fun retener(a: () -> Unit) { flush(); action = a }
        fun flush(): Boolean { val a = action ?: return false; action = null; a(); return true }
    }
}
