package com.me2.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class LocalConversationEntry(
    val role: String,
    val text: String,
    val timestamp: Long,
    val initiativeId: String? = null,
    /** Emoji con el que reaccionó el avatar (solo en mensajes del usuario). */
    val reaction: String? = null,
    /** Mensaje del usuario enviado sin red: guardado localmente, todavía no llegó al backend (no se reenvía solo). */
    val pending: Boolean = false
)

data class LocalMemoryNote(
    val category: String,
    val text: String,
    val importance: Int,
    val timestamp: Long
)

data class LocalAssetMemory(
    val name: String,
    val summary: String,
    val timestamp: Long
)

/** Ubicación del usuario (ciudad geocodificada por el backend o informada por el teléfono). */
data class LocalLocation(
    val city: String?,
    val lat: Double?,
    val lon: Double?,
    val timeZone: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        city?.let { put("ciudad", it) }
        lat?.let { put("lat", it) }
        lon?.let { put("lon", it) }
        timeZone?.let { put("zonaHoraria", it) }
    }

    companion object {
        fun fromJson(json: JSONObject?): LocalLocation? {
            if (json == null) return null
            val lat = json.optDouble("lat").takeIf { !it.isNaN() && it in -90.0..90.0 }
            val lon = json.optDouble("lon").takeIf { !it.isNaN() && it in -180.0..180.0 }
            val city = json.optString("ciudad").trim().takeIf { it.isNotEmpty() && it != "null" }?.take(80)
            val tz = json.optString("zonaHoraria").trim().takeIf { it.isNotEmpty() && it != "null" }?.take(64)
            if (city == null && (lat == null || lon == null)) return null
            return LocalLocation(city, lat, lon, tz)
        }
    }
}

data class LocalMe2Memory(
    val version: Int = 2,
    val userId: String,
    val characterName: String? = null,
    val preferredName: String? = null,
    val conversation: MutableList<LocalConversationEntry> = mutableListOf(),
    val shortTermFocus: String = "general",
    val shortTermIntent: String = "acompanar",
    val persistentMemories: MutableList<LocalMemoryNote> = mutableListOf(),
    val importantMemories: MutableList<LocalMemoryNote> = mutableListOf(),
    val codeMemories: MutableList<LocalAssetMemory> = mutableListOf(),
    val fiscalMemories: MutableList<LocalAssetMemory> = mutableListOf(),
    val updatedAt: Long = System.currentTimeMillis(),
    val hiddenConversationThrough: Long = 0L,
    /** Premium local (JSON): {"fiscal": {...}, "proyectos": {...}}. Vive en el teléfono y viaja en el respaldo cifrado. */
    val premiumLocal: String = "{}",
    /** Presentación (00_PRESENTACION) ya vista: viaja en el respaldo para que no se repita tras reinstalar/restaurar. */
    val presentationCompletedAt: Long = 0L,
    /** Gustos/intereses del usuario (los extrae el backend del mensaje y el teléfono los guarda): habilitan noticias. */
    val interests: List<String> = emptyList(),
    val dislikes: List<String> = emptyList(),
    /** Ubicación (habilita clima en el chat, el widget y las iniciativas). */
    val location: LocalLocation? = null
) {
    fun withUpdatedTimestamp() = copy(updatedAt = System.currentTimeMillis())

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("userId", userId)
        characterName?.let { put("characterName", it) }
        preferredName?.let { put("preferredName", it) }
        put("shortTermFocus", shortTermFocus)
        put("shortTermIntent", shortTermIntent)
        put("updatedAt", updatedAt)
        put("hiddenConversationThrough", hiddenConversationThrough)
        put("conversation", JSONArray().apply {
            conversation.forEach { entry ->
                put(JSONObject().apply {
                    put("role", entry.role)
                    put("text", entry.text)
                    put("timestamp", entry.timestamp)
                    entry.initiativeId?.let { put("initiativeId", it) }
                    entry.reaction?.let { put("reaction", it) }
                    if (entry.pending) put("pendiente", true)
                })
            }
        })
        put("persistentMemories", Companion.notesToJson(persistentMemories))
        put("importantMemories", Companion.notesToJson(importantMemories))
        put("codeMemories", Companion.assetsToJson(codeMemories))
        put("fiscalMemories", Companion.assetsToJson(fiscalMemories))
        put("premiumLocal", premiumLocalJson())
        if (presentationCompletedAt > 0L) put("presentationCompletedAt", presentationCompletedAt)
        put("interests", JSONArray(interests))
        put("dislikes", JSONArray(dislikes))
        location?.let { put("location", it.toJson()) }
    }

    /** Aplica los hechos estructurados que devolvió el backend (gustos nuevos, disgustos, ubicación geocodificada). */
    fun withBackendFacts(gustos: List<String>, disgustos: List<String>, ubicacion: LocalLocation?): LocalMe2Memory {
        val cleanDislikes = (dislikes.filterNot { it in gustos } + disgustos).map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length in 2..60 }.distinct().takeLast(MAX_INTERESTS)
        val cleanInterests = (interests.filterNot { it in disgustos } + gustos).map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length in 2..60 && it !in cleanDislikes }.distinct().takeLast(MAX_INTERESTS)
        return copy(interests = cleanInterests, dislikes = cleanDislikes, location = ubicacion ?: location)
    }

    fun premiumLocalJson(): JSONObject = runCatching { JSONObject(premiumLocal) }.getOrDefault(JSONObject())

    /** Momento de la última interacción real (para el hilo de continuidad del respaldo). */
    fun lastInteractionAt(): Long? = conversation.lastOrNull()?.timestamp

    fun toBackendContext(): JSONObject = JSONObject().apply {
        put("source", "android_local_primary")
        put("version", version)
        characterName?.let { put("characterName", it) }
        put("shortTermFocus", shortTermFocus)
        put("shortTermIntent", shortTermIntent)
        preferredName?.let { put("preferredName", it) }
        put("updatedAt", updatedAt)
        put("recentConversation", JSONArray().apply {
            conversation.takeLast(20).forEach { entry ->
                put(JSONObject().apply {
                    put("role", entry.role)
                    put("text", entry.text)
                    put("timestamp", entry.timestamp)
                })
            }
        })
        put("persistentMemories", Companion.notesToJson(persistentMemories.takeLast(12)))
        put("importantMemories", Companion.notesToJson(importantMemories.takeLast(8)))
        // codeMemories/fiscalMemories NO viajan en cada turno (el backend no las usa en el chat; el Premium local
        // va aparte en `premiumLocal`): menos datos sensibles y menos tokens.
        put("gustos", JSONArray(interests.takeLast(MAX_INTERESTS)))
        put("disgustos", JSONArray(dislikes.takeLast(MAX_INTERESTS)))
        location?.let { put("ubicacion", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject): LocalMe2Memory {
            return LocalMe2Memory(
                version = json.optInt("version", 1).coerceAtLeast(1),
                userId = json.optString("userId"),
                characterName = json.optString("characterName").ifBlank { null },
                preferredName = json.optString("preferredName").ifBlank { null },
                conversation = jsonArrayToConversation(json.optJSONArray("conversation")),
                shortTermFocus = json.optString("shortTermFocus", "general"),
                shortTermIntent = json.optString("shortTermIntent", "acompanar"),
                persistentMemories = jsonArrayToNotes(json.optJSONArray("persistentMemories")),
                importantMemories = jsonArrayToNotes(json.optJSONArray("importantMemories")),
                codeMemories = jsonArrayToAssets(json.optJSONArray("codeMemories")),
                fiscalMemories = jsonArrayToAssets(json.optJSONArray("fiscalMemories")),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                hiddenConversationThrough = json.optLong("hiddenConversationThrough", 0L),
                premiumLocal = json.optJSONObject("premiumLocal")?.toString() ?: "{}",
                presentationCompletedAt = json.optLong("presentationCompletedAt", 0L),
                interests = jsonArrayToStrings(json.optJSONArray("interests")),
                dislikes = jsonArrayToStrings(json.optJSONArray("dislikes")),
                location = LocalLocation.fromJson(json.optJSONObject("location"))
            )
        }

        const val MAX_INTERESTS = 30

        internal fun jsonArrayToStrings(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        }

        internal fun notesToJson(notes: List<LocalMemoryNote>): JSONArray = JSONArray().apply {
            notes.forEach { note ->
                put(JSONObject().apply {
                    put("category", note.category)
                    put("text", note.text)
                    put("importance", note.importance)
                    put("timestamp", note.timestamp)
                })
            }
        }

        internal fun assetsToJson(assets: List<LocalAssetMemory>): JSONArray = JSONArray().apply {
            assets.forEach { asset ->
                put(JSONObject().apply {
                    put("name", asset.name)
                    put("summary", asset.summary)
                    put("timestamp", asset.timestamp)
                })
            }
        }

        private fun jsonArrayToConversation(array: JSONArray?): MutableList<LocalConversationEntry> {
            val result = mutableListOf<LocalConversationEntry>()
            if (array == null) return result
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                result += LocalConversationEntry(
                    role = item.optString("role", "user"),
                    text = item.optString("text", ""),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                    initiativeId = item.optString("initiativeId").takeIf { it.isNotBlank() },
                    reaction = item.optString("reaction").takeIf { it.isNotBlank() },
                    pending = item.optBoolean("pendiente", false)
                )
            }
            return result
        }

        private fun jsonArrayToNotes(array: JSONArray?): MutableList<LocalMemoryNote> {
            val result = mutableListOf<LocalMemoryNote>()
            if (array == null) return result
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                result += LocalMemoryNote(
                    category = item.optString("category", "general"),
                    text = item.optString("text", ""),
                    importance = item.optInt("importance", 1),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
            }
            return result
        }

        private fun jsonArrayToAssets(array: JSONArray?): MutableList<LocalAssetMemory> {
            val result = mutableListOf<LocalAssetMemory>()
            if (array == null) return result
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                result += LocalAssetMemory(
                    name = item.optString("name", ""),
                    summary = item.optString("summary", ""),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
            }
            return result
        }
    }
}

class LocalMemoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val database = Me2MemoryDatabase.getInstance(appContext)
    private val dao = database.memoryDao()
    private val localVault = LocalVault(appContext)

    fun observe(userId: String) = dao.observeByUserId(userId)

    fun load(userId: String): LocalMe2Memory {
        val record = dao.findByUserId(userId) ?: return LocalMe2Memory(userId = userId)
        return localVault.decrypt(record.ivBase64, record.payloadBase64)
            ?.let { LocalMe2Memory.fromJson(JSONObject(it)) }
            ?.copy(userId = userId)
            ?: LocalMe2Memory(userId = userId)
    }

    fun save(memory: LocalMe2Memory) {
        val updated = memory.withUpdatedTimestamp()
        runCatching {
            val encrypted = localVault.encrypt(updated.toJson().toString())
            dao.upsert(
                Me2MemoryRecordEntity(
                    userId = updated.userId,
                    payloadBase64 = encrypted.payloadBase64,
                    ivBase64 = encrypted.ivBase64,
                    schemaVersion = updated.version,
                    updatedAt = updated.updatedAt
                )
            )
        }
    }

    fun replace(memory: LocalMe2Memory) = save(memory)

    fun migrateUserMemory(fromUserId: String, toUserId: String) {
        if (fromUserId == toUserId) return
        if (!isEffectivelyEmpty(toUserId)) return
        val sourceMemory = load(fromUserId)
        if (isEffectivelyEmpty(fromUserId)) return
        save(sourceMemory.copy(userId = toUserId))
        dao.deleteByUserId(fromUserId)
    }

    fun applyBackendFacts(userId: String, gustos: List<String>, disgustos: List<String>, ubicacion: LocalLocation?) {
        if (gustos.isEmpty() && disgustos.isEmpty() && ubicacion == null) return
        save(load(userId).withBackendFacts(gustos, disgustos, ubicacion).withUpdatedTimestamp())
    }

    fun isEffectivelyEmpty(userId: String): Boolean {
        val memory = load(userId)
        return memory.conversation.isEmpty() &&
            memory.characterName.isNullOrBlank() &&
            memory.preferredName.isNullOrBlank() &&
            memory.persistentMemories.isEmpty() &&
            memory.importantMemories.isEmpty() &&
            memory.codeMemories.isEmpty() &&
            memory.fiscalMemories.isEmpty() &&
            memory.premiumLocalJson().length() == 0
    }

    fun appendUserMessage(userId: String, rawText: String) {
        val memory = load(userId)
        val text = rawText.trim()
        if (text.isEmpty()) return
        val characterName = extractCharacterName(text, memory) ?: memory.characterName
        val focus = inferFocus(text)
        val updated = memory.copy(
            characterName = characterName,
            conversation = (memory.conversation + LocalConversationEntry("user", text, nextMessageTime(memory)))
                .takeLast(200)
                .toMutableList(),
            shortTermFocus = focus,
            shortTermIntent = inferIntent(text),
            persistentMemories = mergeNotes(memory.persistentMemories, extractPersistentNote(text)),
            importantMemories = mergeNotes(memory.importantMemories, extractImportantNote(text)),
            codeMemories = maybeAppendAsset(memory.codeMemories, focus == "codigo", "codigo", text),
            fiscalMemories = maybeAppendAsset(memory.fiscalMemories, focus == "fiscal", "fiscal", text)
        )
        save(updated)
    }

    fun markPresentationCompleted(userId: String, at: Long = System.currentTimeMillis()) {
        val memory = load(userId)
        if (memory.presentationCompletedAt > 0L) return
        save(memory.copy(presentationCompletedAt = at))
    }

    /** Sin red: marca el último mensaje del usuario como pendiente (queda guardado, no se pierde ni se reenvía solo). */
    fun markLastUserMessagePending(userId: String) {
        val memory = load(userId)
        val i = memory.conversation.indexOfLast { it.role == "user" }
        if (i < 0 || memory.conversation[i].pending) return
        val conv = memory.conversation.toMutableList().also { it[i] = it[i].copy(pending = true) }
        save(memory.copy(conversation = conv))
    }

    fun pendingUserMessages(userId: String): List<LocalConversationEntry> = load(userId).conversation.filter { it.role == "user" && it.pending }

    /** Tras un chat exitoso: los pendientes ya viajaron como contexto reciente al orquestador. */
    fun clearPendingMessages(userId: String) {
        val memory = load(userId)
        if (memory.conversation.none { it.pending }) return
        save(memory.copy(conversation = memory.conversation.map { if (it.pending) it.copy(pending = false) else it }.toMutableList()))
    }

    /** Premium local: guarda el estado nuevo de un módulo (fiscal | proyectos) devuelto por el orquestador. */
    fun savePremiumModule(userId: String, modulo: String, estado: JSONObject) {
        if (modulo !in PREMIUM_MODULES) return
        val memory = load(userId)
        val local = memory.premiumLocalJson().put(modulo, estado)
        save(memory.copy(premiumLocal = local.toString()))
    }

    /** Premium: append a code memory (scaffold; full monotributista UX is backend/TODO). */
    fun appendCodeMemory(userId: String, name: String, summary: String) {
        val memory = load(userId)
        val asset = LocalAssetMemory(name = name.trim(), summary = summary.trim(), timestamp = System.currentTimeMillis())
        if (asset.name.isBlank() && asset.summary.isBlank()) return
        save(memory.copy(codeMemories = mergeAssets(memory.codeMemories, asset)))
    }

    /** Premium: append a fiscal memory locally. Full email-to-accountant UX is TODO. */
    fun appendFiscalMemory(userId: String, name: String, summary: String) {
        val memory = load(userId)
        val asset = LocalAssetMemory(name = name.trim(), summary = summary.trim(), timestamp = System.currentTimeMillis())
        if (asset.name.isBlank() && asset.summary.isBlank()) return
        save(memory.copy(fiscalMemories = mergeAssets(memory.fiscalMemories, asset)))
    }

    fun appendSpecializedMemoryFromBackend(userId: String, payload: org.json.JSONObject?) {
        if (payload == null) return
        payload.optJSONObject("codeMemory")?.let {
            appendCodeMemory(userId, it.optString("name", "codigo"), it.optString("summary", it.optString("text")))
        }
        payload.optJSONObject("fiscalMemory")?.let {
            appendFiscalMemory(userId, it.optString("name", "fiscal"), it.optString("summary", it.optString("text")))
        }
        payload.optJSONArray("codeMemories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                appendCodeMemory(userId, item.optString("name", "codigo"), item.optString("summary", item.optString("text")))
            }
        }
        payload.optJSONArray("fiscalMemories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                appendFiscalMemory(userId, item.optString("name", "fiscal"), item.optString("summary", item.optString("text")))
            }
        }
    }

    /** Persiste la reacción del avatar sobre el último mensaje del usuario. */
    fun setReactionOnLastUserMessage(userId: String, emoji: String) {
        val memory = load(userId)
        val idx = memory.conversation.indexOfLast { it.role == "user" }
        if (idx < 0) return
        val conv = memory.conversation.toMutableList()
        conv[idx] = conv[idx].copy(reaction = emoji)
        save(memory.copy(conversation = conv))
    }

    fun appendAssistantMessage(userId: String, rawText: String) {
        val memory = load(userId)
        val text = rawText.trim()
        if (text.isEmpty()) return
        val updated = memory.copy(
            conversation = (memory.conversation + LocalConversationEntry("assistant", text, nextMessageTime(memory)))
                .takeLast(200)
                .toMutableList()
        )
        save(updated)
    }

    fun appendInitiativeMessage(userId: String, initiativeId: String, text: String) {
        val memory = load(userId)
        if (memory.conversation.any { it.initiativeId == initiativeId }) return
        require(text.isNotBlank())
        val entry = LocalConversationEntry("assistant", text, nextMessageTime(memory), initiativeId)
        save(memory.copy(conversation = (memory.conversation + entry).takeLast(200).toMutableList()))
    }

    fun clearConversationDisplay(userId: String) {
        val memory = load(userId)
        save(memory.copy(hiddenConversationThrough = maxOf(
            memory.hiddenConversationThrough, memory.conversation.maxOfOrNull { it.timestamp } ?: 0L
        )))
    }

    private fun nextMessageTime(memory: LocalMe2Memory): Long =
        maxOf(System.currentTimeMillis(), (memory.conversation.lastOrNull()?.timestamp ?: 0L) + 1L, memory.hiddenConversationThrough + 1L)

    private fun inferFocus(text: String): String {
        val normalized = text.lowercase(Locale.US)
        return when {
            "código" in normalized || "codigo" in normalized || "program" in normalized -> "codigo"
            "factura" in normalized || "impuesto" in normalized || "fiscal" in normalized -> "fiscal"
            "agenda" in normalized || "alarma" in normalized || "record" in normalized -> "organizacion"
            "premium" in normalized || "pago" in normalized -> "premium"
            else -> "general"
        }
    }

    private fun inferIntent(text: String): String {
        val normalized = text.lowercase(Locale.US)
        return when {
            normalized.contains("?") || normalized.startsWith("qué") || normalized.startsWith("como") -> "resolver"
            "recuerda" in normalized || "acuérdate" in normalized || "anota" in normalized -> "recordar"
            else -> "acompanar"
        }
    }

    private fun extractCharacterName(text: String, memory: LocalMe2Memory): String? {
        extractCharacterNameFromPattern(text)?.let { return it }
        val lastAssistantMessage = memory.conversation.lastOrNull { it.role == "assistant" }?.text.orEmpty()
        return if (CHARACTER_NAME_PROMPT in lastAssistantMessage) {
            normalizeCharacterName(text)
        } else {
            null
        }
    }

    private fun extractCharacterNameFromPattern(text: String): String? {
        val patterns = listOf(
            "quiero que te llames ",
            "quiero llamarte ",
            "te voy a llamar ",
            "voy a llamarte ",
            "quiero ponerte ",
            "quiero darte ",
            "tu nombre va a ser ",
            "tu nombre será ",
            "tu nombre sera ",
            "vas a llamarte ",
            "te llamaré ",
            "te llamare "
        )
        val lowered = text.lowercase(Locale.US).trim()
        for (pattern in patterns) {
            val index = lowered.indexOf(pattern)
            if (index < 0) continue
            val value = text.substring(index + pattern.length)
            normalizeCharacterName(trimCharacterName(value))?.let { return it }
        }
        return null
    }

    private fun trimCharacterName(raw: String): String {
        val lowered = raw.lowercase(Locale.US)
        val separators = listOf(" por ", " y además", " y ademas", " pero ", " porque ", ",", ".", "?", "!", ";", ":")
        var cut = raw.length
        for (separator in separators) {
            val index = lowered.indexOf(separator)
            if (index >= 0 && index < cut) cut = index
        }
        return raw.substring(0, cut)
    }

    private fun normalizeCharacterName(raw: String): String? {
        val cleaned = raw.trim()
            .trim('"', '\'', '“', '”', '‘', '’', '.', ',', ';', ':', '!', '?', '…')
            .replace(Regex("""\s+"""), " ")
        if (cleaned.isBlank() || cleaned.length > 40) return null
        if (!cleaned.matches(Regex("""[A-Za-zÁÉÍÓÚÜÑáéíóúüñ0-9][A-Za-zÁÉÍÓÚÜÑáéíóúüñ0-9 '\-_.]{0,39}"""))) {
            return null
        }
        val lowered = cleaned.lowercase(Locale.US)
        if (lowered in setOf(
                "me2", "hola", "holi", "buenas", "gracias", "ninguno",
                "como quieras", "da igual", "sin nombre", "ningún nombre", "ningun nombre",
                "nombre", "un nombre", "nickname", "un nickname", "apodo", "un apodo"
            )
        ) return null
        if (cleaned.split(" ").size > 4) return null
        return cleaned.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }
    }

    private fun extractPersistentNote(text: String): LocalMemoryNote? {
        val normalized = text.lowercase(Locale.US)
        val shouldPersist = listOf(
            "me gusta",
            "prefiero",
            "trabajo",
            "estoy construyendo",
            "proyecto",
            "mi empresa",
            "mi horario"
        ).any { normalized.contains(it) }
        if (!shouldPersist) return null
        return LocalMemoryNote(
            category = inferFocus(text),
            text = text.trim(),
            importance = 2,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun extractImportantNote(text: String): LocalMemoryNote? {
        val normalized = text.lowercase(Locale.US)
        val shouldPersist = listOf(
            "recuérd",
            "importante",
            "no olvides",
            "mi nombre es",
            "cumpleaños",
            "google"
        ).any { normalized.contains(it) }
        if (!shouldPersist) return null
        return LocalMemoryNote(
            category = "importante",
            text = text.trim(),
            importance = 3,
            timestamp = System.currentTimeMillis()
        )
    }


    private fun maybeAppendAsset(
        existing: MutableList<LocalAssetMemory>,
        shouldCapture: Boolean,
        defaultName: String,
        text: String
    ): MutableList<LocalAssetMemory> {
        if (!shouldCapture) return existing
        return mergeAssets(
            existing,
            LocalAssetMemory(name = defaultName, summary = text.trim().take(280), timestamp = System.currentTimeMillis())
        )
    }

    private fun mergeAssets(
        existing: MutableList<LocalAssetMemory>,
        candidate: LocalAssetMemory
    ): MutableList<LocalAssetMemory> {
        val deduped = existing.filterNot {
            it.summary.equals(candidate.summary, ignoreCase = true) ||
                (it.name.equals(candidate.name, ignoreCase = true) && it.summary == candidate.summary)
        }
        return (deduped + candidate).takeLast(64).toMutableList()
    }

    private fun mergeNotes(existing: MutableList<LocalMemoryNote>, candidate: LocalMemoryNote?): MutableList<LocalMemoryNote> {
        if (candidate == null) return existing
        val deduped = existing.filterNot { it.text.equals(candidate.text, ignoreCase = true) }
        return (deduped + candidate).takeLast(64).toMutableList()
    }

    companion object {
        val PREMIUM_MODULES = setOf("fiscal", "proyectos")
        private const val CHARACTER_NAME_PROMPT = "¿Qué nombre o nickname querés que tenga?"
    }
}
