package com.me2.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.me2.android.BuildConfig
import com.me2.android.config.ApiConfig
import com.me2.android.data.LocalMe2Memory
import com.me2.android.data.UserSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class BackendAuthResult(
    val token: String,
    val userId: String,
    val email: String,
    val displayName: String,
    val photoUrl: String?,
    val emailVerified: Boolean
)

data class AdultModeSnapshot(
    val phase: String?,
    val unlocked: Boolean,
    val intensity: String?,
    val extensionEnabled: Boolean,
    val clipCategoria: String?,
    val clipEtiqueta: String?,
    val allowAdultTone: Boolean
)

data class BackendChatResult(
    val reply: String,
    val tone: String?,
    val microExpression: String?,
    val premiumUntilMillis: Long?,
    val adultMode: AdultModeSnapshot? = null,
    val checkoutInitPoint: String? = null,
    val videoEtiqueta: String? = null,
    val clip: com.me2.android.ui.ChatMediaRouting.Clip? = null,
    val media: com.me2.android.ui.ChatMediaRouting.Media? = null,
    val reaction: String? = null,
    /** Pista audiovisual del orquestador (categoría + subcategoría + intensidad; nunca un archivo). */
    val audiovisual: com.me2.android.media.AudiovisualCue? = null,
    /** Clima actual para el widget (p. ej. "23°C"), si el backend lo tiene. */
    val weatherLabel: String? = null,
    /** Acciones deterministas del turno (alarma creada/cancelada en el teléfono, evento agendado). */
    val actions: JSONObject? = null,
    /** Gustos/ubicación extraídos por el backend para guardar en la memoria local primaria. */
    val memoryFacts: MemoryFacts? = null
)

data class MemoryFacts(
    val gustos: List<String>,
    val disgustos: List<String>,
    val ubicacion: com.me2.android.data.LocalLocation?,
    /** Notas reales para la memoria del usuario (p. ej. su respuesta a "¿cómo dormiste?"): (categoría, texto). */
    val notas: List<Pair<String, String>> = emptyList()
)

data class PremiumStatusResult(
    val active: Boolean,
    val premiumUntilMillis: Long,
    val backupMaterial: String?,
    val adultMode: AdultModeSnapshot? = null
)

data class PaymentVerifyResult(
    val premiumActive: Boolean,
    val premiumUntilMillis: Long?,
    val status: String?
)

/** Error HTTP no-2xx con su código (sigue siendo IllegalStateException para quien ya la atrapaba). */
class HttpStatusException(val statusCode: Int, message: String) : IllegalStateException(message)

data class AlarmDispatchStage(
    val stage: Int,
    val offsetFromAlarmMs: Long,
    val channelId: String,
    val notificationType: String,
    val vibration: String,
    val sound: String,
    val title: String,
    val message: String
) {
    companion object {
        /** Plan de intentos del orquestador (`dispatchPlan` de /api/alarmas o de la acción crear_local del chat). */
        fun parsePlan(array: JSONArray?): List<AlarmDispatchStage> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                AlarmDispatchStage(
                    stage = item.optInt("stage", 1),
                    offsetFromAlarmMs = item.optLong("offsetFromAlarmMs", 0L),
                    channelId = item.optString("channelId", com.me2.android.notifications.Me2NotificationChannels.CHANNEL_MESSAGES),
                    notificationType = item.optString("notificationType", "message"),
                    vibration = item.optString("vibration", "double"),
                    sound = item.optString("sound", "bubble"),
                    title = item.optString("titulo", "Hora de despertar"),
                    message = item.optString("mensaje", "ME2 registró tu protocolo de despertar.")
                )
            }
        }
    }
}

data class AlarmRecord(
    val id: String,
    val userId: String,
    val hour: String,
    val title: String,
    val message: String,
    val state: String,
    val stage: Int,
    val attempts: Int,
    val dispatchPlan: List<AlarmDispatchStage>
)

data class AlarmEventResult(
    val state: String,
    val message: String?,
    val nextStage: Int?,
    val alarm: AlarmRecord?
)

class Me2BackendClient internal constructor(baseUrlOverride: String?) {
    constructor() : this(null)

    val googleWebClientId: String = ApiConfig.googleWebClientId.ifBlank { BuildConfig.GOOGLE_WEB_CLIENT_ID.trim() }
    private val baseUrl: String = baseUrlOverride?.trimEnd('/')
        ?: ApiConfig.backendBaseUrl.ifBlank { BuildConfig.BACKEND_BASE_URL.trim().trimEnd('/') }
    private val betaPremiumMillis = 4102444800000L

    /** Solo rutas del propio backend (B3): una URL absoluta de otro origen se descarta. */
    fun absoluteUrl(path: String?): String? = BackendUrlPolicy.resolve(baseUrl, path)

    /** Descarga autenticada (medios del chat protegidos por sesión). */
    fun fetchBytes(url: String, authToken: String?): ByteArray {
        val attach = BackendUrlPolicy.mayAttachToken(baseUrl, url)
        return Me2SessionRecovery.withRecovery(authToken?.takeIf { attach }) { token -> fetchBytesOnce(url, token) }
    }

    private fun fetchBytesOnce(url: String, authToken: String?): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            // El token de sesión solo viaja al origen del backend.
            authToken?.takeIf { BackendUrlPolicy.mayAttachToken(baseUrl, url) }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            if (c.responseCode == 401) throw AuthRequiredException("HTTP 401")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Cierre de sesión explícito: revoca el token en el backend (best effort; las sesiones no vencen solas). */
    fun logout(authToken: String) {
        request(method = "POST", path = LOGOUT_PATH, authToken = authToken)
    }

    fun isConfigured(): Boolean = ApiConfig.isBackendReady() && baseUrl.isNotEmpty()

    fun isOnline(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Login con la cuenta básica. serverAuthCode opcional (compatibilidad); la edad se verifica con [submitAgeAuthCode]. */
    fun authenticateWithGoogle(idToken: String, serverAuthCode: String? = null): BackendAuthResult {
        val json = request(
            method = "POST",
            path = "/api/auth/google",
            body = JSONObject().put("idToken", idToken).apply { serverAuthCode?.let { put("serverAuthCode", it) } }
        )
        return Me2AuthContract.parseGoogleAuth(json)
    }

    /**
     * Verificación de edad bajo demanda: serverAuthCode del scope de fecha de nacimiento (autorización incremental).
     * Devuelve el estado del backend: "mayor" | "menor" | "sin_dato".
     */
    fun submitAgeAuthCode(session: UserSession, serverAuthCode: String): String {
        val json = request(
            method = "POST",
            path = "/api/auth/google/edad",
            authToken = session.authToken,
            body = JSONObject().put("serverAuthCode", serverAuthCode)
        )
        return json.optJSONObject("data")?.optString("estado").orEmpty().ifBlank { "sin_dato" }
    }

    /** userId autenticado según el backend (para corregir sesiones guardadas con el id de Google). */
    fun fetchAuthenticatedUserId(authToken: String): String? =
        Me2AuthContract.parseAuthMeUserId(request(method = "GET", path = "/api/auth/me", authToken = authToken))
    fun sendChat(
        session: UserSession,
        memory: LocalMe2Memory,
        message: String,
        initiative: JSONObject? = null,
        /** Ubicación efectiva (teléfono fresco > ciudad del chat), ver DeviceLocationPolicy.effective. */
        location: com.me2.android.data.LocalLocation? = memory.location,
        /** Alarma que este mensaje respondió (protocolo despertador): {hora, titulo, intento}. */
        alarmaRespondida: JSONObject? = null,
        /** Próximos eventos del calendario propio del teléfono (el orquestador los lee para consultar/borrar). */
        calendar: JSONArray? = null
    ): BackendChatResult {
        val json = request(
            method = "POST",
            path = "/chat",
            authToken = session.authToken,
            body = JSONObject().apply {
                put("mensaje", message)
                put("contexto", JSONObject().apply {
                    put("clienteOficial", "android_nativo")
                    put("memoriaLocal", memory.toBackendContext().apply { calendar?.let { put("calendario", it) } })
                    // Premium local (gestor monotributista + proyectos): el orquestador lo transforma y devuelve el estado nuevo.
                    put("premiumLocal", memory.premiumLocalJson())
                    if (initiative != null) put("iniciativa", initiative)
                    if (alarmaRespondida != null) put("alarmaRespondida", alarmaRespondida)
                    // Herramientas (clima/hora): zona del teléfono + coordenadas conocidas de la memoria local.
                    put("zonaHoraria", com.me2.android.time.Me2Clock.ZONE_ID) // zona fija de ME2, no la del teléfono
                    location?.let { loc ->
                        if (loc.lat != null && loc.lon != null) { put("lat", loc.lat); put("lon", loc.lon) }
                        loc.city?.let { put("ciudad", it) }
                    }
                })
            }
        )

        val adultJson = json.optJSONObject("adultMode")
        val videoJson = json.optJSONObject("video")
        val checkoutJson = json.optJSONObject("checkout")
        val clipHint = adultJson?.optJSONObject("clipHint")
        return BackendChatResult(
            reply = json.optString("respuesta", "ME2 recibió el mensaje, pero no devolvió texto."),
            tone = json.optJSONObject("expresion")?.optString("tono"),
            microExpression = json.optJSONObject("expresion")?.optString("microexpresion"),
            premiumUntilMillis = parsePremiumMillis(json.optJSONObject("premium")),
            adultMode = adultJson?.let {
                AdultModeSnapshot(
                    phase = it.optString("phase").ifBlank { null },
                    unlocked = it.optBoolean("unlocked", false),
                    intensity = it.optString("intensity").ifBlank { null },
                    extensionEnabled = it.optBoolean("extensionEnabled", false),
                    clipCategoria = (clipHint?.optString("categoria") ?: videoJson?.optString("categoria"))
                        ?.ifBlank { null },
                    clipEtiqueta = (clipHint?.optString("etiqueta") ?: videoJson?.optString("etiqueta"))
                        ?.ifBlank { null },
                    allowAdultTone = it.optBoolean("allowAdultTone", false)
                )
            },
            checkoutInitPoint = checkoutJson?.optString("initPoint")?.ifBlank { null },
            videoEtiqueta = videoJson?.optString("etiqueta")?.ifBlank { null },
            clip = json.optJSONObject("clip")?.let {
                com.me2.android.ui.ChatMediaRouting.Clip(
                    tipo = it.optString("tipo", "clip"), fuente = it.optString("fuente", "galeria"),
                    categoria = it.optString("categoria").ifBlank { null }, url = absoluteUrl(it.optString("url"))
                )
            },
            media = json.optJSONObject("media")?.let {
                com.me2.android.ui.ChatMediaRouting.Media(tipo = it.optString("tipo"), url = absoluteUrl(it.optString("url")))
            },
            reaction = json.optJSONObject("reaccion")?.optString("emoji")?.ifBlank { null },
            audiovisual = parseAudiovisual(json.optJSONObject("audiovisual")),
            weatherLabel = parseWeatherLabel(json.optJSONObject("clima")),
            actions = json.optJSONObject("acciones"),
            memoryFacts = parseMemoryFacts(json.optJSONObject("memoriaLocalDelta"))
        )
    }

    /** Hechos estructurados para la memoria local primaria (gustos/disgustos/ubicación). */
    internal fun parseMemoryFacts(o: JSONObject?): MemoryFacts? {
        o ?: return null
        val strings = { key: String ->
            val a = o.optJSONArray(key)
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        }
        val notas = o.optJSONArray("notas")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val n = a.optJSONObject(i) ?: return@mapNotNull null
                val texto = n.optString("texto").trim().take(280)
                if (texto.isEmpty()) null else n.optString("categoria", "nota").ifBlank { "nota" } to texto
            }
        }.orEmpty()
        val facts = MemoryFacts(strings("gustos"), strings("disgustos"), com.me2.android.data.LocalLocation.fromJson(o.optJSONObject("ubicacion")), notas)
        return facts.takeUnless { it.gustos.isEmpty() && it.disgustos.isEmpty() && it.ubicacion == null && it.notas.isEmpty() }
    }

    internal fun parseAudiovisual(o: JSONObject?): com.me2.android.media.AudiovisualCue? {
        val categoria = o?.optString("categoria")?.ifBlank { null } ?: return null
        return com.me2.android.media.AudiovisualCue(
            categoria = categoria,
            subcategoria = o.optString("subcategoria").ifBlank { null }?.takeIf { it != "null" },
            intensidad = o.optString("intensidad").ifBlank { null }?.takeIf { it != "null" }
        )
    }

    internal fun parseWeatherLabel(o: JSONObject?): String? {
        o ?: return null
        if (!o.has("temperatura") || o.isNull("temperatura")) return null
        val t = o.optDouble("temperatura", Double.NaN)
        return if (t.isNaN()) null else "${Math.round(t)}°C"
    }

    fun evaluateInitiative(
        session: UserSession,
        memory: LocalMe2Memory,
        state: JSONObject,
        events: JSONArray = JSONArray(),
        enPrimerPlano: Boolean,
        notificacionesHabilitadas: Boolean,
        /** Si no es null: pedir al orquestador una iniciativa generada ahora para entregar desde ese instante (sin red). */
        prefetchAt: Long? = null,
        /** Calendario propio del teléfono: recordatorios de eventos y "planear una salida" sin pisar la agenda. */
        calendar: JSONArray? = null
    ): JSONObject {
        val profile = JSONObject((state.optJSONObject("perfilRitmo") ?: JSONObject()).toString())
            .put("zonaHoraria", com.me2.android.time.Me2Clock.ZONE_ID)
            .put("ultimaInteraccion", state.optLong("ultimaInteraccion"))
        val json = request(
            method = "POST",
            path = "/api/iniciativas/evaluar",
            authToken = session.authToken,
            body = JSONObject().apply {
                // Sin userId: la identidad la decide el backend a partir del token.
                put("memoriaLocal", memory.toBackendContext().apply { calendar?.let { put("calendario", it) } })
                put("registro", state.optJSONArray("registro") ?: JSONArray())
                put("perfilRitmo", profile)
                put(
                    "disponibilidad",
                    JSONObject()
                        .put("enPrimerPlano", enPrimerPlano)
                        .put("notificacionesHabilitadas", notificacionesHabilitadas)
                )
                put("eventos", events)
                if (prefetchAt != null) {
                    put("prefetch", true)
                    put("entregarDesde", prefetchAt)
                }
            }
        )
        return json.getJSONObject("data")
    }
    fun fetchPremiumStatus(session: UserSession): PremiumStatusResult {
        val json = request(
            method = "GET",
            path = "/api/premium/${session.id}",
            authToken = session.authToken
        )
        val data = json.optJSONObject("data") ?: json
        val adultJson = data.optJSONObject("adultMode")
        val clipHint = adultJson?.optJSONObject("clipHint")
        return PremiumStatusResult(
            active = data.optBoolean("premiumActivo", false),
            premiumUntilMillis = parsePremiumMillis(data) ?: 0L,
            backupMaterial = data.optString("backupMaterial").ifBlank { null },
            adultMode = adultJson?.let {
                AdultModeSnapshot(
                    phase = it.optString("phase").ifBlank { null },
                    unlocked = it.optBoolean("unlocked", false),
                    intensity = it.optString("intensity").ifBlank { null },
                    extensionEnabled = it.optBoolean("extensionEnabled", false),
                    clipCategoria = clipHint?.optString("categoria")?.ifBlank { null },
                    clipEtiqueta = clipHint?.optString("etiqueta")?.ifBlank { null },
                    allowAdultTone = it.optBoolean("allowAdultTone", false)
                )
            }
        )
    }

    /**
     * Vuelta de Mercado Pago: el servidor consulta el pago real por id (la URL de vuelta no acredita nada).
     * Aprobado → premiumActivo + premiumHasta; pendiente/rechazado → status. Errores HTTP → HttpStatusException.
     */
    fun verifyPayment(session: UserSession, paymentId: String): PaymentVerifyResult {
        val json = request(
            method = "POST",
            path = "/api/mercadopago/verify",
            authToken = session.authToken,
            body = JSONObject().put("paymentId", paymentId)
        )
        val data = json.optJSONObject("data") ?: json
        val active = data.optBoolean("premiumActivo", false)
        return PaymentVerifyResult(
            premiumActive = active,
            premiumUntilMillis = if (active) parsePremiumMillis(data) else null,
            status = data.optString("status").ifBlank { null }
        )
    }

    fun fetchBackupMaterial(session: UserSession): String {
        val json = request(
            method = "GET",
            path = "/api/premium/${session.id}/backup/materials",
            authToken = session.authToken
        )
        return json.optJSONObject("data")?.optString("backupMaterial")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Material de respaldo no disponible")
    }

    fun uploadEncryptedBackup(session: UserSession, encryptedBackup: JSONObject) {
        request(
            method = "PUT",
            path = "/api/premium/${session.id}/backup",
            authToken = session.authToken,
            body = JSONObject().put("backup", encryptedBackup)
        )
    }

    /** Restauración en un teléfono nuevo: el backend deja el hilo de continuidad (cuándo/dónde) para el próximo chat. */
    fun restoreEncryptedBackup(session: UserSession, device: String?): JSONObject? {
        val json = request(
            method = "POST",
            path = "/api/premium/${session.id}/backup/restore",
            authToken = session.authToken,
            body = JSONObject().put("dispositivo", device ?: JSONObject.NULL)
        )
        return json.optJSONObject("data")?.optJSONObject("backup")
    }

    fun downloadEncryptedBackup(session: UserSession): JSONObject? {
        val json = request(
            method = "GET",
            path = "/api/premium/${session.id}/backup",
            authToken = session.authToken
        )
        return json.optJSONObject("data")?.optJSONObject("backup")
    }

    /** Eventos de agenda del usuario (para recordatorios locales que suenan sin red). */
    fun listCalendar(session: UserSession): JSONArray {
        val json = request(method = "GET", path = "/api/calendario/${session.id}", authToken = session.authToken)
        return json.optJSONArray("data") ?: JSONArray()
    }

    /** Clima en las coordenadas actuales del teléfono (para el widget), p. ej. "23°C". */
    fun fetchWeatherLabel(session: UserSession, lat: Double, lon: Double): String? {
        val json = request(
            method = "GET",
            path = "/api/clima?lat=${"%.4f".format(java.util.Locale.US, lat)}&lon=${"%.4f".format(java.util.Locale.US, lon)}",
            authToken = session.authToken
        )
        return parseWeatherLabel(json.optJSONObject("data"))
    }

    fun listAlarms(session: UserSession): List<AlarmRecord> {
        val json = request(
            method = "GET",
            path = "/api/alarmas/${session.id}",
            authToken = session.authToken
        )
        return parseAlarmList(json.optJSONArray("data"))
    }

    fun createAlarm(session: UserSession, hour: String, title: String, message: String): AlarmRecord {
        val json = request(
            method = "POST",
            path = "/api/alarmas/${session.id}",
            authToken = session.authToken,
            body = JSONObject().apply {
                put("hora", hour)
                put("titulo", title)
                put("mensaje", message)
            }
        )
        return parseAlarmRecord(json.optJSONObject("data"))
            ?: throw IllegalStateException("Alarma inválida")
    }
    fun cancelAlarm(session: UserSession, alarmId: String) {
        request(
            method = "DELETE",
            path = "/api/alarmas/${session.id}/$alarmId",
            authToken = session.authToken
        )
    }

    fun reportAlarmEvent(session: UserSession, alarmId: String, stage: Int, state: String): AlarmEventResult {
        val json = request(
            method = "POST",
            path = "/api/alarmas/${session.id}/$alarmId/evento",
            authToken = session.authToken,
            body = JSONObject().apply {
                put("stage", stage)
                put("estado", state)
            }
        )
        val data = json.optJSONObject("data") ?: JSONObject()
        return AlarmEventResult(
            state = data.optString("estado", state),
            message = data.optString("mensaje").ifBlank { null },
            nextStage = data.optInt("stageSiguiente").takeIf { it > 0 },
            alarm = parseAlarmRecord(data.optJSONObject("alarma"))
        )
    }
    private fun parseAlarmList(array: JSONArray?): List<AlarmRecord> {
        if (array == null) return emptyList()
        val result = mutableListOf<AlarmRecord>()
        for (index in 0 until array.length()) {
            parseAlarmRecord(array.optJSONObject(index))?.let(result::add)
        }
        return result
    }

    private fun parseAlarmRecord(json: JSONObject?): AlarmRecord? {
        if (json == null || json.optString("id").isBlank()) return null
        val dispatchPlan = AlarmDispatchStage.parsePlan(json.optJSONArray("dispatchPlan"))
        return AlarmRecord(
            id = json.optString("id"),
            userId = json.optString("userID", json.optString("userId")),
            hour = json.optString("hora"),
            title = json.optString("titulo", "Hora de despertar"),
            message = json.optString("mensaje", "ME2 registró tu protocolo de despertar."),
            state = json.optString("estado", "ACTIVE"),
            stage = json.optInt("stage", 1),
            attempts = json.optInt("intentos", 0),
            dispatchPlan = dispatchPlan
        )
    }

    private fun parsePremiumMillis(data: JSONObject?): Long? {
        if (data == null) return null
        val parsed = parseIsoMillis(data.optString("premiumHasta"))
        if (parsed != null) return parsed
        return if (data.optBoolean("premiumActivo", false)) betaPremiumMillis else null
    }

    /**
     * Toda llamada autenticada usa el token vigente (Me2SessionRecovery) y, ante un 401, recupera la sesión en
     * silencio y reintenta una vez: nunca se le vuelve a pedir login al usuario. El logout no se reintenta.
     */
    private fun request(
        method: String,
        path: String,
        authToken: String? = null,
        body: JSONObject? = null
    ): JSONObject {
        if (path == LOGOUT_PATH) return requestOnce(method, path, Me2SessionRecovery.currentToken(authToken), body)
        return Me2SessionRecovery.withRecovery(authToken) { token -> requestOnce(method, path, token, body) }
    }

    private fun requestOnce(
        method: String,
        path: String,
        authToken: String?,
        body: JSONObject?
    ): JSONObject {
        check(isConfigured()) { "BACKEND_BASE_URL no configurada." }
        val sentElapsed = android.os.SystemClock.elapsedRealtime()
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (!authToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer ".plus(authToken))
            }
            doInput = true
            if (body != null) {
                doOutput = true
            }
        }

        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                }
            }

            val responseCode = connection.responseCode
            syncMe2Clock(connection, sentElapsed)
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val responseJson = responseText.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()

            if (responseCode !in 200..299) {
                val message = responseJson.optString("error").ifBlank { "Error HTTP $responseCode" }
                if (responseCode == 401) throw AuthRequiredException(message)
                throw HttpStatusException(responseCode, message)
            }
            return responseJson
        } finally {
            connection.disconnect()
        }
    }

    /** Reloj propio de ME2: cada respuesta del backend trae su hora (X-ME2-Server-Time; respaldo: header Date). */
    private fun syncMe2Clock(connection: HttpURLConnection, sentElapsed: Long) {
        runCatching {
            val received = android.os.SystemClock.elapsedRealtime()
            val serverMs = connection.getHeaderField("X-ME2-Server-Time")?.toLongOrNull()
            if (serverMs != null) {
                val procMs = connection.getHeaderField("X-ME2-Proc-Ms")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                // Se descuenta el procesamiento del servidor (p. ej. el LLM): solo la latencia de red cuenta como ida y vuelta.
                com.me2.android.time.Me2Clock.onServerTime(serverMs, sentElapsed + procMs, received)
            } else {
                val date = connection.getHeaderFieldDate("Date", 0L)
                if (date > 0L) com.me2.android.time.Me2Clock.onServerTime(date, sentElapsed, received, resolutionMs = 1000L)
            }
        }
    }

    private fun parseIsoMillis(rawValue: String?): Long? {
        if (rawValue.isNullOrBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX"
        )
        patterns.forEach { pattern ->
            try {
                return SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(rawValue)?.time
            } catch (_: ParseException) {
            }
        }
        return null
    }

    private companion object {
        const val LOGOUT_PATH = "/api/auth/logout"
    }
}
