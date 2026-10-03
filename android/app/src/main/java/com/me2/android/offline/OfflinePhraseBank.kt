package com.me2.android.offline

import android.content.Context
import com.me2.android.media.AudiovisualCue
import org.json.JSONObject
import kotlin.random.Random

/**
 * "Promptblock offline": banco de frases editable (assets/offline/frases_offline.json) usado SOLO sin red para
 * alarmas, recordatorios e inicio de conversación. Cada categoría trae su pista audiovisual (categoría +
 * subcategoría + intensidad) para que el avatar reproduzca un clip acorde vía MediaSelector.
 */
class OfflinePhraseBank(json: String) {
    data class Phrase(val id: String, val categoria: String, val text: String, val cue: AudiovisualCue?)

    private data class Category(val variantes: List<String>, val cues: List<AudiovisualCue>)

    private val categorias: Map<String, Category> = runCatching {
        val root = JSONObject(json).getJSONObject("categorias")
        root.keys().asSequence().associateWith { key ->
            val c = root.getJSONObject(key)
            val vars = c.optJSONArray("variantes")
            val cues = listOfNotNull(c.optJSONObject("audiovisual")?.let(::cueOf)) +
                (c.optJSONArray("audiovisual_alternativos")?.let { a -> (0 until a.length()).mapNotNull { cueOf(a.optJSONObject(it)) } } ?: emptyList())
            Category((0 until (vars?.length() ?: 0)).map { vars!!.getString(it) }.filter { it.isNotBlank() }, cues)
        }
    }.getOrDefault(emptyMap())

    fun categories(): Set<String> = categorias.keys

    /** Elige una variante sin repetir la anterior (si hay más de una) y completa los placeholders. */
    fun pick(categoria: String, vars: Map<String, String?>, previousId: String? = null, random: Random = Random.Default): Phrase? {
        val c = categorias[categoria] ?: return null
        if (c.variantes.isEmpty()) return null
        val indices = c.variantes.indices.filter { "$categoria#$it" != previousId }.ifEmpty { c.variantes.indices.toList() }
        val i = indices[random.nextInt(indices.size)]
        val cue = if (c.cues.isEmpty()) null else c.cues[random.nextInt(c.cues.size)]
        return Phrase("$categoria#$i", categoria, render(c.variantes[i], vars), cue)
    }

    companion object {
        const val ASSET = "offline/frases_offline.json"
        const val ALARMA_1 = "alarma_aviso_1"
        const val ALARMA_2 = "alarma_aviso_2"
        const val ALARMA_FINAL = "alarma_final"
        const val RECORDATORIO = "recordatorio"
        const val INICIO = "inicio_conversacion"
        /** Respuesta a un INPUT del chat sin red (el mensaje del usuario queda guardado como pendiente). */
        const val SIN_RED_INPUT = "sin_red_input"

        fun alarmCategory(stage: Int) = when (stage) { 1 -> ALARMA_1; 2 -> ALARMA_2; else -> ALARMA_FINAL }

        private fun cueOf(o: JSONObject?): AudiovisualCue? {
            val cat = o?.optString("categoria")?.ifBlank { null } ?: return null
            return AudiovisualCue(cat, o.optString("subcategoria").ifBlank { null }, o.optString("intensidad").ifBlank { null })
        }

        /** {nombre}/{titulo}/{hora}; si falta el dato se omite el placeholder y se limpia la puntuación sobrante. */
        fun render(template: String, vars: Map<String, String?>): String {
            var out = Regex("\\{(\\w+)\\}").replace(template) { m -> vars[m.groupValues[1]]?.trim().orEmpty() }
            out = out.replace(Regex("\\(\\s*\\)"), "")
                .replace(Regex("\\s+([,.:!?])"), "$1")
                .replace(Regex("([,:])\\s*([.!?])"), "$2")
                .replace(Regex("^[\\s,:.]+"), "")
                .replace(Regex(",\\s*,"), ",")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            return out.replaceFirstChar { it.uppercase() }
        }
    }
}

/** Wrapper Android: carga el asset y persiste la última variante por categoría (anti-repetición entre procesos). */
class OfflinePhrases(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("me2_offline_phrases", Context.MODE_PRIVATE)
    private val bank by lazy {
        OfflinePhraseBank(runCatching { app.assets.open(OfflinePhraseBank.ASSET).bufferedReader().use { it.readText() } }.getOrDefault("{}"))
    }

    fun pick(categoria: String, vars: Map<String, String?>): OfflinePhraseBank.Phrase? {
        val phrase = bank.pick(categoria, vars, prefs.getString(categoria, null)) ?: return null
        prefs.edit().putString(categoria, phrase.id).commit()
        return phrase
    }
}
