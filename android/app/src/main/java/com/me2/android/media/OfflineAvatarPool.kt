package com.me2.android.media

import kotlin.random.Random

/**
 * Contenedor del avatar SIN RED (regla de producto):
 *  - mientras ME2 tipea la frase offline: un clip "escribiendo mensaje" (CONVERSACION/ESCRIBIENDO*), si existe;
 *  - después: solo variantes de 01_LOOP_NEUTRAL, sin repetir la anterior, hasta el próximo input del usuario.
 * Los clips "escribiendo" NUNCA se usan en reposo (ni offline ni online): solo con una respuesta en curso.
 * Sin reacciones ni otros estados offline.
 */
object OfflineAvatarPool {
    fun esEscribiendo(r: MediaResource): Boolean =
        r.categoria == MediaCategoria.CONVERSACION && r.subcategoria?.startsWith("ESCRIBIENDO") == true

    /** Pool de reposo offline: solo LOOP_NEUTRAL (nunca escribiendo). */
    fun pool(recursos: List<MediaResource>, permisos: MediaPermisos = MediaPermisos()): List<MediaResource> =
        recursos.filter { permisos.permite(it) && it.tipo == MediaTipo.VIDEO && !it.adulto && it.categoria == MediaCategoria.LOOP_NEUTRAL }

    fun escribiendo(recursos: List<MediaResource>, permisos: MediaPermisos = MediaPermisos()): List<MediaResource> =
        recursos.filter { permisos.permite(it) && it.tipo == MediaTipo.VIDEO && !it.adulto && esEscribiendo(it) }

    /** Plan de una respuesta offline: clip de tipeo (opcional) y luego neutral. */
    data class Plan(val tipeo: MediaResource?, val neutral: MediaResource?)

    fun planRespuesta(
        recursos: List<MediaResource>,
        permisos: MediaPermisos = MediaPermisos(),
        previousId: String? = null,
        random: Random = Random.Default
    ): Plan {
        val tipeo = MediaSelector.elegir(escribiendo(recursos, permisos), previousId, random)
        val neutral = siguiente(recursos, permisos, tipeo?.id ?: previousId, random)
        return Plan(tipeo, neutral)
    }

    /** Próximo clip de reposo (neutral) sin repetir el anterior. */
    fun siguiente(
        recursos: List<MediaResource>,
        permisos: MediaPermisos = MediaPermisos(),
        previousId: String? = null,
        random: Random = Random.Default
    ): MediaResource? = MediaSelector.elegir(pool(recursos, permisos), previousId, random)

    /** La pista de una frase sin red solo vale si es neutral o "escribiendo". */
    fun esCueNeutral(cue: AudiovisualCue?): Boolean {
        val req = AvatarCueMapper.fromCue(cue) ?: return false
        return req.categoria == MediaCategoria.LOOP_NEUTRAL ||
            (req.categoria == MediaCategoria.CONVERSACION && req.subcategoria?.let(MediaNameParser::normalizar)?.startsWith("ESCRIBIENDO") == true)
    }
}
