package com.me2.android.media

import kotlin.math.abs
import kotlin.random.Random

/** Qué se pide al sistema audiovisual (categoría + intensidad), nunca un archivo concreto. */
data class MediaRequest(
    val categoria: MediaCategoria,
    val subcategoria: String? = null,
    val intensidad: MediaIntensidad? = null,
    val tipos: Set<MediaTipo> = setOf(MediaTipo.VIDEO),
    /** Categorías completas a probar (en orden) si no hay nada en la pedida. */
    val fallbacks: List<MediaCategoria> = listOf(MediaCategoria.LOOP_NEUTRAL),
    /** Si true, como último recurso cualquier video válido no adulto (salvo presentación). */
    val ultimoRecurso: Boolean = true
)

data class MediaPermisos(val premium: Boolean = false, val adulto: Boolean = false) {
    fun permite(r: MediaResource): Boolean =
        r.habilitado && (!r.premium || premium || adulto) && (!r.adulto || (adulto && premium))
}

data class MediaSelection(val recurso: MediaResource, val nivelFallback: Int) {
    val esFallback: Boolean get() = nivelFallback > 0
}

/**
 * Selector audiovisual: filtra por categoría/subcategoría/intensidad/permisos/habilitado, prioriza por `prioridad`,
 * evita repetir el recurso anterior si hay más de uno y cae a fallbacks válidos. Nunca inventa una referencia:
 * solo devuelve recursos presentes en la lista descubierta (o null si la lista no tiene nada reproducible).
 */
object MediaSelector {
    private val AMPLIABLES = setOf(
        MediaCategoria.CONVERSACION, MediaCategoria.WIDGET, MediaCategoria.TRANSICION,
        MediaCategoria.DESPERTADOR, MediaCategoria.SISTEMA, MediaCategoria.PREMIUM
    )

    fun select(
        recursos: List<MediaResource>,
        req: MediaRequest,
        permisos: MediaPermisos = MediaPermisos(),
        previousId: String? = null,
        random: Random = Random.Default
    ): MediaSelection? {
        // 00_PRESENTACION es exclusiva del primer contacto (secuenciaPresentacion): nunca en loop, reacción ni fallback.
        val sub = req.subcategoria?.let(MediaNameParser::normalizar)
        // "Escribiendo mensaje" solo mientras ME2 tipea una respuesta (pedido explícito): nunca por ampliación/fallback.
        val pideEscribiendo = req.categoria == MediaCategoria.CONVERSACION && sub?.startsWith("ESCRIBIENDO") == true
        val validos = recursos.filter { permisos.permite(it) && it.tipo in req.tipos && (it.categoria != MediaCategoria.PRESENTACION || req.categoria == MediaCategoria.PRESENTACION) &&
            (pideEscribiendo || !OfflineAvatarPool.esEscribiendo(it)) }
        val deCat = validos.filter { it.categoria == req.categoria }
        val niveles = mutableListOf<List<MediaResource>>()
        if (sub != null) {
            val mismaSub = deCat.filter { it.subcategoria == sub }
            if (req.intensidad != null) niveles += mismaSub.filter { it.intensidad == req.intensidad }
            niveles += masCercanas(mismaSub, req.intensidad)
            if (req.categoria in AMPLIABLES) niveles += deCat
        } else {
            if (req.intensidad != null) niveles += deCat.filter { it.intensidad == req.intensidad }
            niveles += deCat
        }
        req.fallbacks.filter { it != req.categoria }.forEach { cat -> niveles += validos.filter { it.categoria == cat } }
        // Último recurso: cualquier video válido no adulto que no sea la presentación (el contenedor nunca queda vacío).
        if (req.ultimoRecurso) niveles += validos.filter { it.tipo == MediaTipo.VIDEO && it.categoria != MediaCategoria.PRESENTACION && !it.adulto }

        niveles.forEachIndexed { nivel, pool ->
            elegir(pool, previousId, random)?.let { return MediaSelection(it, nivel) }
        }
        return null
    }

    /** Anti-repetición inmediata + prioridad: entre los de mayor prioridad, al azar sin repetir el anterior. */
    fun elegir(pool: List<MediaResource>, previousId: String?, random: Random = Random.Default): MediaResource? {
        if (pool.isEmpty()) return null
        val top = pool.maxOf { it.prioridad }
        val candidatos = pool.filter { it.prioridad == top }.distinctBy { it.id }
        if (candidatos.size == 1) {
            // Si el único de máxima prioridad es el anterior y hay otros, se usa otro para no repetir.
            val unico = candidatos.first()
            if (unico.id == previousId) pool.firstOrNull { it.id != previousId }?.let { return it }
            return unico
        }
        val sinRepetir = candidatos.filter { it.id != previousId }.ifEmpty { candidatos }
        return sinRepetir[random.nextInt(sinRepetir.size)]
    }

    private fun masCercanas(pool: List<MediaResource>, objetivo: MediaIntensidad?): List<MediaResource> {
        if (objetivo == null || pool.isEmpty()) return pool
        val dist = { r: MediaResource -> abs((r.intensidad ?: MediaIntensidad.NORMAL).ordinal - objetivo.ordinal) }
        val min = pool.minOf(dist)
        return pool.filter { dist(it) == min }
    }

    /** Presentación: todas las variantes habilitadas en orden de variante (primer arranque: PRESENTACION_001 → 002 → 003; clips provisionales). */
    fun secuenciaPresentacion(recursos: List<MediaResource>, permisos: MediaPermisos = MediaPermisos()): List<MediaResource> =
        recursos.filter { it.categoria == MediaCategoria.PRESENTACION && it.tipo == MediaTipo.VIDEO && permisos.permite(it) }
            .sortedWith(compareBy({ it.variante }, { it.id }))
}
