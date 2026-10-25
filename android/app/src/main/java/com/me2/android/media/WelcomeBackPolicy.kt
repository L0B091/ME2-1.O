package com.me2.android.media

import kotlin.random.Random

/**
 * "Volviste": cada vez que la app vuelve al frente o se reabre con sesión, ME2 saluda (04_SALUDOS/SALUDO) con una
 * frase variada sin repetir la anterior. Sin límite diario. No dispara en rotación/cambio de configuración, si estuvo
 * en segundo plano menos de [minAusenciaMs], ni si hay una respuesta en curso.
 */
object WelcomeBackPolicy {
    const val minAusenciaMs = 30_000L
    const val SUBCATEGORIA = "SALUDO"

    val frases = listOf(
        "¡Volviste!", "¡Estás de vuelta!", "¡Qué bueno verte de nuevo!", "¡Hola de nuevo! Te estaba esperando.",
        "¡Ey, volviste! ¿Cómo andás?", "¡Qué lindo que vuelvas!", "¡De vuelta por acá! ¿Qué contás?",
        "¡Hola otra vez! Te extrañaba un poquito."
    )

    fun debeSaludar(stoppedAtMs: Long, nowMs: Long, cambioConfig: Boolean, respuestaEnCurso: Boolean): Boolean =
        stoppedAtMs > 0L && !cambioConfig && !respuestaEnCurso && nowMs - stoppedAtMs >= minAusenciaMs

    fun elegirIndice(previo: Int, random: Random = Random.Default): Int {
        if (frases.size < 2) return 0
        var i: Int
        do { i = random.nextInt(frases.size) } while (i == previo)
        return i
    }

    fun frase(indice: Int, nombre: String?): String {
        val f = frases[indice]
        val n = nombre?.trim().orEmpty()
        return if (n.isEmpty() || indice % 2 == 1) f else f.replaceFirst("!", ", $n!")
    }
}
