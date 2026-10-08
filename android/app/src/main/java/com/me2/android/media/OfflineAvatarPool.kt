package com.me2.android.media

import kotlin.random.Random

/**
 * Contenedor del avatar SIN RED (regla de producto):
 *  - mientras ME2 tipea la frase offline: un clip "escribiendo mensaje" (CONVERSACION/ESCRIBIENDO*), si existe;
 *  - en reposo: variantes de [CARPETA_SIN_CONEXION] (loop propio de "sin conexión"), sin repetir la anterior, mientras
 *    dure la falta de red. Si esa carpeta está vacía, el respaldo es el loop de siempre: 01_LOOP_NEUTRAL.
 *  - al volver la red: de nuevo 01_LOOP_NEUTRAL ([reposo] con online = true).
 * Los clips "escribiendo" NUNCA se usan en reposo (ni offline ni online): solo con una respuesta en curso.
 * Sin reacciones ni otros estados offline.
 */
object OfflineAvatarPool {
    fun esEscribiendo(r: MediaResource): Boolean =
        r.categoria == MediaCategoria.CONVERSACION && r.subcategoria?.startsWith("ESCRIBIENDO") == true

    /** Carpeta de clips de reposo sin red (assets/ME2_MEDIA o filesDir/ME2_MEDIA). */
    const val CARPETA_SIN_CONEXION = "09_SIN_CONEXION"

    private fun deCategoria(recursos: List<MediaResource>, permisos: MediaPermisos, categoria: MediaCategoria) =
        recursos.filter { permisos.permite(it) && it.tipo == MediaTipo.VIDEO && !it.adulto && it.categoria == categoria }

    /** Clips de 09_SIN_CONEXION disponibles (vacío hasta que se agreguen). */
    fun sinConexion(recursos: List<MediaResource>, permisos: MediaPermisos = MediaPermisos()): List<MediaResource> =
        deCategoria(recursos, permisos, MediaCategoria.SIN_CONEXION)

    /** Pool de reposo offline: 09_SIN_CONEXION; si está vacía, 01_LOOP_NEUTRAL (nunca escribiendo). */
    fun pool(recursos: List<MediaResource>, permisos: MediaPermisos = MediaPermisos()): List<MediaResource> =
        sinConexion(recursos, permisos).ifEmpty { deCategoria(recursos, permisos, MediaCategoria.LOOP_NEUTRAL) }

    /** Loop de reposo del contenedor según la red: con red 01_LOOP_NEUTRAL; sin red [pool]. */
    fun reposo(recursos: List<MediaResource>, permisos: MediaPermisos = MediaPermisos(), online: Boolean): List<MediaResource> =
        if (online) deCategoria(recursos, permisos, MediaCategoria.LOOP_NEUTRAL) else pool(recursos, permisos)

    fun escribiendo(recursos: List<MediaResource>, permisos: MediaPermisos = MediaPermisos()): List<MediaResource> =
        recursos.filter { permisos.permite(it) && it.tipo == MediaTipo.VIDEO && !it.adulto && esEscribiendo(it) }

    /** Plan de una respuesta offline: clip de tipeo (opcional) y luego el loop de reposo sin red. */
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

    /** Próximo clip de reposo sin red (09_SIN_CONEXION o, si está vacía, neutral) sin repetir el anterior. */
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
