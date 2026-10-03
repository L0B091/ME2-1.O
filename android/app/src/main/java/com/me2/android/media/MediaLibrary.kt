package com.me2.android.media

import android.content.Context
import android.net.Uri
import android.util.Log
import com.me2.android.gallery.GalleryClip
import org.json.JSONObject
import java.io.File

/**
 * Descubrimiento dinámico de la biblioteca audiovisual V1:
 * - empaquetado: assets/ME2_MEDIA/<NN_CATEGORIA>/<SUBCATEGORIA>/<NOMBRE>_<VARIANTE>.ext
 * - drop-in en runtime: filesDir/ME2_MEDIA/ (misma estructura; mismo id reemplaza al empaquetado)
 * - overrides opcionales: ME2_MEDIA/metadata.json → {"recursos": {"ID": {"habilitado": false, "prioridad": 2, ...}}}
 * Agregar un clip = agregar el archivo con la nomenclatura; no hay listas en el código.
 */
class MediaLibrary(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var cache: List<MediaResource>? = null

    fun recursos(): List<MediaResource> = cache ?: descubrir().also { cache = it }

    fun refrescar(): List<MediaResource> = descubrir().also { cache = it }

    fun filesRoot(): File = File(appContext.filesDir, ROOT)

    private fun descubrir(): List<MediaResource> {
        val assets = listarAssets(ROOT).mapNotNull { MediaNameParser.parse(it, MediaResource.Origen.ASSETS) }
        val files = listarFiles(filesRoot()).mapNotNull { MediaNameParser.parse(it, MediaResource.Origen.FILES_DIR) }
        val merged = (assets.associateBy { it.id } + files.associateBy { it.id }).values.toList()
        val overrides = cargarOverrides()
        return merged.map { aplicarOverride(it, overrides.optJSONObject(it.id)) }.sortedBy { it.archivo }
    }

    private fun listarAssets(path: String): List<String> {
        val hijos = runCatching { appContext.assets.list(path)?.toList().orEmpty() }.getOrElse {
            Log.w(TAG, "assets.list($path): ${it.javaClass.simpleName}"); emptyList()
        }
        return hijos.flatMap { nombre ->
            val full = "$path/$nombre"
            if (MediaNameParser.tipoDe(nombre) != null) listOf(full.removePrefix("$ROOT/")) else listarAssets(full)
        }
    }

    private fun listarFiles(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && MediaNameParser.tipoDe(it.name) != null }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }

    private fun cargarOverrides(): JSONObject {
        val texto = runCatching { File(filesRoot(), METADATA).takeIf { it.isFile }?.readText() }.getOrNull()
            ?: runCatching { appContext.assets.open("$ROOT/$METADATA").bufferedReader().use { it.readText() } }.getOrNull()
            ?: return JSONObject()
        return runCatching { JSONObject(texto).optJSONObject("recursos") ?: JSONObject() }.getOrDefault(JSONObject())
    }

    fun uri(r: MediaResource): Uri = when (r.origen) {
        MediaResource.Origen.ASSETS -> Uri.parse("asset:///$ROOT/${r.archivo}")
        MediaResource.Origen.FILES_DIR -> Uri.fromFile(File(filesRoot(), r.archivo))
    }

    /** Adaptador al handle de reproducción existente (ExoPlayer en MainActivity / frames del widget). */
    fun toClip(r: MediaResource): GalleryClip = GalleryClip(
        id = "me2:${r.archivo}",
        mood = listOfNotNull(r.categoria.name, r.subcategoria, r.intensidad?.name).joinToString("/"),
        displayName = r.id,
        uri = uri(r),
        source = if (r.origen == MediaResource.Origen.ASSETS) GalleryClip.Source.ASSETS else GalleryClip.Source.FILES_DIR,
        carriesVoice = r.categoria == MediaCategoria.PRESENTACION
    )

    companion object {
        private const val TAG = "Me2MediaLibrary"
        const val ROOT = "ME2_MEDIA"
        const val METADATA = "metadata.json"

        fun aplicarOverride(r: MediaResource, o: JSONObject?): MediaResource {
            o ?: return r
            return r.copy(
                habilitado = o.optBoolean("habilitado", r.habilitado),
                prioridad = o.optInt("prioridad", r.prioridad),
                loop = o.optBoolean("loop", r.loop),
                audio = o.optBoolean("audio", r.audio),
                // Los overrides solo pueden AGREGAR restricciones premium/adulto, nunca quitarlas.
                premium = r.premium || o.optBoolean("premium", false),
                adulto = r.adulto || o.optBoolean("adulto", false),
                duracionMs = if (o.has("duracion_ms") && !o.isNull("duracion_ms")) o.optLong("duracion_ms") else r.duracionMs
            )
        }
    }
}
