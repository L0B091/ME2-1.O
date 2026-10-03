package com.me2.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import java.io.FileOutputStream
import java.io.File
import android.view.View
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import java.nio.ByteBuffer
import com.me2.android.ui.ChatMediaRouting
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.me2.android.config.ApiConfig
import com.me2.android.data.ChatMessage
import com.me2.android.data.LocalMemoryStore
import com.me2.android.data.UserIdMigration
import com.me2.android.data.PremiumBackupCrypto
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import com.me2.android.databinding.ActivityMainBinding
import com.me2.android.gallery.ClipCatalog
import com.me2.android.gallery.GalleryClip
import com.me2.android.media.AudiovisualCue
import com.me2.android.media.AvatarCueMapper
import com.me2.android.media.AvatarState
import com.me2.android.media.AvatarStateMachine
import com.me2.android.media.MediaCategoria
import com.me2.android.media.MediaLibrary
import com.me2.android.media.MediaPermisos
import com.me2.android.media.MediaRequest
import com.me2.android.media.MediaSelection
import com.me2.android.media.MediaSelector
import com.me2.android.media.MediaTipo
import com.me2.android.notifications.Me2AlarmStore
import com.me2.android.notifications.Me2InitiativeTimer
import com.me2.android.notifications.Me2SyncWorker
import com.me2.android.notifications.StoredAlarmRecord
import com.me2.android.net.Me2BackendClient
import com.me2.android.notifications.Me2AlarmScheduler
import com.me2.android.notifications.Me2NotificationChannels
import com.me2.android.notifications.Me2NotificationCoordinator
import com.me2.android.notifications.Me2InitiativeScheduler
import com.me2.android.notifications.Me2InitiativeStore
import com.me2.android.ui.ChatAdapter
import com.me2.android.ui.BitacoraContent
import com.me2.android.widget.Me2HomeWidgetProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "Me2Main"
        /** Post-presentation wait before silence check-in (product: ~45–60s). */
        private const val POST_PRESENTATION_SILENCE_MS = 50_000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionStorage: SessionStorage
    private lateinit var localMemoryStore: LocalMemoryStore
    private lateinit var alarmScheduler: Me2AlarmScheduler
    private lateinit var notificationCoordinator: Me2NotificationCoordinator
    private lateinit var initiativeStore: Me2InitiativeStore
    private lateinit var initiativeScheduler: Me2InitiativeScheduler
    private lateinit var chatAdapter: ChatAdapter

    private val fullConversation = mutableListOf<ChatMessage>()
    private val visibleConversation = mutableListOf<ChatMessage>()
    private val backendClient = Me2BackendClient()
    private val premiumBackupCrypto = PremiumBackupCrypto()

    private var player: ExoPlayer? = null
    private lateinit var clipCatalog: ClipCatalog
    private var currentAvatarClipId: String? = null
    private var lastAvatarClipId: String? = null
    private lateinit var mediaLibrary: MediaLibrary
    private var avatarMode: AvatarState = AvatarState.LOOP_NEUTRAL
    /** Pedido audiovisual activo (para repetir CONVERSACION/DESPERTADOR mientras corresponda). */
    private var currentRequest: MediaRequest? = null
    /** Modo adulto desbloqueado según la última respuesta del backend (solo en memoria, por sesión). */
    private var adultUnlockedNow: Boolean = false
    /** Historial anti-repetición del avatar persistido (sobrevive cierre/reinicio). */
    private lateinit var mediaHistory: com.me2.android.media.MediaHistory
    private var currentAvatarGallery: List<GalleryClip> = emptyList()
    private var hasPlayedPresentation = false
    private var presentationClipIndex: Int = 0
    private var presentationSequenceActive: Boolean = false
    private var sessionStartedAt: Long = 0L
    private lateinit var currentSession: UserSession
    private var startedFromEmptyLocalMemory: Boolean = false
    private var backupMaterial: String? = null
    private val checkInHandler = Handler(Looper.getMainLooper())
    private val checkInTick = object : Runnable {
        override fun run() {
            maybeAskSilenceCheckIn()
            checkInHandler.postDelayed(this, 30_000L)
        }
    }
    private val postPresentationSilenceRunnable = Runnable {
        askPostPresentationSilenceCheckIn()
    }

    // Biblioteca V1 (assets/ME2_MEDIA + filesDir/ME2_MEDIA, descubrimiento dinámico); res/raw vía ClipCatalog
    // queda solo como último recurso para que el contenedor del avatar nunca quede vacío.
    private val loopNeutralGallery: List<GalleryClip>
        get() = mediaLibrary.recursos()
            .filter { it.categoria == MediaCategoria.LOOP_NEUTRAL && it.tipo == MediaTipo.VIDEO && mediaPermisos().permite(it) }
            .map(mediaLibrary::toClip)
            .ifEmpty { clipCatalog.listByMood(ClipCatalog.MOOD_LOOP_NEUTRAL) }
    private val presentationGallery: List<GalleryClip>
        get() = MediaSelector.secuenciaPresentacion(mediaLibrary.recursos(), mediaPermisos())
            .map(mediaLibrary::toClip)
            .ifEmpty { clipCatalog.listByMood(ClipCatalog.MOOD_PRESENTACION) }

    private fun mediaPermisos(): MediaPermisos {
        val premium = ::currentSession.isInitialized && currentSession.isPremium
        return MediaPermisos(premium = premium, adulto = premium && adultUnlockedNow)
    }

    private fun selectMedia(request: MediaRequest): MediaSelection? =
        MediaSelector.select(mediaLibrary.recursos(), request, mediaPermisos(), previousId = currentMediaId() ?: mediaHistory.lastId())

    /** id V1 del clip actual ("me2:02_REACCIONES/ALEGRIA/ALEGRIA_MEDIO_001.mp4" → "ALEGRIA_MEDIO_001"). */
    private fun currentMediaId(): String? =
        currentAvatarClipId?.takeIf { it.startsWith("me2:") }?.substringAfterLast('/')?.substringBeforeLast('.')?.uppercase(Locale.ROOT)

    private val profilePhotoPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            runCatching { persistProfilePhoto(uri) }
                .onSuccess { refreshProfilePhoto() }
                .onFailure {
                    Log.e(TAG, "profile photo pick failed", it)
                    Toast.makeText(this, getString(R.string.editar_foto), Toast.LENGTH_SHORT).show()
                }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, getString(R.string.notification_permission_needed), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            applyVideoContainerMaxWidth()
        } catch (error: Throwable) {
            Log.e(TAG, "Main inflate failed", error)
            LoginActivity.markMainLaunchFailed(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        mediaLibrary = MediaLibrary(this)
        mediaHistory = com.me2.android.media.MediaHistory(this)
        clipCatalog = ClipCatalog(this).also { catalog ->
            runCatching { catalog.ensureDirs() }
            Log.i(TAG, "ClipCatalog ready; ApiConfig ${ApiConfig.readinessSummary()}")
        }
        sessionStorage = runCatching { SessionStorage(this) }.getOrElse {
            Log.e(TAG, "SessionStorage failed", it)
            LoginActivity.markMainLaunchFailed(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        var session = runCatching { sessionStorage.loadUser() }.getOrNull()
        // Harden demo path: if prefs raced/missed but Intent carries demo_preview, recreate session.
        if (session == null && intent.getBooleanExtra(LoginActivity.EXTRA_DEMO_PREVIEW, false)) {
            val demo = UserSession.demoPreview()
            sessionStorage.saveUserCommit(demo)
            session = demo
        }
        if (session == null) {
            LoginActivity.markMainLaunchFailed(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        currentSession = session

        try {
            localMemoryStore = LocalMemoryStore(this)
            alarmScheduler = Me2AlarmScheduler(this)
            notificationCoordinator = Me2NotificationCoordinator(this)
            initiativeStore = Me2InitiativeStore(this)
            initiativeScheduler = Me2InitiativeScheduler(this)

            runCatching { Me2NotificationChannels.ensure(this) }
            ensureNotificationPermission()

            setupToolbar()
            setupChat()
            setupBitacora(currentSession)
            reconcileBackendUserId()
            binding.videoContainer.clipToOutline = true
            runCatching { setupVideo() }.onFailure { Log.e(TAG, "setupVideo failed", it) }

            startedFromEmptyLocalMemory = runCatching {
                localMemoryStore.isEffectivelyEmpty(currentSession.id)
            }.getOrDefault(true)
            val launchedWithEvent = intent?.getStringExtra(Me2NotificationCoordinator.EXTRA_EVENT_TYPE) != null
            runCatching { hydrateConversation() }.onFailure { Log.e(TAG, "hydrateConversation failed", it) }
            if (!launchedWithEvent) runCatching { maybePlayPresentation() }
            runCatching {
                localMemoryStore.observe(currentSession.id).observe(this) { record ->
                    if (record != null) runCatching { hydrateConversation() }
                }
            }
            syncPremiumState()
            runCatching { handleIncomingIntent(intent) }
            // Alarmas/recordatorios/iniciativa: todo se re-arma desde disco (funciona offline y tras muerte del proceso).
            runCatching { alarmScheduler.restoreAll() }
            runCatching { Me2SyncWorker.enqueueIfPending(this) }
            syncBackendAlarms()
            runCatching {
                if (initiativeStore.isEnabled(currentSession.id)) {
                    Me2InitiativeTimer(this).restore(currentSession.id)
                    initiativeScheduler.ensureScheduled()
                    if (initiativeStore.snapshot(currentSession.id).optLong("ultimaInteraccion", 0L) <= 0L) {
                        initiativeStore.observeInteraction(currentSession.id)
                    }
                }
            }.onFailure { Log.e(TAG, "initiative bootstrap failed", it) }
            checkInHandler.postDelayed(checkInTick, 30_000L)
            // Mark launch stable after UI is up so a later crash does not immediately loop forever,
            // but a crash during onCreate leaves pending=true and Login stays put.
            checkInHandler.postDelayed({
                LoginActivity.markMainLaunchStable(this@MainActivity)
            }, 2_500L)
        } catch (error: Throwable) {
            Log.e(TAG, "Main onCreate failed", error)
            LoginActivity.markMainLaunchFailed(this)
            runCatching { sessionStorage.clear() }
            Toast.makeText(this, "ME2 no pudo abrir la UI. Volvé a intentar desde login.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        sessionStartedAt = SystemClock.elapsedRealtime()
        // Clips nuevos (drop-in en filesDir/ME2_MEDIA) se descubren sin tocar código.
        if (::mediaLibrary.isInitialized) runCatching { mediaLibrary.refrescar() }
        if (!presentationSequenceActive) {
            runCatching { restoreAvatarPresence(forceReload = currentAvatarClipId == null) }
        }
        player?.playWhenReady = true
        player?.volume = 1f
        if (::currentSession.isInitialized) {
            // Returning to foreground cancels a pending post-silence eval only if user is active;
            // observeInteraction is reserved for real chat/widget interactions.
            maybeAskSilenceCheckIn()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::sessionStorage.isInitialized) return
        if (sessionStartedAt > 0L) {
            val elapsedMinutes = ((SystemClock.elapsedRealtime() - sessionStartedAt) / 60000L).coerceAtLeast(0L)
            if (elapsedMinutes > 0L) {
                sessionStorage.addUsageMinutes(elapsedMinutes)
            }
        }
        player?.playWhenReady = false
        if (::currentSession.isInitialized &&
            ::initiativeStore.isInitialized &&
            ::initiativeScheduler.isInitialized &&
            sessionStorage.loadUser()?.id == currentSession.id &&
            runCatching { initiativeStore.isEnabled(currentSession.id) }.getOrDefault(false)
        ) {
            // Leaving the app starts the 1-hour countdown toward server eval (server may ESPERAR).
            runCatching {
                initiativeStore.markPostSilenceEvalArmed(currentSession.id)
                initiativeScheduler.schedulePostSilenceEval()
            }
        }
    }

    override fun onDestroy() {
        checkInHandler.removeCallbacks(checkInTick)
        checkInHandler.removeCallbacks(postPresentationSilenceRunnable)
        if (::binding.isInitialized) {
            runCatching { binding.playerView.player = null }
        }
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }

    private fun setupToolbar() {
        binding.drawerLayout.setScrimColor(ContextCompat.getColor(this, R.color.me2_drawer_scrim))
        // La fecha de la bitácora se recalcula cada vez que se abre el drawer (botón o deslizamiento).
        binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                runCatching { renderBitacoraTexts(sessionStorage.loadUser() ?: currentSession) }
            }
        })
        binding.bitacoraButton.setOnClickListener {
            runCatching { renderBitacoraTexts(sessionStorage.loadUser() ?: currentSession) }
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.clearChatButton.setOnClickListener {
            localMemoryStore.clearConversationDisplay(currentSession.id)
            initiativeStore.clearActiveContext(currentSession.id)
            visibleConversation.clear()
            chatAdapter.submitList(visibleConversation.toList())
            Toast.makeText(this, "PANTALLA LIMPIA", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupChat() {
        chatAdapter = ChatAdapter(gifLoader = ::loadChatGif)
        binding.chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatRecyclerView.adapter = chatAdapter
        binding.sendButton.setOnClickListener { sendMessage() }
    }

    private fun setupBitacora(session: UserSession) {
        renderBitacoraTexts(session)
        binding.homeWidgetSwitch.setOnCheckedChangeListener(null)
        binding.homeWidgetSwitch.isChecked = sessionStorage.isHomeWidgetEnabled()
        binding.homeWidgetSwitch.setOnCheckedChangeListener { _, checked ->
            sessionStorage.setHomeWidgetEnabled(checked)
            Me2HomeWidgetProvider.refreshAll(this)
            Toast.makeText(
                this,
                if (checked) getString(R.string.home_widget_on) else getString(R.string.home_widget_off),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.profilePhotoFrame.setOnClickListener { profilePhotoPicker.launch("image/*") }
        binding.editProfileButton.setOnClickListener { profilePhotoPicker.launch("image/*") }
        refreshProfilePhoto()

        binding.signOutButton.setOnClickListener {
            val shouldBackup =
                currentSession.isPremium &&
                    !currentSession.authToken.isNullOrBlank() &&
                    backendClient.isConfigured() &&
                    backendClient.isOnline(this)

            if (shouldBackup) {
                performPremiumBackup { completeSignOut() }
            } else {
                completeSignOut()
            }
        }
    }

    /** Bitácora: nombre, AVATAR // nombre (si se conoce), fecha local dd/MM/yyyy, MAIL y enlace con un decimal. Sin plan/premium. */
    private fun renderBitacoraTexts(session: UserSession) {
        val avatarName = runCatching { localMemoryStore.load(session.id).characterName }.getOrNull()
        val content = BitacoraContent.build(session.displayName, session.email, session.id, avatarName, session.usageMinutes)
        binding.userNameText.text = content.userName
        binding.avatarNameText.text = content.avatarLine.orEmpty()
        binding.avatarNameText.visibility = if (content.avatarLine == null) View.GONE else View.VISIBLE
        binding.nodeIdText.text = content.dateLine
        binding.mailText.text = content.mailLine
        binding.linkText.text = content.linkLabel
        binding.linkProgressBar.progress = content.linkProgressTenths
    }


    private fun setupVideo() {
        if (!::binding.isInitialized) return
        runCatching {
            val builder = ExoPlayer.Builder(this)
            runCatching {
                builder.javaClass.methods
                    .firstOrNull { it.name == "setUsePlatformDiagnostics" && it.parameterCount == 1 }
                    ?.invoke(builder, false)
            }
            player = builder.build().also { exoPlayer ->
                binding.playerView.player = exoPlayer
                binding.playerView.setKeepContentOnPlayerReset(true)
                binding.playerView.setShutterBackgroundColor(Color.TRANSPARENT)
                exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
                exoPlayer.volume = 1f
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            handleAvatarPlaybackEnded()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("Me2Avatar", "Fallo reproduccion avatar: ${error.errorCodeName}")
                        if (presentationSequenceActive && avatarMode == AvatarState.PRESENTACION) {
                            // Skip failed clip; continue sequence or finish so input is not stuck.
                            runCatching { handleAvatarPlaybackEnded() }
                        } else {
                            runCatching { fallbackToLoopNeutral(forceReload = true) }
                        }
                    }
                })
                fallbackToLoopNeutral(forceReload = true, resetStateLabel = true)
            }
        }.onFailure {
            Log.e(TAG, "ExoPlayer init failed", it)
            player = null
            runCatching {
            }
        }
    }

    private fun seedConversation() {
        if (fullConversation.isNotEmpty()) return
        val intro = localMemoryStore.load(currentSession.id).characterName
            ?.let { "Hola. Podés llamarme $it." }
            ?: "Hola. Estoy lista para acompañarte."
        fullConversation += ChatMessage(intro, true)
        localMemoryStore.appendAssistantMessage(currentSession.id, intro)
        visibleConversation += fullConversation
        renderConversation()
    }

    private fun hydrateConversation() {
        val memory = localMemoryStore.load(currentSession.id)
        if (memory.conversation.isEmpty()) {
            // Google first-interaction: leave chat empty (videos + silence). Demo keeps seed for UI preview.
            if (currentSession.isDemo) {
                seedConversation()
            } else {
                fullConversation.clear()
                visibleConversation.clear()
                renderConversation()
            }
            return
        }

        fullConversation.clear()
        visibleConversation.clear()
        memory.conversation.forEach { entry ->
            val text = if (entry.role == "user") {
                entry.text.uppercase(Locale.getDefault())
            } else {
                entry.text
            }
            val isMe2 = entry.role != "user"
            val message = ChatMessage(text, isMe2, reaction = if (isMe2) null else entry.reaction)
            fullConversation += message
            if (entry.timestamp > memory.hiddenConversationThrough) visibleConversation += message
        }
        if (avatarMode == AvatarState.LOOP_NEUTRAL) {
        }
        renderConversation()
    }

    private fun sendMessage() {
        if (!binding.messageInput.isEnabled) return
        val content = binding.messageInput.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) return
        cancelPostPresentationSilence()
        val initiative = initiativeStore.activeContext(currentSession.id)

        val visibleUserText = content.uppercase(Locale.getDefault())
        val userMessage = ChatMessage(visibleUserText, false)
        fullConversation += userMessage
        visibleConversation += userMessage
        localMemoryStore.appendUserMessage(currentSession.id, content)
        initiativeStore.observeInteraction(currentSession.id)
        initiativeScheduler.cancelPostSilenceEval()
        initiative?.let { initiativeStore.responded(currentSession.id, it.getString("id"), responseText = content) }
        binding.messageInput.text?.clear()
        // Protocolo despertador: la alarma solo se considera respondida cuando el usuario envía un INPUT en el Chat.
        answerPendingAlarms()
        runCatching { Me2InitiativeTimer(this).arm(currentSession.id) }
        // Mientras ME2 procesa: estado de conversación (PENSANDO → fallback neutral si no hay clip).
        playAvatarRequest(MediaRequest(MediaCategoria.CONVERSACION, "PENSANDO"))
        renderConversation()
        dispatchChat(content, initiative)
    }

    private fun renderConversation() {
        chatAdapter.submitList(visibleConversation.toList())
        binding.chatRecyclerView.post {
            if (chatAdapter.itemCount > 0) {
                binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }

    private fun maybeAskSilenceCheckIn() {
        if (!::currentSession.isInitialized) return
        if (!initiativeStore.isEnabled(currentSession.id)) return
        if (!initiativeStore.shouldAskCheckIn(currentSession.id)) return
        initiativeStore.markCheckInAsked(currentSession.id)
        appendSilenceCheckIn()
        // After check-in without response, arm 1h countdown toward server eval.
        initiativeStore.markPostSilenceEvalArmed(currentSession.id)
        initiativeScheduler.schedulePostSilenceEval()
    }

    /**
     * Check-in de silencio: la voz es de Dolphin. Con red no se escribe texto fijo (la evaluación de iniciativa del
     * servidor, ya armada, genera el mensaje con el LLM). Sin red: frase del banco offline aprobado.
     */
    private fun appendSilenceCheckIn() {
        if (backendClient.isConfigured() && backendClient.isOnline(this)) return
        val nombre = currentSession.displayName.trim().substringBefore(' ').ifBlank { null }
        val phrase = runCatching {
            com.me2.android.offline.OfflinePhrases(this).pick(com.me2.android.offline.OfflinePhraseBank.INICIO, mapOf("nombre" to nombre))
        }.getOrNull() ?: return
        localMemoryStore.appendAssistantMessage(currentSession.id, phrase.text)
        hydrateConversation()
    }

    private fun dispatchChat(content: String, initiative: JSONObject? = null) {
        val backendReady = backendClient.isConfigured() && backendClient.isOnline(this)
        // Demo opens without Google. If backend is up, still hit /chat so LLM can be tested.
        // If backend is down, keep a local demo reply (no crash).
        // Sin red (también en demo): frase offline del banco + mensaje marcado pendiente.
        if (!backendClient.isOnline(this)) {
            replyOffline()
            return
        }
        if (currentSession.isDemo && !backendReady) {
            appendAssistantReply(
                getString(R.string.demo_chat_notice),
                "DEMO",
                "DEMO_LOOP",
                typewriter = true
            )
            return
        }
        if (!currentSession.isDemo && currentSession.authToken.isNullOrBlank()) {
            replyOffline()
            return
        }
        if (!backendReady) {
            replyOffline()
            return
        }

        val memorySnapshot = localMemoryStore.load(currentSession.id)
        binding.sendButton.isEnabled = false
        thread {
            runCatching {
                backendClient.sendChat(currentSession, memorySnapshot, content, initiative)
            }.onSuccess { result ->
                runOnUiThread {
                    binding.sendButton.isEnabled = true
                    applyAdultModeFromChat(result)
                    adultUnlockedNow = result.adultMode?.unlocked == true
                    applyChatActions(result.actions)
                    result.memoryFacts?.let { facts ->
                        runCatching { localMemoryStore.applyBackendFacts(currentSession.id, facts.gustos, facts.disgustos, facts.ubicacion) }
                    }
                    runCatching { localMemoryStore.clearPendingMessages(currentSession.id) }
                    result.weatherLabel?.let { label ->
                        runCatching {
                            sessionStorage.saveLastTemperature(label)
                            Me2HomeWidgetProvider.refreshAll(this)
                        }
                    }
                    val intensity = result.adultMode?.intensity
                    val state = when {
                        !intensity.isNullOrBlank() && intensity != "none" && result.adultMode?.unlocked == true ->
                            "ADULT_${intensity.uppercase(Locale.getDefault())}"
                        else -> result.tone?.uppercase(Locale.getDefault()) ?: "ONLINE"
                    }
                    val detail = result.videoEtiqueta?.uppercase(Locale.getDefault())
                        ?: result.microExpression?.uppercase(Locale.getDefault())
                        ?: "SYNC"
                    // Modo adulto: la burbuja puede ser solo GIF (formato "gif"); fuera de él siempre hay texto.
                    if (result.reply.isNotBlank() || result.media == null) {
                        appendAssistantReply(result.reply, state, detail, typewriter = true, cue = result.audiovisual)
                    } else {
                        applyAssistantAvatarState(state, detail, result.audiovisual)
                    }
                    applyChatMedia(result)
                    startedFromEmptyLocalMemory = false
                    result.premiumUntilMillis?.let { premiumUntil ->
                        updateCurrentSession(currentSession.copy(premiumUntilMillis = premiumUntil))
                    }
                    // El link de pago llega dentro del texto del LLM (clickeable en la burbuja); la palabra
                    // clave del modo adulto no se guarda en el teléfono (el backend solo guarda su hash).
                }
            }.onFailure {
                runOnUiThread {
                    binding.sendButton.isEnabled = true
                    if (currentSession.isDemo) {
                        appendAssistantReply(
                            getString(R.string.demo_chat_notice),
                            "DEMO",
                            "DEMO_LOOP",
                            typewriter = true
                        )
                    } else {
                        replyOffline()
                    }
                }
            }
        }
    }

    /** Sin red: el mensaje del usuario queda guardado como pendiente y ME2 responde con el banco offline + clip. */
    private fun replyOffline() {
        val userId = currentSession.id
        val reply = com.me2.android.offline.OfflineChatResponder(
            pick = { cat, vars -> com.me2.android.offline.OfflinePhrases(this).pick(cat, vars) },
            markPending = { localMemoryStore.markLastUserMessagePending(userId) }
        ).respond(
            nombre = currentSession.displayName.trim().substringBefore(' ').ifBlank { null },
            fallbackText = getString(R.string.offline_memory_notice)
        )
        appendAssistantReply(reply.text, "OFFLINE", "LOCAL", cue = reply.cue)
    }

    private fun appendAssistantReply(
        reply: String,
        state: String,
        detail: String,
        typewriter: Boolean = false,
        cue: AudiovisualCue? = null
    ) {
        // Typewriter lo re-dispara el adapter en bind/tap para cualquier burbuja ME2.
        val me2Reply = ChatMessage(reply, true, animateTypewriter = true)
        fullConversation += me2Reply
        visibleConversation += me2Reply
        localMemoryStore.appendAssistantMessage(currentSession.id, reply)
        applyAssistantAvatarState(state, detail, cue)
        renderConversation()
    }

    /**
     * Reacción emoji → burbuja del usuario; clip adulto remoto → contenedor del avatar (solo video);
     * GIF → burbuja inline del avatar (solo modo adulto). Nunca GIF en el contenedor de video.
     */
    private fun applyChatMedia(result: com.me2.android.net.BackendChatResult) {
        val adultUnlocked = result.adultMode?.unlocked == true
        result.reaction?.let { emoji ->
            markLastUserReaction(fullConversation, emoji)
            markLastUserReaction(visibleConversation, emoji)
            runCatching { localMemoryStore.setReactionOnLastUserMessage(currentSession.id, emoji) }
        }
        ChatMediaRouting.avatarRemoteClipUrl(result.clip, adultUnlocked)?.let { playRemoteAvatarClip(it) }
        ChatMediaRouting.inlineGifUrl(result.media, adultUnlocked)?.let { url ->
            val gifBubble = ChatMessage("", true, gifUrl = url)
            fullConversation += gifBubble
            visibleConversation += gifBubble
        }
        renderConversation()
    }

    private fun markLastUserReaction(list: MutableList<ChatMessage>, emoji: String) {
        val idx = list.indexOfLast { !it.fromMe2 }
        if (idx >= 0) list[idx] = list[idx].copy(reaction = emoji)
    }

    private fun playRemoteAvatarClip(url: String) {
        val exoPlayer = player ?: return
        runCatching {
            val headers = currentSession.authToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
            val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
            exoPlayer.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url)))
            currentAvatarClipId = "remote:$url"
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }.onFailure { Log.w(TAG, "clip remoto no disponible: ${it.javaClass.simpleName}") }
    }

    private fun loadChatGif(url: String, target: ImageView) {
        target.tag = url
        val token = currentSession.authToken
        thread {
            val drawable = runCatching {
                val bytes = backendClient.fetchBytes(url, token)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                } else {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
                }
            }.getOrNull()
            runOnUiThread {
                if (target.tag != url || drawable == null) return@runOnUiThread
                target.setImageDrawable(drawable)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                }
            }
        }
    }

    private fun applyAdultModeFromChat(result: com.me2.android.net.BackendChatResult) {
        val adult = result.adultMode ?: return
        sessionStorage.saveAdultUnlocked(adult.unlocked)
        adult.intensity?.let { sessionStorage.saveAdultIntensity(it) }
    }

    private fun syncPremiumState() {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            return
        }

        thread {
            runCatching {
                backendClient.fetchPremiumStatus(currentSession)
            }.onSuccess { premium ->
                val updatedSession = currentSession.copy(
                    premiumUntilMillis = if (premium.active) premium.premiumUntilMillis else 0L
                )
                runOnUiThread {
                    updateCurrentSession(updatedSession)
                    backupMaterial = premium.backupMaterial
                    premium.adultMode?.let { adult ->
                        sessionStorage.saveAdultUnlocked(adult.unlocked)
                        adult.intensity?.let { sessionStorage.saveAdultIntensity(it) }
                    }
                    if (updatedSession.isPremium && startedFromEmptyLocalMemory) {
                        restorePremiumBackup(silent = true)
                    }
                }
            }
        }
    }

    private fun syncBackendAlarms() {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            return
        }
        thread {
            runCatching {
                backendClient.listAlarms(currentSession)
            }.onSuccess { alarms ->
                alarms.forEach { alarmScheduler.schedule(it) }
            }
            // Agenda → recordatorios locales (suenan sin red, título = texto del usuario).
            runCatching {
                val eventos = backendClient.listCalendar(currentSession)
                runOnUiThread { for (i in 0 until eventos.length()) eventos.optJSONObject(i)?.let(::scheduleReminderFrom) }
            }
            runCatching { Me2SyncWorker.enqueueIfPending(this) }
        }
    }

    private fun scheduleTestAlarm() {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            Toast.makeText(this, getString(R.string.alarm_requires_backend), Toast.LENGTH_SHORT).show()
            return
        }
        ensureNotificationPermission()
        if (!alarmScheduler.canScheduleExactAlarms()) {
            alarmScheduler.exactAlarmPermissionIntent()?.let(::startActivity)
            Toast.makeText(this, getString(R.string.exact_alarm_permission_needed), Toast.LENGTH_SHORT).show()
            return
        }

        val nextMinute = Calendar.getInstance().apply {
            add(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val hour = SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextMinute.time)

        thread {
            runCatching {
                val alarm = backendClient.createAlarm(
                    currentSession,
                    hour = hour,
                    title = getString(R.string.alarm_protocol_title),
                    message = getString(R.string.alarm_protocol_message)
                )
                alarmScheduler.schedule(alarm)
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, "${getString(R.string.alarm_test_scheduled)} $hour", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this, error.message ?: getString(R.string.alarm_requires_backend), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun cancelNextAlarm() {
        val nextAlarm = alarmScheduler.peekNextAlarm(currentSession.id)
        if (nextAlarm == null) {
            Toast.makeText(this, getString(R.string.alarm_none_active), Toast.LENGTH_SHORT).show()
            return
        }

        alarmScheduler.cancel(nextAlarm.id)
        notificationCoordinator.cancelAlarmNotifications(nextAlarm.id)

        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            Toast.makeText(this, getString(R.string.alarm_cancelled), Toast.LENGTH_SHORT).show()
            return
        }

        thread {
            runCatching {
                backendClient.cancelAlarm(currentSession, nextAlarm.id)
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.alarm_cancelled), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        restoreAvatarPresence()
        val eventType = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_EVENT_TYPE) ?: return
        val title = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_TITLE).orEmpty()
        val message = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_MESSAGE).orEmpty()
        val alarmId = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_ALARM_ID)
        val stage = intent.getIntExtra(Me2NotificationCoordinator.EXTRA_STAGE, 0)

        when (eventType) {
            "initiative" -> openInitiative(intent)

            "message" -> {
                appendAssistantReply(
                    message.ifBlank { getString(R.string.notification_message_opened) },
                    "NOTICE",
                    title.ifBlank { "MESSAGE" }
                )
            }

            "alarm" -> {
                appendAssistantReply(
                    message.ifBlank { getString(R.string.notification_alarm_opened) },
                    "NOTICE",
                    "STAGE_$stage"
                )
                if (!alarmId.isNullOrBlank()) {
                    // Abrir la notificación silencia el aviso actual, pero NO responde la alarma: los intentos
                    // siguientes siguen programados hasta que el usuario envíe un INPUT (ver sendMessage).
                    notificationCoordinator.cancelAlarmNotifications(alarmId)
                }
                // Frase offline: trae su pista audiovisual; si no, el intento de despertador correspondiente.
                val cue = cueFromIntent(intent)
                val sub = when (stage) { 1 -> "AVISO_01"; 2 -> "AVISO_02"; else -> "ALARMA" }
                playAvatarRequest(AvatarCueMapper.fromCue(cue) ?: MediaRequest(MediaCategoria.DESPERTADOR, sub))
            }
        }

        intent.removeExtra(Me2NotificationCoordinator.EXTRA_EVENT_TYPE)
    }

    private fun restoreAvatarPresence(forceReload: Boolean = false) {
        if (presentationSequenceActive) return
        if (player == null) {
            setupVideo()
            return
        }
        fallbackToLoopNeutral(
            forceReload = forceReload || !galleryContainsCurrentClip(loopNeutralGallery),
            resetStateLabel = currentAvatarClipId == null
        )
    }

    private fun maybePlayPresentation() {
        // First-interaction sequence: Google path only (demo keeps chat usable for UI preview).
        if (currentSession.isDemo) return
        if (hasPlayedPresentation || sessionStorage.isPresentationIntroCompleted()) {
            setChatInputEnabled(true)
            return
        }
        val memory = runCatching { localMemoryStore.load(currentSession.id) }.getOrNull()
        if (!com.me2.android.media.PresentationGate.shouldPlay(
                currentSession.isDemo, sessionStorage.isPresentationIntroCompleted(),
                memory?.presentationCompletedAt ?: 0L, memory?.conversation?.isEmpty() ?: true)) {
            markPresentationSeen()
            setChatInputEnabled(true)
            return
        }
        val gallery = presentationGallery
        val exoPlayer = player
        if (gallery.isEmpty() || exoPlayer == null) {
            // No clips: do not hard-lock input forever.
            setChatInputEnabled(true)
            return
        }
        hasPlayedPresentation = true
        presentationSequenceActive = true
        presentationClipIndex = 0
        setChatInputEnabled(false)
        avatarMode = AvatarState.PRESENTACION
        currentAvatarGallery = gallery
        playAvatarClip(exoPlayer, gallery.first())
    }

    /** Tablet (sw600dp): limita el ancho del contenedor de video para no recortar la cara con resize_mode=zoom. */
    private fun applyVideoContainerMaxWidth() {
        val maxWidth = resources.getDimensionPixelSize(R.dimen.video_container_max_width)
        if (maxWidth <= 0) return
        val params = binding.videoContainer.layoutParams
        params.width = maxWidth
        binding.videoContainer.layoutParams = params
    }

    private fun setChatInputEnabled(enabled: Boolean) {
        if (!::binding.isInitialized) return
        binding.messageInput.isEnabled = enabled
        binding.messageInput.isFocusable = enabled
        binding.messageInput.isFocusableInTouchMode = enabled
        binding.sendButton.isEnabled = enabled
        if (!enabled) {
            binding.messageInput.clearFocus()
        }
    }

    /** Flag persistido en prefs (commit) y en la memoria local (respaldo cifrado). */
    private fun markPresentationSeen() {
        sessionStorage.setPresentationIntroCompleted(true)
        runCatching { localMemoryStore.markPresentationCompleted(currentSession.id) }
    }

    private fun onPresentationSequenceCompleted() {
        presentationSequenceActive = false
        markPresentationSeen()
        setChatInputEnabled(true)
        runCatching { initiativeStore.observeInteraction(currentSession.id) }
        schedulePostPresentationSilence()
        fallbackToLoopNeutral(forceReload = true)
    }

    private fun schedulePostPresentationSilence() {
        cancelPostPresentationSilence()
        checkInHandler.postDelayed(postPresentationSilenceRunnable, POST_PRESENTATION_SILENCE_MS)
    }

    private fun cancelPostPresentationSilence() {
        checkInHandler.removeCallbacks(postPresentationSilenceRunnable)
    }

    private fun askPostPresentationSilenceCheckIn() {
        if (!::currentSession.isInitialized || !::localMemoryStore.isInitialized) return
        if (presentationSequenceActive) return
        // Skip if user already wrote (or silence already stored).
        val memory = runCatching { localMemoryStore.load(currentSession.id) }.getOrNull() ?: return
        val hasUserMessage = memory.conversation.any { it.role == "user" }
        if (hasUserMessage) return
        if (::initiativeStore.isInitialized) {
            runCatching { initiativeStore.markCheckInAsked(currentSession.id) }
        }
        appendSilenceCheckIn()
        if (::initiativeStore.isInitialized && ::initiativeScheduler.isInitialized) {
            runCatching {
                initiativeStore.markPostSilenceEvalArmed(currentSession.id)
                initiativeScheduler.schedulePostSilenceEval()
            }
        }
    }

    private fun applyAssistantAvatarState(state: String, detail: String, cue: AudiovisualCue? = null) {
        val normalizedState = state.uppercase(Locale.getDefault())
        // Modo adulto: 07_PREMIUM/ADULTO solo con permiso; si no hay recurso, la reacción COQUETA equivalente.
        if (normalizedState.startsWith("ADULT_")) {
            val adult = AvatarCueMapper.adultRequest(normalizedState)
            if (adult != null) {
                val selection = selectMedia(adult)
                if (selection != null) playAvatarSelection(adult, selection)
                else playAvatarRequest(AvatarCueMapper.adultFallback(adult))
                return
            }
        }
        val request = AvatarCueMapper.fromCue(cue) ?: AvatarCueMapper.fromLegacy(state, detail)
        if (request == null) {
            fallbackToLoopNeutral(forceReload = !galleryContainsCurrentClip(loopNeutralGallery))
            return
        }
        playAvatarRequest(request)
    }

    /** Pide categoría+intensidad al selector; si no hay nada válido, vuelve a LOOP_NEUTRAL (nunca vacío). */
    private fun playAvatarRequest(request: MediaRequest) {
        if (presentationSequenceActive) return
        val selection = selectMedia(request)
        if (selection == null) {
            fallbackToLoopNeutral(forceReload = !galleryContainsCurrentClip(loopNeutralGallery))
            return
        }
        playAvatarSelection(request, selection)
    }

    private fun playAvatarSelection(request: MediaRequest, selection: MediaSelection) {
        val exoPlayer = player ?: return
        val recurso = selection.recurso
        if (recurso.categoria == MediaCategoria.LOOP_NEUTRAL) {
            avatarMode = AvatarState.LOOP_NEUTRAL
            currentRequest = null
            currentAvatarGallery = loopNeutralGallery
        } else {
            avatarMode = when (recurso.categoria) {
                MediaCategoria.CONVERSACION -> AvatarState.CONVERSACION
                MediaCategoria.DESPERTADOR -> AvatarState.DESPERTADOR
                MediaCategoria.SISTEMA -> AvatarState.SISTEMA
                else -> AvatarState.REACCION
            }
            currentRequest = request
            currentAvatarGallery = listOf(mediaLibrary.toClip(recurso))
        }
        playAvatarClip(exoPlayer, mediaLibrary.toClip(recurso))
    }

    private fun handleAvatarPlaybackEnded() {
        val gallery = currentAvatarGallery.ifEmpty { presentationGallery }
        val transition = AvatarStateMachine.onClipEnded(
            avatarMode,
            presentationHasNext = avatarMode == AvatarState.PRESENTACION && presentationClipIndex + 1 < gallery.size,
            alarmPending = runCatching { alarmScheduler.hasAnswerable(currentSession.id) }.getOrDefault(false)
        )
        when {
            transition.advancePresentation -> {
                val exoPlayer = player ?: run {
                    onPresentationSequenceCompleted()
                    return
                }
                presentationClipIndex += 1
                playAvatarClip(exoPlayer, gallery[presentationClipIndex])
            }
            transition.presentationCompleted -> onPresentationSequenceCompleted()
            transition.next == AvatarState.CONVERSACION || transition.next == AvatarState.DESPERTADOR ->
                currentRequest?.let { playAvatarRequest(it) } ?: fallbackToLoopNeutral(forceReload = true)
            avatarMode == AvatarState.LOOP_NEUTRAL -> {
                val exoPlayer = player ?: return
                playAvatarClip(exoPlayer, pickNextClip(currentAvatarGallery, currentAvatarClipId))
            }
            else -> fallbackToLoopNeutral(forceReload = true)
        }
    }

    private fun fallbackToLoopNeutral(forceReload: Boolean = false, resetStateLabel: Boolean = false) {
        avatarMode = AvatarState.LOOP_NEUTRAL
        currentRequest = null
        currentAvatarGallery = loopNeutralGallery
        if (resetStateLabel) {
        }
        ensureAvatarPlayback(forceReload = forceReload)
    }

    private fun ensureAvatarPlayback(forceReload: Boolean = false) {
        val exoPlayer = player ?: return
        val gallery = currentAvatarGallery.ifEmpty { loopNeutralGallery }
        val fallback = gallery.firstOrNull() ?: loopNeutralGallery.firstOrNull() ?: return
        val desiredClip = when {
            forceReload -> pickNextClip(gallery, currentAvatarClipId)
            currentAvatarClipId == null -> pickNextClip(gallery, lastAvatarClipId)
            !galleryContainsCurrentClip(gallery) -> pickNextClip(gallery, currentAvatarClipId)
            else -> gallery.firstOrNull { it.id == currentAvatarClipId } ?: fallback
        }
        if (!forceReload && desiredClip.id == currentAvatarClipId) {
            exoPlayer.playWhenReady = true
            return
        }
        playAvatarClip(exoPlayer, desiredClip)
    }

    private fun playAvatarClip(exoPlayer: ExoPlayer, clip: GalleryClip) {
        lastAvatarClipId = currentAvatarClipId
        currentAvatarClipId = clip.id
        runCatching { mediaHistory.record(clip.id) }
        // Voice only on welcome/presentacion; mute spoken risk on other moods if tagged.
        exoPlayer.volume = if (clip.carriesVoice || clip.mood == ClipCatalog.MOOD_PRESENTACION) 1f else 1f
        exoPlayer.setMediaItem(MediaItem.fromUri(clipCatalog.playbackUri(clip)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun galleryContainsCurrentClip(gallery: List<GalleryClip>): Boolean =
        clipCatalog.containsClip(gallery, currentAvatarClipId)

    /** Anti-repetición inmediata (también entre sesiones: último clip persistido). */
    private fun resolveNextClip(gallery: List<GalleryClip>, previousClipId: String?): GalleryClip? {
        val prev = previousClipId ?: runCatching { mediaHistory.lastClipId() }.getOrNull()
        return com.me2.android.gallery.ClipPicker.pickRandom(gallery, gallery.firstOrNull { it.id == prev })
    }

    private fun pickNextClip(gallery: List<GalleryClip>, previousClipId: String?): GalleryClip =
        resolveNextClip(gallery, previousClipId)
            ?: resolveNextClip(loopNeutralGallery, previousClipId)
            ?: error("ClipCatalog has no demo clips; res/raw fallbacks missing")

    private fun openInitiative(intent: Intent) {
        val userId = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_USER_ID)
        val id = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_INITIATIVE_ID)
        if (userId != currentSession.id || id.isNullOrBlank()) {
            Log.w("Me2Initiative", "Aviso de otra sesion o sin identificador")
            Toast.makeText(this, R.string.initiative_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val initiative = initiativeStore.find(userId, id)
            if (initiative == null || initiative.optString("estado") == "CANCELADA") {
                Toast.makeText(this, R.string.initiative_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            if (initiative.isNull("abierta")) {
                localMemoryStore.appendInitiativeMessage(userId, id, initiative.getString("mensaje"))
            }
            initiativeStore.opened(userId, id)
            notificationCoordinator.cancelInitiative(userId, id)
            hydrateConversation()
            // Iniciativa del banco offline: pide al selector el clip de su pista (fallback normal, nunca vacío).
            initiative.optJSONObject("contexto")?.optJSONObject("audiovisual")?.let { av ->
                playAvatarRequest(AvatarCueMapper.fromCue(AudiovisualCue(av.optString("categoria"), av.optString("subcategoria").ifBlank { null }, av.optString("intensidad").ifBlank { null }))
                    ?: MediaRequest(MediaCategoria.LOOP_NEUTRAL))
            }
        } catch (error: Exception) {
            Log.e("Me2Initiative", "No se pudo abrir el contexto: ${error.javaClass.simpleName}")
            Toast.makeText(this, R.string.initiative_unavailable, Toast.LENGTH_LONG).show()
        }
    }



    private fun cueFromIntent(intent: Intent): AudiovisualCue? {
        val cat = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_AV_CATEGORIA)?.ifBlank { null } ?: return null
        return AudiovisualCue(cat, intent.getStringExtra(Me2NotificationCoordinator.EXTRA_AV_SUBCATEGORIA), intent.getStringExtra(Me2NotificationCoordinator.EXTRA_AV_INTENSIDAD))
    }

    /**
     * Protocolo despertador: el INPUT del usuario responde las escalaciones en curso (estado persistido).
     * Con red se informa al backend (devuelve el clima); sin red queda pendiente de sync y se muestra el clima cacheado.
     */
    private fun answerPendingAlarms() {
        val answered = runCatching { alarmScheduler.answerActive(currentSession.id) }.getOrDefault(emptyList())
        if (answered.isEmpty()) return
        answered.forEach { notificationCoordinator.cancelAlarmNotifications(it.id) }
        val online = !currentSession.authToken.isNullOrBlank() && backendClient.isConfigured() && backendClient.isOnline(this)
        if (online) {
            answered.filter { it.syncState == StoredAlarmRecord.SYNC_ANSWER && it.remoteId != null }.forEach { r ->
                resolveAlarmEvent(r.remoteId!!, r.firedStage) { Me2AlarmStore(this).remove(r.id) }
            }
            Me2SyncWorker.enqueueIfPending(this)
        } else {
            Me2SyncWorker.enqueueIfPending(this)
            val cached = sessionStorage.loadLastTemperature()
            if (cached.any(Char::isDigit)) appendAssistantReply(cached, "NOTICE", "CLIMA")
        }
    }

    /** Acciones del turno: alarma creada/cancelada en el teléfono, alarma del servidor, evento → recordatorio local. */
    private fun applyChatActions(actions: JSONObject?) {
        actions ?: return
        runCatching {
            actions.optJSONObject("alarma")?.let { a ->
                when (a.optString("accion")) {
                    "crear_local" -> {
                        val hora = a.optString("hora")
                        if (hora.isNotBlank()) alarmScheduler.createLocalAlarm(currentSession.id, hora, a.optString("titulo").takeIf { !a.isNull("titulo") }.orEmpty())
                    }
                    "cancelar_local" -> alarmScheduler.findByHour(currentSession.id, a.optString("hora").takeIf { !a.isNull("hora") })
                        .forEach { alarmScheduler.cancelAndSync(it.id); notificationCoordinator.cancelAlarmNotifications(it.id) }
                    "creada" -> syncBackendAlarms()
                }
            }
            actions.optJSONObject("evento")?.optJSONObject("evento")?.let(::scheduleReminderFrom)
            // Premium local: el estado nuevo (fiscal | proyectos) queda en la memoria local del teléfono (y en el respaldo).
            actions.optJSONObject("premium")?.let { p ->
                val estado = p.optJSONObject("estado")
                if (p.optString("persistidoEn") == "telefono" && estado != null) localMemoryStore.savePremiumModule(currentSession.id, p.optString("modulo"), estado)
            }
            Me2SyncWorker.enqueueIfPending(this)
        }.onFailure { Log.w(TAG, "acciones: ${it.javaClass.simpleName}") }
    }

    private fun scheduleReminderFrom(evento: JSONObject) {
        val at = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse("${evento.optString("fecha")} ${evento.optString("hora", "08:00")}")?.time
        }.getOrNull() ?: return
        alarmScheduler.scheduleReminder(currentSession.id, evento.optString("id").ifBlank { null }, evento.optString("descripcion"), at)
    }

    private fun resolveAlarmEvent(alarmId: String, stage: Int, onReported: () -> Unit = {}) {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            return
        }
        thread {
            runCatching {
                backendClient.reportAlarmEvent(currentSession, alarmId, stage, "respondio")
            }.onSuccess {
                // Sin texto fijo en el chat: la respuesta a la alarma la redacta Dolphin en el turno del usuario.
                runCatching(onReported)
            }
        }
    }

    /**
     * Sesiones guardadas por versiones anteriores tienen como id el de la cuenta de Google (no el del backend) y
     * todas las rutas /api/.../:userId responden 403. Se consulta /api/auth/me y se migra lo local al userId real.
     */
    private fun reconcileBackendUserId() {
        val session = currentSession
        val token = session.authToken
        if (session.isDemo || token.isNullOrBlank() || !backendClient.isConfigured()) return
        thread {
            val backendId = runCatching { backendClient.fetchAuthenticatedUserId(token) }.getOrNull() ?: return@thread
            if (backendId == session.id) return@thread
            UserIdMigration.migrate(this, session.id, backendId)
            runOnUiThread {
                if (currentSession.id != session.id) return@runOnUiThread
                updateCurrentSession(currentSession.copy(id = backendId))
                runCatching { hydrateConversation() }
            }
        }
    }

    private fun updateCurrentSession(session: UserSession) {
        currentSession = session
        sessionStorage.saveUser(session)
        setupBitacora(session)
    }

    private fun completeSignOut() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(this, options)
            .signOut()
            .addOnCompleteListener {
                initiativeScheduler.cancel()
                initiativeStore.clearActiveContext(currentSession.id)
                initiativeStore.records(currentSession.id).forEach {
                    notificationCoordinator.cancelInitiative(currentSession.id, it.getString("id"))
                    initiativeStore.cancelled(currentSession.id, it.getString("id"))
                }
                sessionStorage.clear()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
    }

    private fun performPremiumBackup(onComplete: (() -> Unit)? = null) {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            Toast.makeText(this, getString(R.string.premium_backup_failed), Toast.LENGTH_SHORT).show()
            onComplete?.invoke()
            return
        }

        thread {
            runCatching {
                val memory = localMemoryStore.load(currentSession.id)
                val material = backupMaterial ?: backendClient.fetchBackupMaterial(currentSession)
                backupMaterial = material
                val encrypted = premiumBackupCrypto.encrypt(currentSession, memory, material)
                // Hilo en tiempo y espacio (metadato en claro: solo fecha + zona; la ciudad la completa el backend).
                encrypted.put("continuidad", JSONObject().apply {
                    memory.lastInteractionAt()?.let { put("ultimaInteraccionAt", it) }
                    put("lugar", JSONObject().put("zonaHoraria", java.util.TimeZone.getDefault().id))
                })
                backendClient.uploadEncryptedBackup(currentSession, encrypted)
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.premium_backup_done), Toast.LENGTH_SHORT).show()
                    onComplete?.invoke()
                }
            }.onFailure {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.premium_backup_failed), Toast.LENGTH_SHORT).show()
                    onComplete?.invoke()
                }
            }
        }
    }

    private fun restorePremiumBackup(silent: Boolean = false) {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            if (!silent) {
                Toast.makeText(this, getString(R.string.premium_restore_failed), Toast.LENGTH_SHORT).show()
            }
            return
        }

        thread {
            runCatching {
                val payload = runCatching { backendClient.restoreEncryptedBackup(currentSession, Build.MODEL) }
                    .getOrElse { backendClient.downloadEncryptedBackup(currentSession) }
                val material = backupMaterial ?: backendClient.fetchBackupMaterial(currentSession)
                backupMaterial = material
                payload?.let { premiumBackupCrypto.decrypt(currentSession, it, material) }
            }.onSuccess { memory ->
                runOnUiThread {
                    when {
                        memory == null && !silent -> {
                            Toast.makeText(this, getString(R.string.premium_restore_empty), Toast.LENGTH_SHORT).show()
                        }

                        memory != null -> {
                            localMemoryStore.replace(memory.copy(userId = currentSession.id))
                            // Teléfono nuevo: si el respaldo trae relación previa, la presentación no vuelve a sonar.
                            if (com.me2.android.media.PresentationGate.alreadySeen(memory.presentationCompletedAt, memory.conversation.isEmpty())) {
                                markPresentationSeen()
                                if (presentationSequenceActive) {
                                    presentationSequenceActive = false
                                    avatarMode = AvatarState.LOOP_NEUTRAL
                                    setChatInputEnabled(true)
                                    fallbackToLoopNeutral(forceReload = true)
                                }
                            }
                            startedFromEmptyLocalMemory = false
                            hydrateConversation()
                            if (!silent) {
                                Toast.makeText(this, getString(R.string.premium_restore_done), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }.onFailure {
                if (!silent) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.premium_restore_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun refreshProfilePhoto() {
        if (!::binding.isInitialized || !::currentSession.isInitialized) return
        val path = currentSession.photoUrl
        val local = path?.takeIf { it.startsWith("/") || it.startsWith("file:") }?.let { raw ->
            val file = if (raw.startsWith("file:")) File(Uri.parse(raw).path ?: return@let null) else File(raw)
            file.takeIf { it.isFile }
        }
        if (local != null) {
            val bitmap = BitmapFactory.decodeFile(local.absolutePath)
            if (bitmap != null) {
                binding.profileImage.setImageBitmap(bitmap)
                binding.profileImage.visibility = View.VISIBLE
                binding.editProfileButton.visibility = View.GONE
                return
            }
        }
        // Sin foto del usuario: solo marco + lápiz (nunca logo ME2).
        binding.profileImage.setImageDrawable(null)
        binding.profileImage.visibility = View.GONE
        binding.editProfileButton.visibility = View.VISIBLE
    }

    private fun persistProfilePhoto(uri: Uri) {
        val dest = File(filesDir, "profile_photo.jpg")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: error("no input stream")
        currentSession = currentSession.copy(photoUrl = dest.absolutePath)
        sessionStorage.saveUser(currentSession)
    }

}
