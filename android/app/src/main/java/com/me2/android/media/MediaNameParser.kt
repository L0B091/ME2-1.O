package com.me2.android.media

import java.text.Normalizer

/**
 * Convierte una ruta relativa dentro de ME2_MEDIA en [MediaResource] usando solo la convención de nombres
 * (carpeta de categoría / subcarpeta opcional / CATEGORIA_INTENSIDAD_VARIANTE.ext). Sin listas de archivos.
 */
object MediaNameParser {
    private val VARIANTE = Regex("_(\\d{1,4})$")
    // Precompilada: normalizar() corre por cada archivo de la biblioteca (compilar el regex en cada llamada era ANR).
    private val MARCAS = Regex("\\p{M}+")
    private val EXT_VIDEO = setOf("mp4", "webm", "mkv", "3gp")
    private val EXT_GIF = setOf("gif", "webp")
    private val EXT_IMAGEN = setOf("png", "jpg", "jpeg")
    private val EXT_AUDIO = setOf("mp3", "ogg", "wav", "m4a", "aac")

    /** Prefijos de nombre que identifican categoría cuando el archivo no está dentro de una carpeta de categoría. */
    private val PREFIJOS = listOf(
        "PRESENTACION" to MediaCategoria.PRESENTACION,
        "NEUTRAL" to MediaCategoria.LOOP_NEUTRAL,
        "WIDGET" to MediaCategoria.WIDGET,
        "ALARMA" to MediaCategoria.DESPERTADOR,
        "DESPERTANDO" to MediaCategoria.DESPERTADOR,
        "POST_ALARMA" to MediaCategoria.DESPERTADOR,
        "PREMIUM" to MediaCategoria.PREMIUM
    )

    fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARCAS, "")
            .uppercase().replace('-', '_').replace(' ', '_')

    fun tipoDe(nombre: String): MediaTipo? = when (nombre.substringAfterLast('.', "").lowercase()) {
        in EXT_VIDEO -> MediaTipo.VIDEO
        in EXT_GIF -> MediaTipo.GIF
        in EXT_IMAGEN -> MediaTipo.IMAGEN
        in EXT_AUDIO -> MediaTipo.AUDIO
        else -> null
    }

    fun parse(rutaRelativa: String, origen: MediaResource.Origen = MediaResource.Origen.ASSETS): MediaResource? {
        val partes = rutaRelativa.trim('/').split('/').filter { it.isNotBlank() }
        if (partes.isEmpty() || partes.any { it.startsWith(".") || it.startsWith("_") }) return null
        val archivo = partes.last()
        val tipo = tipoDe(archivo) ?: return null
        val base = normalizar(archivo.substringBeforeLast('.'))
        val carpetas = partes.dropLast(1)

        var categoria = carpetas.firstOrNull()?.let(MediaCategoria::fromCarpeta)
        if (categoria == null) categoria = PREFIJOS.firstOrNull { base == it.first || base.startsWith(it.first + "_") }?.second
        if (categoria == null) return null

        val variante = VARIANTE.find(base)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val sinVariante = base.replace(VARIANTE, "")
        val tokens = sinVariante.split('_').filter { it.isNotBlank() }
        val intensidad = tokens.lastOrNull { t -> MediaIntensidad.entries.any { it.name == t } }?.let { MediaIntensidad.valueOf(it) }

        // Subcategoría: subcarpeta directa bajo la categoría; si no hay, se deriva del nombre.
        val subcarpeta = carpetas.getOrNull(1)?.let(::normalizar)
        val sub = (subcarpeta ?: subDesdeNombre(categoria, tokens.filterNot { it == intensidad?.name }))
            ?.ifBlank { null }

        val adulto = categoria == MediaCategoria.PREMIUM && (sub == "ADULTO" || carpetas.any { normalizar(it) == "ADULTO" })
        return MediaResource(
            id = base,
            archivo = partes.joinToString("/"),
            categoria = categoria,
            subcategoria = sub,
            intensidad = if (categoria == MediaCategoria.REACCION) intensidad ?: MediaIntensidad.NORMAL else intensidad,
            variante = variante,
            tipo = tipo,
            loop = categoria == MediaCategoria.LOOP_NEUTRAL || categoria == MediaCategoria.WIDGET,
            audio = tipo == MediaTipo.VIDEO || tipo == MediaTipo.AUDIO,
            premium = categoria == MediaCategoria.PREMIUM,
            adulto = adulto,
            widget = categoria == MediaCategoria.WIDGET,
            alarma = categoria == MediaCategoria.DESPERTADOR,
            origen = origen
        )
    }

    private fun subDesdeNombre(categoria: MediaCategoria, tokens: List<String>): String? {
        val t = tokens.toMutableList()
        when (categoria) {
            MediaCategoria.PRESENTACION, MediaCategoria.LOOP_NEUTRAL -> return null
            MediaCategoria.WIDGET, MediaCategoria.PREMIUM -> if (t.firstOrNull() == categoria.name) t.removeAt(0)
            MediaCategoria.DESPERTADOR -> {
                if (t.size > 1 && t.first() == "ALARMA") t.removeAt(0)
                if (t == listOf("FINAL")) return "ALARMA"
            }
            else -> Unit
        }
        if (categoria == MediaCategoria.PREMIUM && t.firstOrNull() == "ESPECIAL") return "ESPECIALES"
        return t.joinToString("_").ifBlank { null }
    }
}
