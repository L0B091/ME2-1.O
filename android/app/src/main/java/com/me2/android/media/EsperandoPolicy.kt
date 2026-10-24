package com.me2.android.media

import kotlin.random.Random

/**
 * "Esperando respuesta del usuario" (03_CONVERSACION/ESPERANDO): tras terminar la respuesta de ME2 (y su clip de
 * reacción), un ESPERANDO al azar sin repetir el anterior; luego vuelve al loop neutral. Si la espera sigue, de vez en
 * cuando otro ESPERANDO (cada [primerReintentoMs]..+[jitterMs], creciendo, hasta [maxRepeticiones]).
 * PENSANDO es otra cosa: solo mientras ME2 procesa / espera al backend.
 */
object EsperandoPolicy {
    const val SUBCATEGORIA = "ESPERANDO"
    const val primerReintentoMs = 35_000L
    const val jitterMs = 20_000L
    const val maxRepeticiones = 4

    fun esEsperando(r: MediaResource): Boolean =
        r.categoria == MediaCategoria.CONVERSACION && r.subcategoria == SUBCATEGORIA

    fun candidatos(recursos: List<MediaResource>, permisos: MediaPermisos = MediaPermisos()): List<MediaResource> =
        recursos.filter { permisos.permite(it) && it.tipo == MediaTipo.VIDEO && !it.adulto && esEsperando(it) }

    /**
     * Elige el clip: la sugerencia del backend (p. ej. "ESPERANDO_002") si existe y no repite el anterior; si no,
     * uno al azar entre los disponibles evitando el anterior. null si no hay clips ESPERANDO.
     */
    fun elegir(
        recursos: List<MediaResource>,
        sugerido: String?,
        previousId: String?,
        permisos: MediaPermisos = MediaPermisos(),
        random: Random = Random.Default
    ): MediaResource? {
        val pool = candidatos(recursos, permisos)
        if (pool.isEmpty()) return null
        val sug = sugerido?.let(MediaNameParser::normalizar)
        pool.firstOrNull { it.id == sug && it.id != previousId }?.let { return it }
        return MediaSelector.elegir(pool, previousId, random)
    }

    /** ¿Se reproduce ESPERANDO al terminar la respuesta? No tras una DESPEDIDA (el usuario se fue) ni sin red. */
    fun alTerminarRespuesta(esperandoUsuario: Boolean, online: Boolean, ultimaSubcategoria: String?): Boolean =
        esperandoUsuario && online && ultimaSubcategoria?.let(MediaNameParser::normalizar) != "DESPEDIDA"

    /** Demora hasta el próximo ESPERANDO ocasional (null = no más en esta espera). */
    fun proximaEsperaMs(repeticion: Int, random: Random = Random.Default): Long? {
        if (repeticion >= maxRepeticiones) return null
        return primerReintentoMs * (repeticion + 1) + random.nextLong(jitterMs + 1)
    }
}
