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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.me2.android.config.ApiConfig
import com.me2.android.data.ChatMessage
import com.me2.android.data.LocalMemoryStore
import com.me2.android.data.PremiumBackupCrypto
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import com.me2.android.databinding.ActivityMainBinding
import com.me2.android.gallery.ClipCatalog
import com.me2.android.gallery.GalleryClip
import com.me2.android.net.Me2BackendClient
import com.me2.android.notifications.Me2AlarmScheduler
import com.me2.android.notifications.Me2NotificationChannels
import com.me2.android.notifications.Me2NotificationCoordinator
import com.me2.android.notifications.Me2InitiativeScheduler
import com.me2.android.notifications.Me2InitiativeStore
import com.me2.android.ui.ChatAdapter
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
    private enum class AvatarMode { LOOP_NEUTRAL, CONTEXTUAL, PRESENTATION }

    private data class AvatarSelection(
        val label: String,
        val gallery: List<GalleryClip>
    )

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
    private var avatarMode: AvatarMode = AvatarMode.LOOP_NEUTRAL
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

    // Mood galleries via ClipCatalog: filesDir/gallery → assets/videos → res/raw demos.
    private val loopNeutralGallery: List<GalleryClip>
        get() = clipCatalog.listByMood(ClipCatalog.MOOD_LOOP_NEUTRAL)
    private val presentationGallery: List<GalleryClip>
        get() = clipCatalog.listByMood(ClipCatalog.MOOD_PRESENTACION)
    private val calidaGallery: List<GalleryClip>
        get() = clipCatalog.listByMood(ClipCatalog.MOOD_CALIDA)
    private val alegreGallery: List<GalleryClip>
        get() = clipCatalog.listByMood(ClipCatalog.MOOD_ALEGRE)
    private val atentaGallery: List<GalleryClip>
        get() = clipCatalog.listByMood(ClipCatalog.MOOD_ATENTA)
    private val aliviadaGallery: List<GalleryClip>
        get() = clipCatalog.listByMood(ClipCatalog.MOOD_ALIVIADA)
    private val agradecidaGallery: List<GalleryClip>
        get() = clipCatalog.listByMood(ClipCatalog.MOOD_AGRADECIDA)


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
        } catch (error: Throwable) {
            Log.e(TAG, "Main inflate failed", error)
            LoginActivity.markMainLaunchFailed(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

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
            syncBackendAlarms()
            runCatching {
                if (initiativeStore.isEnabled(currentSession.id)) {
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
        binding.bitacoraButton.setOnClickListener {
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
        chatAdapter = ChatAdapter()
        binding.chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatRecyclerView.adapter = chatAdapter
        binding.sendButton.setOnClickListener { sendMessage() }
    }

    private fun setupBitacora(session: UserSession) {
        binding.userNameText.text = "MAIL // ${session.email.uppercase(Locale.getDefault())}"
        val planTag = when {
            session.isDemo -> getString(R.string.demo_session_plan)
            session.isPremium -> "PREMIUM"
            else -> "FREE"
        }
        binding.userIdText.text = "PLAN // $planTag"
        binding.linkText.text = "ENLACE PSICOLÓGICO // ${sessionStorage.linkPercentage(session)}%"
        val planLabel = when {
            session.isDemo -> getString(R.string.demo_session_plan)
            session.isPremium -> "ESTABLE (PREMIUM)"
            else -> "ESTABLE (FREE)"
        }
        binding.statusText.text = "ESTADO // $planLabel"
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

        binding.premiumButton.setOnClickListener {
            showPremiumDialog()
        }
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
                        if (presentationSequenceActive && avatarMode == AvatarMode.PRESENTATION) {
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
            val message = ChatMessage(text, isMe2)
            fullConversation += message
            if (entry.timestamp > memory.hiddenConversationThrough) visibleConversation += message
        }
        if (avatarMode == AvatarMode.LOOP_NEUTRAL) {
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
        fallbackToLoopNeutral(forceReload = !galleryContainsCurrentClip(loopNeutralGallery))
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

    private fun showPremiumDialog() {
        val premiumCopy = if (currentSession.isPremium) {
            getString(R.string.adult_mode_active_copy)
        } else {
            getString(R.string.adult_mode_premium_copy)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("PREMIUM ME2")
            .setMessage(premiumCopy)

        if (currentSession.isPremium && !currentSession.authToken.isNullOrBlank()) {
            builder
                .setPositiveButton(getString(R.string.premium_backup)) { _, _ ->
                    performPremiumBackup()
                }
                .setNeutralButton(getString(R.string.premium_tools)) { _, _ ->
                    showPremiumToolsDialog()
                }
                .setNegativeButton(getString(R.string.premium_restore)) { _, _ ->
                    restorePremiumBackup()
                }
        } else {
            builder
                .setPositiveButton(getString(R.string.premium_checkout)) { _, _ ->
                    openMercadoPagoCheckout()
                }
                .setNegativeButton("CERRAR", null)
        }

        builder.show()
    }

    private fun showPremiumToolsDialog() {
        if (!currentSession.isPremium) {
            Toast.makeText(this, getString(R.string.premium_required), Toast.LENGTH_SHORT).show()
            return
        }
        val memory = localMemoryStore.load(currentSession.id)
        val summary = buildString {
            append("CÓDIGO: ${memory.codeMemories.size} recuerdos\n")
            append("FISCAL: ${memory.fiscalMemories.size} recuerdos\n")
            append("TODO: envío de mail al contador / captura de imágenes fiscales pendiente en backend.")
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.premium_tools))
            .setMessage(summary)
            .setPositiveButton("CERRAR", null)
            .show()
    }

    private fun openMercadoPagoCheckout() {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured()) {
            Toast.makeText(this, getString(R.string.premium_checkout_unavailable), Toast.LENGTH_LONG).show()
            return
        }
        if (!backendClient.isOnline(this)) {
            Toast.makeText(this, getString(R.string.premium_checkout_offline), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.premium_checkout_starting), Toast.LENGTH_SHORT).show()
        thread {
            runCatching {
                backendClient.createMercadoPagoCheckout(currentSession)
            }.onSuccess { checkout ->
                runOnUiThread {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(checkout.initPoint)))
                    // After returning, refresh premium status (verify endpoint may be unconfigured).
                    syncPremiumState()
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.premium_checkout_unavailable),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun maybeAskSilenceCheckIn() {
        if (!::currentSession.isInitialized) return
        if (!initiativeStore.isEnabled(currentSession.id)) return
        if (!initiativeStore.shouldAskCheckIn(currentSession.id)) return
        initiativeStore.markCheckInAsked(currentSession.id)
        val prompt = getString(R.string.silence_check_in)
        localMemoryStore.appendAssistantMessage(currentSession.id, prompt)
        hydrateConversation()
        // After check-in without response, arm 1h countdown toward server eval.
        initiativeStore.markPostSilenceEvalArmed(currentSession.id)
        initiativeScheduler.schedulePostSilenceEval()
    }

    private fun dispatchChat(content: String, initiative: JSONObject? = null) {
        val backendReady = backendClient.isConfigured() && backendClient.isOnline(this)
        // Demo opens without Google. If backend is up, still hit /chat so LLM can be tested.
        // If backend is down, keep a local demo reply (no crash).
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
            appendAssistantReply(getString(R.string.offline_memory_notice), "OFFLINE", "LOCAL")
            return
        }
        if (!backendReady) {
            appendAssistantReply(getString(R.string.offline_memory_notice), "OFFLINE", "LOCAL")
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
                    val intensity = result.adultMode?.intensity
                    val state = when {
                        !intensity.isNullOrBlank() && intensity != "none" && result.adultMode?.unlocked == true ->
                            "ADULT_${intensity.uppercase(Locale.getDefault())}"
                        else -> result.tone?.uppercase(Locale.getDefault()) ?: "ONLINE"
                    }
                    val detail = result.videoEtiqueta?.uppercase(Locale.getDefault())
                        ?: result.microExpression?.uppercase(Locale.getDefault())
                        ?: "SYNC"
                    appendAssistantReply(result.reply, state, detail, typewriter = true)
                    startedFromEmptyLocalMemory = false
                    result.premiumUntilMillis?.let { premiumUntil ->
                        updateCurrentSession(currentSession.copy(premiumUntilMillis = premiumUntil))
                    }
                    result.checkoutInitPoint?.takeIf { it.isNotBlank() }?.let { initPoint ->
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(initPoint)))
                        }
                    }
                    // Persist keyword if ME2 just assigned one in the reply.
                    extractAdultKeyword(result.reply)?.let { keyword ->
                        sessionStorage.saveAdultKeyword(keyword)
                    }
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
                        appendAssistantReply(getString(R.string.offline_memory_notice), "OFFLINE", "LOCAL")
                    }
                }
            }
        }
    }

    private fun appendAssistantReply(
        reply: String,
        state: String,
        detail: String,
        typewriter: Boolean = false
    ) {
        // Typewriter lo re-dispara el adapter en bind/tap para cualquier burbuja ME2.
        val me2Reply = ChatMessage(reply, true, animateTypewriter = true)
        fullConversation += me2Reply
        visibleConversation += me2Reply
        localMemoryStore.appendAssistantMessage(currentSession.id, reply)
        applyAssistantAvatarState(state, detail)
        renderConversation()
    }

    private fun applyAdultModeFromChat(result: com.me2.android.net.BackendChatResult) {
        val adult = result.adultMode ?: return
        sessionStorage.saveAdultUnlocked(adult.unlocked)
        adult.intensity?.let { sessionStorage.saveAdultIntensity(it) }
    }

    private fun extractAdultKeyword(reply: String): String? {
        val patterns = listOf(
            Regex("""palabra clave para modo adulto será:\s*([A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+)""", RegexOption.IGNORE_CASE),
            Regex("""palabra clave para modo adulto sera:\s*([A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+)""", RegexOption.IGNORE_CASE)
        )
        for (re in patterns) {
            val m = re.find(reply) ?: continue
            return m.groupValues.getOrNull(1)?.lowercase(Locale.getDefault())
        }
        return null
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
                    "AWAKE",
                    "STAGE_$stage"
                )
                if (!alarmId.isNullOrBlank()) {
                    alarmScheduler.cancel(alarmId)
                    notificationCoordinator.cancelAlarmNotifications(alarmId)
                    resolveAlarmEvent(alarmId, stage)
                }
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
        val conversationEmpty = runCatching {
            localMemoryStore.load(currentSession.id).conversation.isEmpty()
        }.getOrDefault(true)
        if (!conversationEmpty) {
            sessionStorage.setPresentationIntroCompleted(true)
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
        avatarMode = AvatarMode.PRESENTATION
        currentAvatarGallery = gallery
        playAvatarClip(exoPlayer, gallery.first())
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

    private fun onPresentationSequenceCompleted() {
        presentationSequenceActive = false
        sessionStorage.setPresentationIntroCompleted(true)
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
        val prompt = getString(R.string.silence_check_in)
        localMemoryStore.appendAssistantMessage(currentSession.id, prompt)
        hydrateConversation()
        if (::initiativeStore.isInitialized && ::initiativeScheduler.isInitialized) {
            runCatching {
                initiativeStore.markPostSilenceEvalArmed(currentSession.id)
                initiativeScheduler.schedulePostSilenceEval()
            }
        }
    }

    private fun applyAssistantAvatarState(state: String, detail: String) {
        val selection = resolveAvatarSelection(state, detail)
        if (selection == null) {
            fallbackToLoopNeutral(forceReload = !galleryContainsCurrentClip(loopNeutralGallery))
            return
        }
        val exoPlayer = player ?: return
        val clip = resolveNextClip(selection.gallery, currentAvatarClipId)
        if (clip == null) {
            fallbackToLoopNeutral(forceReload = !galleryContainsCurrentClip(loopNeutralGallery))
            return
        }
        avatarMode = AvatarMode.CONTEXTUAL
        currentAvatarGallery = selection.gallery
        playAvatarClip(exoPlayer, clip)
    }

    private fun resolveAvatarSelection(state: String, detail: String): AvatarSelection? {
        val normalizedState = state.uppercase(Locale.getDefault())
        val normalizedDetail = detail.uppercase(Locale.getDefault())
        val tokens = "$normalizedState $normalizedDetail"
        // Adult intensity → teasers locales existentes (sin biblioteca adulta Blender).
        when {
            tokens.contains("ADULT_EXPLICIT") -> return AvatarSelection("TEASER_EXPLICIT", loopNeutralGallery)
            tokens.contains("ADULT_INTIMATE") -> return AvatarSelection("TEASER_INTIMATE", calidaGallery)
            tokens.contains("ADULT_SUGGESTIVE") -> return AvatarSelection("TEASER_SUGGESTIVE", alegreGallery)
            tokens.contains("ADULT_SOFT_FLIRT") || tokens.contains("ADULT_SOFT") ->
                return AvatarSelection("TEASER_SOFT", calidaGallery)
        }
        return when {
            normalizedState == "DEMO" -> AvatarSelection("DEMO_LOOP", loopNeutralGallery)
            normalizedState == "OFFLINE" || normalizedState == "NOTICE" -> null
            normalizedDetail == "LOCAL" || normalizedDetail == "SYNC" || normalizedDetail == "MESSAGE" || normalizedDetail.startsWith("STAGE_") -> null
            tokens.contains("AGRADEC") -> AvatarSelection("AGRADECIDA", agradecidaGallery)
            tokens.contains("ALEGRE") || tokens.contains("HAPPY") || tokens.contains("FELIZ") || tokens.contains("SONRISA") -> AvatarSelection("ALEGRE", alegreGallery)
            tokens.contains("ATENTA") || tokens.contains("LISTENING") || tokens.contains("MIRADA_ATENTA") || tokens.contains("THINK") -> AvatarSelection("ATENTA", atentaGallery)
            tokens.contains("ALIVIADA") || tokens.contains("CALMA") || tokens.contains("TRISTE") -> AvatarSelection("ALIVIADA", aliviadaGallery)
            normalizedState.isNotBlank() || normalizedDetail.isNotBlank() -> AvatarSelection("CALIDA", calidaGallery)
            else -> null
        }
    }

    private fun handleAvatarPlaybackEnded() {
        when (avatarMode) {
            AvatarMode.PRESENTATION -> {
                val exoPlayer = player ?: run {
                    onPresentationSequenceCompleted()
                    return
                }
                val gallery = currentAvatarGallery.ifEmpty { presentationGallery }
                val nextIndex = presentationClipIndex + 1
                if (nextIndex < gallery.size) {
                    presentationClipIndex = nextIndex
                    playAvatarClip(exoPlayer, gallery[nextIndex])
                } else {
                    onPresentationSequenceCompleted()
                }
            }
            AvatarMode.CONTEXTUAL -> fallbackToLoopNeutral(forceReload = true)
            AvatarMode.LOOP_NEUTRAL -> {
                val exoPlayer = player ?: return
                playAvatarClip(exoPlayer, pickNextClip(currentAvatarGallery, currentAvatarClipId))
            }
        }
    }

    private fun fallbackToLoopNeutral(forceReload: Boolean = false, resetStateLabel: Boolean = false) {
        avatarMode = AvatarMode.LOOP_NEUTRAL
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
        // Voice only on welcome/presentacion; mute spoken risk on other moods if tagged.
        exoPlayer.volume = if (clip.carriesVoice || clip.mood == ClipCatalog.MOOD_PRESENTACION) 1f else 1f
        exoPlayer.setMediaItem(MediaItem.fromUri(clipCatalog.playbackUri(clip)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun galleryContainsCurrentClip(gallery: List<GalleryClip>): Boolean =
        clipCatalog.containsClip(gallery, currentAvatarClipId)

    private fun resolveNextClip(gallery: List<GalleryClip>, previousClipId: String?): GalleryClip? =
        clipCatalog.nextClip(gallery, previousClipId)

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
        } catch (error: Exception) {
            Log.e("Me2Initiative", "No se pudo abrir el contexto: ${error.javaClass.simpleName}")
            Toast.makeText(this, R.string.initiative_unavailable, Toast.LENGTH_LONG).show()
        }
    }



    private fun resolveAlarmEvent(alarmId: String, stage: Int) {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            return
        }
        thread {
            runCatching {
                backendClient.reportAlarmEvent(currentSession, alarmId, stage, "respondio")
            }.onSuccess { result ->
                result.message?.let { reply ->
                    runOnUiThread {
                        appendAssistantReply(reply, "AWAKE", "CLIMA")
                    }
                }
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
                val payload = backendClient.downloadEncryptedBackup(currentSession)
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
