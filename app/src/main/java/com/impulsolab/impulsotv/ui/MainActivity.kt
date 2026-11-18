package com.impulsolab.impulsotv.ui

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.load
import coil.size.Scale
import com.impulsolab.impulsotv.R
import com.impulsolab.impulsotv.constants.AppConstants
import com.impulsolab.impulsotv.data.local.OfflinePlaylistCache
import com.impulsolab.impulsotv.data.model.PlaylistItem
import com.impulsolab.impulsotv.data.model.PlaylistResult
import com.impulsolab.impulsotv.data.remote.RealtimeClient
import com.impulsolab.impulsotv.data.remote.SupabaseClient
import com.impulsolab.impulsotv.manager.MediaCacheManager
import com.impulsolab.impulsotv.manager.NetworkStateManager
import com.impulsolab.impulsotv.manager.SessionManager
import com.impulsolab.impulsotv.service.PlayerService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * MainActivity — única Activity do kiosk
 * ════════════════════════════════════════════════════════════════
 * Gerencia dois estados exclusivos:
 *
 *   [PAIRING]  — teclado alfanumérico para digitar o código do dashboard
 *   [PLAYER]   — player nativo com ExoPlayer (vídeo) + ImageView (imagem)
 *
 * Fluxo de estado:
 *   Boot → verifica sessão salva
 *     ├─ Pareado → PLAYER → carrega playlist → reproduz loop
 *     └─ Não pareado → PAIRING → usuário digita código → PLAYER
 *
 * Tags de navegação:
 *   [STATE]          — transições entre PAIRING e PLAYER
 *   [PAIRING-UI]     — lógica da tela de pairing
 *   [PLAYER-INIT]    — inicialização do ExoPlayer
 *   [PLAYLIST-LOOP]  — ciclo de reprodução da playlist
 *   [ITEM-VIDEO]     — reprodução de vídeo via ExoPlayer
 *   [ITEM-IMAGE]     — exibição de imagem via Coil + timer
 *   [TRANSITION]     — crossfade entre itens
 *   [REALTIME]       — handling de eventos Supabase Realtime
 * ════════════════════════════════════════════════════════════════
 */
class MainActivity : AppCompatActivity() {

    // ─── Managers & Clients ───────────────────────────────────────────────
    private lateinit var session: SessionManager
    private lateinit var supabase: SupabaseClient
    private lateinit var realtime: RealtimeClient
    private lateinit var cacheManager: MediaCacheManager
    private lateinit var networkManager: NetworkStateManager
    private lateinit var offlineCache: OfflinePlaylistCache

    // ─── Jobs ────────────────────────────────────────────────────────────
    private var heartbeatJob: Job? = null
    private var pollingJob: Job? = null
    private var reconnectJob: Job? = null
    private var approvalJob: Job? = null
    private var approvalStartedAt: Long = 0L


    // ─── ExoPlayer ───────────────────────────────────────────────────────
    private var exoPlayer: ExoPlayer? = null
    private lateinit var okHttpClient: OkHttpClient

    // ─── Views — Pairing ─────────────────────────────────────────────────
    private lateinit var pairingContainer: LinearLayout
    private lateinit var etCode: EditText
    private lateinit var tvPairingStatus: TextView
    private lateinit var progressPairing: ProgressBar

    // ─── Views — Player ───────────────────────────────────────────────────
    private lateinit var playerContainer: LinearLayout
    private lateinit var playerView: PlayerView
    private lateinit var imageView: ImageView
    private lateinit var tvDeviceName: TextView

    // ─── Views — Sync (Novo) ──────────────────────────────────────────────
    private lateinit var syncContainer: LinearLayout
    private lateinit var progressSync: ProgressBar
    private lateinit var tvSyncStatus: TextView

    // ─── Service Connection & Ping ───────────────────────────────────────
    private var playerService: PlayerService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(AppConstants.TAG_MAIN, "[SERVICE] Conectado ao PlayerService")
            val binder = service as PlayerService.LocalBinder
            playerService = binder.getService()
            isBound = true
            startPingingService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(AppConstants.TAG_MAIN, "[SERVICE] Desconectado do PlayerService")
            playerService = null
            isBound = false
        }
    }

    private val pingRunnable = object : Runnable {
        override fun run() {
            playerService?.pingFromActivity()
            handler.postDelayed(this, AppConstants.ACTIVITY_PING_INTERVAL_MS)
        }
    }

    private fun startPingingService() {
        handler.removeCallbacks(pingRunnable)
        handler.post(pingRunnable)
    }

    // ─── Playlist state ──────────────────────────────────────────────────
    private var playlistItems: List<PlaylistItem> = emptyList()
    private var playlistHash: Int = 0 // Hash para detectar mudanças reais
    private var currentIndex: Int = 0

    // ─── Atualização de playlist em segundo plano ────────────────────────
    // A playlist nova é buscada e baixada SEM interromper a reprodução.
    // Quando pronta, fica em pendingItems e entra no lugar da atual ao
    // fim do item em exibição (advanceToNext).
    private var pendingItems: List<PlaylistItem>? = null
    private var refreshJob: Job? = null
    private var refreshAgain = false   // nova mudança chegou durante uma busca em andamento
    private val refreshRetryRunnable = Runnable { requestPlaylistRefresh("retry") }

    private val handler = Handler(Looper.getMainLooper())

    // ─── Permissions ─────────────────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i(AppConstants.TAG_MAIN, "Permissão de notificação concedida")
            ensureServiceRunning()
        } else {
            Log.w(AppConstants.TAG_MAIN, "Permissão de notificação negada")
        }
    }

    // [TRANSITION] Imagem → vídeo: esconder a imagem no primeiro quadro do vídeo
    private var revealVideoOnFirstFrame = false

    // Identifica o item em exibição: callbacks atrasados (Coil, erro do
    // ExoPlayer) de um item anterior são ignorados se o token mudou
    private var itemToken = 0

    // Timer de imagem — cancelado quando o item muda
    private val imageTimerRunnable = Runnable { advanceToNext() }

    // ─── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        setContentView(R.layout.activity_main)
        setImmersive()
        bindViews()

        session        = SessionManager(this)
        supabase       = SupabaseClient()
        session.getSession()?.let { applyCredentials(it) }
        cacheManager   = MediaCacheManager(this)
        networkManager = NetworkStateManager(this) {
            // Rede voltou: se estamos esperando o servidor, tenta já (2s)
            handler.post {
                if (reconnectJob?.isActive == true) {
                    Log.i(AppConstants.TAG_MAIN, "[RECONNECT] Rede disponível — tentando agora")
                    startReconnectLoop(initialDelayMs = 2_000L)
                }
            }
        }
        offlineCache   = OfflinePlaylistCache(this)
        okHttpClient   = OkHttpClient()

        checkOverlayPermission()

        realtime = RealtimeClient(
            onPlaylistChanged = { onPlaylistChangedFromRealtime() },   // [REALTIME]
            onRestartRequested = { onRestartRequested() },             // [REALTIME]
            onTakenOver = { onCodeTakenByOtherDevice() },              // [SIGNAL]
            loadLastRestartAt = { session.getLastRestartAt() },
            saveLastRestartAt = { session.saveLastRestartAt(it) }
        )

        checkPermissionsAndStartService()

        // [STATE] Decisão inicial: pareado ou não?
        val sess = session.getSession()
        if (sess != null) {
            showPlayer()
            lifecycleScope.launch { resumeSession(sess) }
        } else {
            showPairing()
        }
    }

    // ─── [RECONNECT] Retomada da sessão e reconexão ao servidor ──────────

    /**
     * [RECONNECT] Retoma uma sessão salva ao abrir o app.
     *   connected/ok  → reprodução online normal
     *   disconnected  → dashboard desvinculou: reset completo
     *   conflict      → outro aparelho assumiu o código: volta ao pareamento
     *   sem resposta  → toca o cache offline (se houver) e tenta reconectar
     *                   em segundo plano até o servidor responder
     */
    private suspend fun resumeSession(sess: SessionManager.Session) {
        val response = supabase.connectDevice(sess.deviceCode, sess.fingerprint)
        when (response?.status) {
            "connected", "ok" -> {
                rememberCredentials(response)
                startPlayback()
            }
            "disconnected" -> {
                Log.w(AppConstants.TAG_MAIN, "[SIGNAL] Dispositivo desconectado pelo servidor")
                onRestartRequested()
            }
            "conflict" -> {
                Log.w(AppConstants.TAG_MAIN, "[SIGNAL] Código em uso por outro aparelho")
                onCodeTakenByOtherDevice()
            }
            else -> {
                Log.w(AppConstants.TAG_MAIN, "[RECONNECT] Servidor indisponível no boot (status=${response?.status}); iniciando offline")
                val cachedItems = try {
                    offlineCache.getOfflinePlaylist()
                } catch (e: Exception) {
                    Log.e(AppConstants.TAG_MAIN, "Erro ao ler playlist offline: ${e.message}")
                    emptyList()
                }
                if (cachedItems.isNotEmpty()) {
                    Log.i(AppConstants.TAG_PLAYER, "Iniciando via cache offline (${cachedItems.size} itens)")
                    playlistItems = cachedItems
                    startPlayback(fromOffline = true)
                } else {
                    // Pareado, mas sem nada salvo: mantém a sessão e aguarda a rede
                    showSync()
                    tvSyncStatus.text = "Aguardando conexão…"
                }
                startReconnectLoop()
            }
        }
    }

    /**
     * [RECONNECT] Tenta falar com o servidor com espera crescente
     * (SERVER_RECONNECT_MIN_MS → SERVER_RECONNECT_MAX_MS). Ao conseguir,
     * liga heartbeat/Realtime/polling sem interromper o que está tocando.
     */
    private fun startReconnectLoop(initialDelayMs: Long = AppConstants.SERVER_RECONNECT_MIN_MS) {
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            var waitMs = AppConstants.SERVER_RECONNECT_MIN_MS
            var nextDelay = initialDelayMs
            while (isActive) {
                delay(nextDelay)
                nextDelay = waitMs
                val sess = session.getSession() ?: return@launch
                val response = supabase.connectDevice(sess.deviceCode, sess.fingerprint)
                when (response?.status) {
                    "connected", "ok" -> {
                        Log.i(AppConstants.TAG_MAIN, "[RECONNECT] Servidor alcançado — retomando modo online")
                        rememberCredentials(response)
                        if (playlistItems.isEmpty()) {
                            startPlayback()
                        } else {
                            startOnlineServices(session.getSession() ?: sess)
                            requestPlaylistRefresh("reconexão")
                        }
                        return@launch
                    }
                    "disconnected" -> { onRestartRequested(); return@launch }
                    "conflict" -> { onCodeTakenByOtherDevice(); return@launch }
                    else -> {
                        Log.d(AppConstants.TAG_MAIN, "[RECONNECT] Ainda sem servidor; nova tentativa em ${waitMs / 1000}s")
                        waitMs = (waitMs * 2).coerceAtMost(AppConstants.SERVER_RECONNECT_MAX_MS)
                        nextDelay = waitMs
                    }
                }
            }
        }
    }

    private fun stopReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** Liga Realtime, heartbeat e polling — tudo que depende do servidor. */
    /** [SIGNAL] Fase 2: guarda token/canal novos (o servidor troca a cada conexão). */
    private fun rememberCredentials(response: com.impulsolab.impulsotv.data.model.ConnectResponse?) {
        val token = response?.deviceToken
        if (token.isNullOrBlank()) {
            Log.w(AppConstants.TAG_MAIN, "[SIGNAL] Servidor não entregou token (modo antigo)")
            return
        }
        session.saveCredentials(token, response?.signalTopic)
        supabase.deviceToken = token
    }

    private fun applyCredentials(sess: SessionManager.Session) {
        supabase.deviceToken = sess.deviceToken
        supabase.fingerprint = sess.fingerprint
    }

    private fun startOnlineServices(sess: SessionManager.Session) {
        applyCredentials(sess)
        realtime.connect(sess.deviceId, sess.playlistId, sess.signalTopic)
        startHeartbeat(sess.deviceCode, sess.fingerprint)
        startPolling(sess.deviceCode)
    }

    /**
     * Pede a permissão "Sobrepor a outros apps" (necessária para o app se abrir
     * sozinho no boot). Pede UMA vez por instalação — a marca fica num arquivo
     * de preferências que o reset de pareamento não apaga — e suspende o
     * watchdog do PlayerService enquanto a tela de configurações está aberta.
     *
     * Antes de abrir a tela, tenta liberar sozinho pelo adb local (opção C) —
     * na PROSB3000 essa tela nem existe.
     */
    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) return
        lifecycleScope.launch {
            val granted = withContext(Dispatchers.IO) {
                com.impulsolab.impulsotv.adb.SelfPermissions.ensure(this@MainActivity)
            }
            if (!granted) askOverlayPermissionOnScreen()
        }
    }

    /** Fallback: abre a tela do sistema (uma vez por instalação). */
    private fun askOverlayPermissionOnScreen() {
        if (Settings.canDrawOverlays(this)) return

        val devicePrefs = getSharedPreferences(AppConstants.PREF_DEVICE_FILE, MODE_PRIVATE)
        if (devicePrefs.getBoolean(AppConstants.PREF_OVERLAY_ASKED, false)) {
            Log.w(AppConstants.TAG_MAIN, "Permissão de sobreposição não concedida (já solicitada antes)")
            return
        }

        run {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                PlayerService.suppressBringToFrontUntil =
                    System.currentTimeMillis() + AppConstants.SYSTEM_SCREEN_GRACE_MS
                devicePrefs.edit().putBoolean(AppConstants.PREF_OVERLAY_ASKED, true).apply()
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Conceda a permissão 'Sobrepor a outros aplicativos' para abertura automática.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                PlayerService.suppressBringToFrontUntil = 0L
                Log.e(AppConstants.TAG_MAIN, "Erro ao solicitar permissão overlay: ${e.message}")
            }
        }
    }

    private fun checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                ensureServiceRunning()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            ensureServiceRunning()
        }
    }

    override fun onResume() {
        super.onResume()
        // De volta ao app: o watchdog pode voltar a agir
        PlayerService.suppressBringToFrontUntil = 0L
        setImmersive()
        exoPlayer?.play()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PlayerService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        handler.removeCallbacks(pingRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopHeartbeat()
        stopPolling()
        stopReconnectLoop()
        networkManager.release()
        realtime.disconnect()
        releasePlayer()
        super.onDestroy()
    }

    // [KIOSK] Bloqueia teclas de saída
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* [KIOSK] Bloqueado */ }

    // ─── [STATE] Transições de estado ────────────────────────────────────

    private fun showPairing() {
        Log.i(AppConstants.TAG_MAIN, "[STATE] → PAIRING")
        pairingContainer.visibility = View.VISIBLE
        playerContainer.visibility  = View.GONE
        etCode.requestFocus()
    }

    private fun showPlayer() {
        Log.i(AppConstants.TAG_MAIN, "[STATE] → PLAYER")
        pairingContainer.visibility = View.GONE
        syncContainer.visibility    = View.GONE
        playerContainer.visibility  = View.VISIBLE
        val name = session.getSession()?.deviceName ?: ""
        tvDeviceName.text = name
    }

    /** [EMPTY] Nenhuma mídia: logo + aviso em vez de tela preta. */
    private fun showEmptyPlaylist() {
        showSync()
        progressSync.visibility = View.GONE
        tvSyncStatus.text = "Nenhuma mídia nesta playlist.\nAdicione conteúdo no painel."
    }

    private val emptyRetryRunnable = Runnable { startPlayback() }

    private fun showSync() {
        Log.i(AppConstants.TAG_MAIN, "[STATE] → SYNC")
        pairingContainer.visibility = View.GONE
        playerContainer.visibility  = View.GONE
        syncContainer.visibility    = View.VISIBLE
        progressSync.visibility     = View.VISIBLE
        progressSync.progress       = 0
        tvSyncStatus.text           = "0%"
    }

    // ─── [PAIRING-UI] Tela de pairing ────────────────────────────────────

    private fun setupPairingInput() {
        // Uppercase automático enquanto digita
        etCode.filters = arrayOf(android.text.InputFilter.AllCaps())

        // Conectar ao pressionar Enter no teclado
        etCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO) {
                attemptPairing()
                true
            } else false
        }

        // Auto-conectar ao digitar 6 caracteres
        etCode.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if ((s?.length ?: 0) == 6) attemptPairing()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    /**
     * [PAIRING-UI] Tenta parear com o código digitado.
     * Busca o device_code no Supabase e, se encontrado,
     * salva a sessão e vai para o player.
     */
    private fun attemptPairing(force: Boolean = false) {
        if (pairingInProgress) return   // 6º caractere + Enter disparavam 2 pedidos
        val code = etCode.text.toString().trim().uppercase()
        if (code.length != 6) {
            tvPairingStatus.text = "O código deve ter 6 caracteres"
            return
        }

        setPairingLoading(true)
        tvPairingStatus.text = ""

        if (!force) stopApprovalWait()

        lifecycleScope.launch {
            val fingerprint = session.getOrCreateFingerprint()
            supabase.fingerprint = fingerprint
            val response = supabase.connectDevice(code, fingerprint, force)

            if (response == null) {
                setPairingLoading(false)
                tvPairingStatus.text = "Erro de conexão. Tente novamente."
                return@launch
            }

            if (response.status != "pending_approval") stopApprovalWait()

            when (response.status) {
                "pending_approval" -> {
                    setPairingLoading(false)
                    waitForTakeoverApproval(code)
                }
                "conflict" -> {
                    setPairingLoading(false)
                    handleConnectionConflict(
                        response.conflictInfo
                            ?: com.impulsolab.impulsotv.data.model.ConflictInfo(null, null, null),
                        code
                    )
                }
                "connected", "ok" -> {
                    val device = response.device
                    if (device == null) {
                        setPairingLoading(false)
                        tvPairingStatus.text = "Resposta inválida do servidor. Tente novamente."
                        Log.e(AppConstants.TAG_PAIRING, "[PAIRING-UI] status=${response.status} sem 'device' na resposta")
                        return@launch
                    }
                    // Salva sessão localmente. Sem playlist ainda: salva vazio —
                    // a device-api informa o playlist_id quando for vinculado.
                    session.saveSession(
                        deviceId   = device.id,
                        deviceCode = device.deviceCode,
                        deviceName = device.name,
                        playlistId = device.playlistId ?: ""
                    )
                    rememberCredentials(response)

                    Log.i(AppConstants.TAG_PAIRING, "[PAIRING-UI] Pareado com sucesso: ${device.name}")

                    // [STATE] Transição para player
                    setPairingLoading(false)
                    showPlayer()
                    startPlayback()
                }
                else -> {
                    setPairingLoading(false)
                    tvPairingStatus.text = "Código não encontrado. Verifique e tente novamente."
                }
            }
        }
    }

    /**
     * [PAIRING-UI] Troca de aparelho pedida: o dono aprova em Dispositivos
     * no painel. Repete o pedido a cada 5s, por até 15 min, enquanto o código
     * digitado continuar o mesmo.
     */
    private fun waitForTakeoverApproval(code: String) {
        if (approvalStartedAt == 0L) approvalStartedAt = System.currentTimeMillis()
        if (System.currentTimeMillis() - approvalStartedAt > AppConstants.TAKEOVER_APPROVAL_TIMEOUT_MS) {
            stopApprovalWait()
            tvPairingStatus.text = "O pedido de troca expirou. Digite o código para tentar de novo."
            return
        }
        tvPairingStatus.text = "Aguardando o dono aprovar a troca no painel (Dispositivos)…"
        approvalJob?.cancel()
        approvalJob = lifecycleScope.launch {
            delay(AppConstants.TAKEOVER_APPROVAL_RETRY_MS)
            if (etCode.text.toString().trim().uppercase() == code) {
                attemptPairing(force = true)
            } else {
                stopApprovalWait()
            }
        }
    }

    private fun stopApprovalWait() {
        approvalJob?.cancel()
        approvalJob = null
        approvalStartedAt = 0L
    }

    private fun handleConnectionConflict(conflict: com.impulsolab.impulsotv.data.model.ConflictInfo, code: String) {
        Log.w(AppConstants.TAG_PAIRING, "[PAIRING-UI] Conflito detectado para o código: $code")
        
        val deviceName = conflict.deviceName ?: "Desconhecido"
        val connectionType = conflict.connectionType ?: "web/outro"
        val lastSeen = conflict.lastConnectedAt ?: "Indisponível"

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Dispositivo em uso")
            .setMessage("Este código já está conectado em outro aparelho:\n\n" +
                        "Aparelho: $deviceName\n" +
                        "Tipo: $connectionType\n" +
                        "Último acesso: $lastSeen\n\n" +
                        "Deseja desconectar o outro aparelho e conectar este?")
            .setCancelable(false)
            .setPositiveButton("Sim, conectar este") { _, _ ->
                Log.i(AppConstants.TAG_PAIRING, "[PAIRING-UI] Usuário optou por forçar a conexão")
                attemptPairing(force = true)
            }
            .setNegativeButton("Não") { dialog, _ ->
                dialog.dismiss()
                setPairingLoading(false)
            }
            .show()
    }

    private var pairingInProgress = false

    private fun setPairingLoading(loading: Boolean) {
        pairingInProgress = loading
        progressPairing.visibility = if (loading) View.VISIBLE else View.GONE
        etCode.isEnabled = !loading
    }

    // ─── [PLAYER-INIT] Inicialização do ExoPlayer ────────────────────────

    /**
     * Inicia o fluxo completo de reprodução:
     *   1. Carrega a playlist do Supabase
     *   2. Inicializa o ExoPlayer com suporte a OkHttp
     *   3. Inicia o Realtime e o Heartbeat
     *   4. Inicia o pré-cache dos arquivos
     *   5. Começa a reproduzir do primeiro item
     */
    private fun startPlayback(fromOffline: Boolean = false) {
        if (session.getSession() == null) return

        lifecycleScope.launch {
            if (!fromOffline) {
                val result = supabase.fetchPlaylistFunction(session.getSession()?.deviceCode ?: return@launch)

                when (result) {
                    is PlaylistResult.Disconnected -> {
                        Log.w(AppConstants.TAG_PLAYER, "Dispositivo desconectado via API")
                        onRestartRequested()
                        return@launch
                    }
                    is PlaylistResult.Success -> {
                        val items = result.items
                        val newHash = playlistSignature(items)
                        syncPlaylistId(result.playlistId, reconnectRealtime = false)

                        if (items.isEmpty()) {
                            Log.w(AppConstants.TAG_PLAYER, "[EMPTY] Playlist vazia — aguardando conteúdo")
                            showEmptyPlaylist()
                            // Fica online (heartbeat + Realtime) para o painel ver o
                            // aparelho e para começar assim que houver mídia
                            session.getSession()?.let { startOnlineServices(it) }
                            handler.removeCallbacks(emptyRetryRunnable)
                            handler.postDelayed(emptyRetryRunnable, AppConstants.PLAYLIST_EMPTY_RETRY_MS)
                            return@launch
                        }
                        handler.removeCallbacks(emptyRetryRunnable)

                        if (newHash == playlistHash && playlistItems.isNotEmpty()) {
                            Log.i(AppConstants.TAG_PLAYER, "[CACHE-VERSION] Conteúdo idêntico. Pulando sincronização de rede.")
                            showPlayer()
                            playCurrentItem()
                            return@launch
                        }

                        playlistItems = items
                        playlistHash = newHash
                        offlineCache.savePlaylist(items)
                    }
                    is PlaylistResult.Error -> {
                        Log.e(AppConstants.TAG_PLAYER, "Erro ao buscar playlist: ${result.message}")
                        if (playlistItems.isNotEmpty()) {
                            // Já está tocando: mantém e só tenta de novo depois
                            handler.postDelayed({ startPlayback() }, AppConstants.PLAYLIST_RETRY_DELAY_MS)
                            return@launch
                        }
                        // [OFFLINE] Nada na tela e o servidor caiu (ex.: Wi‑Fi derrubado
                        // logo após o device-connect): toca a playlist salva em vez de
                        // deixar a tela preta, e reconecta em segundo plano.
                        val cached = try {
                            offlineCache.getOfflinePlaylist()
                        } catch (e: Exception) {
                            Log.e(AppConstants.TAG_PLAYER, "Erro ao ler playlist offline: ${e.message}")
                            emptyList()
                        }
                        if (cached.isNotEmpty()) {
                            Log.w(AppConstants.TAG_PLAYER, "[OFFLINE] Servidor inacessível — tocando playlist salva (${cached.size} itens)")
                            playlistItems = cached
                            startPlayback(fromOffline = true)
                        } else {
                            showSync()
                            tvSyncStatus.text = "Aguardando conexão…"
                        }
                        startReconnectLoop()
                        return@launch
                    }
                }
            }

            // Sessão relida: o playlist_id pode ter sido atualizado acima
            val sess = session.getSession() ?: return@launch
            if (fromOffline) playlistHash = playlistSignature(playlistItems)

            // [SYNC] Exibe a tela de sincronização
            showSync()

            currentIndex  = 0
            initExoPlayer()

            if (!fromOffline) {
                stopReconnectLoop()
                startOnlineServices(sess)
            }

            // [SYNC] Baixa TODOS os arquivos antes de liberar o player.
            // Offline não há o que baixar: toca já o que está no aparelho (itens
            // sem arquivo local são pulados pelo erro do player e voltam quando a
            // reconexão disparar a atualização da playlist).
            if (!fromOffline) {
                cacheManager.preCachePlaylist(playlistItems) { progress ->
                    handler.post {
                        progressSync.progress = progress
                        tvSyncStatus.text = "$progress%"
                    }
                }
            }

            // [SYNC] Agora sim, com tudo em cache, libera o player
            showPlayer()
            playCurrentItem()
        }
    }

    private fun startHeartbeat(deviceCode: String, fingerprint: String) {
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val orientation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"
                    supabase.sendHeartbeatFunction(
                        deviceCode = deviceCode,
                        fingerprint = fingerprint,
                        orientation = orientation,
                        isOnline = networkManager.isOnline()
                    )
                    delay(AppConstants.HEARTBEAT_INTERVAL_MS)
                } catch (e: Exception) {
                    if (e is IllegalStateException && e.message == "TAKEOVER") {
                        withContext(Dispatchers.Main) { onCodeTakenByOtherDevice() }
                        break
                    }
                    delay(5000)
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Fallback do Realtime: a cada PLAYLIST_POLLING_INTERVAL_MS dispara a
     * mesma busca em segundo plano usada pelos eventos em tempo real.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun startPolling(deviceCode: String) {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                delay(AppConstants.PLAYLIST_POLLING_INTERVAL_MS)
                requestPlaylistRefresh("polling")
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * [PLAYER-INIT] Configura o ExoPlayer com:
     *   - OkHttpDataSource: usa o mesmo cliente HTTP do app
     *   - Modo sem controles de UI (kiosk)
     *   - Listener para detectar fim de vídeo e erros
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun initExoPlayer() {
        releasePlayer()   // garante que não há instância anterior

        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(
                mapOf("apikey" to AppConstants.SUPABASE_ANON_KEY)
            )

        // DefaultDataSource.Factory resolve schemes locais (file://) automaticamente
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { player ->
                playerView.player = player
                playerView.useController = false   // sem controles — kiosk
                // [TRANSITION] Mantém o último quadro do vídeo anterior até o
                // primeiro quadro do próximo — sem flash preto entre vídeos
                playerView.setKeepContentOnPlayerReset(true)

                player.addListener(object : Player.Listener {

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            // [ITEM-VIDEO] Vídeo terminou → próximo item
                            Log.d(AppConstants.TAG_PLAYER, "[ITEM-VIDEO] Vídeo concluído")
                            advanceToNext()
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        // [TRANSITION] Imagem → vídeo: só revela o vídeo quando
                        // o primeiro quadro real já está na tela
                        if (revealVideoOnFirstFrame) {
                            revealVideoOnFirstFrame = false
                            hideImageLayer()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(AppConstants.TAG_PLAYER, "[ITEM-VIDEO] Erro: ${error.message}")
                        // Pula para o próximo item em caso de erro (se ainda for o mesmo item)
                        val token = itemToken
                        handler.postDelayed({ if (token == itemToken) advanceToNext() }, 2_000L)
                    }
                })
            }

        Log.i(AppConstants.TAG_PLAYER, "[PLAYER-INIT] ExoPlayer inicializado")
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    // ─── [PLAYLIST-LOOP] Ciclo de reprodução ─────────────────────────────

    /**
     * [PLAYLIST-LOOP] Reproduz o item no índice atual.
     * Decide entre modo vídeo e modo imagem com base em mediaFile.fileType.
     */
    private fun playCurrentItem() {
        if (playlistItems.isEmpty()) return
        itemToken++

        val item = playlistItems[currentIndex]
        val isVertical = item.isVerticalDisplay
        val fileName = item.mediaFile.originalName ?: "Arquivo ${currentIndex + 1}"

        // [ORIENTATION] Gira antes de escolher preencher/encaixar
        wantVerticalScreen = item.isMarkedVertical
        updatePlayerRotation()

        Log.i(AppConstants.TAG_PLAYER, "[PLAYLIST-LOOP] Item ${currentIndex + 1}/${playlistItems.size}: " +
              "$fileName (${item.mediaFile.fileType}) | marcadoVertical=${item.isMarkedVertical} conteudoVertical=$isVertical fill=${shouldFillScreen(item)} [meta=${item.metadata?.displayMode}, flat=${item.flatDisplayMode}, dim=${item.mediaFile.width}x${item.mediaFile.height}]")

        if (item.isVideo) {
            playVideo(item)
        } else {
            showImage(item)
        }
    }

    /**
     * [ORIENTATION] O Android fica no padrão (horizontal). Quando a mídia vem
     * marcada como vertical no painel, o APP gira o player 270° sozinho — sem
     * configurar rotação no sistema da TV Box.
     *
     * Só gira se a tela estiver DEITADA. Se o sistema já estiver em pé (alguém
     * girou no Android, ou é um celular), não gira de novo — a rotação dupla
     * era o que deixava o conteúdo deitado e cortado.
     */
    private var wantVerticalScreen = false

    private fun updatePlayerRotation() {
        val root = playerContainer.parent as? View ?: return
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return   // ainda sem layout; o listener chama de novo

        val rotate = wantVerticalScreen && w > h
        val lp = playerContainer.layoutParams as? FrameLayout.LayoutParams ?: return
        val targetRotation = if (rotate) AppConstants.VERTICAL_ROTATION_DEGREES else 0f
        val targetW = if (rotate) h else FrameLayout.LayoutParams.MATCH_PARENT
        val targetH = if (rotate) w else FrameLayout.LayoutParams.MATCH_PARENT
        if (playerContainer.rotation == targetRotation && lp.width == targetW && lp.height == targetH) return

        Log.i(AppConstants.TAG_PLAYER, "[ORIENTATION] Tela ${w}x$h → " +
              if (rotate) "girando player ${targetRotation.toInt()}° (${h}x$w)" else "sem rotação")
        playerContainer.rotation = targetRotation
        lp.width = targetW
        lp.height = targetH
        lp.gravity = Gravity.CENTER
        playerContainer.layoutParams = lp
    }

    /** Orientação efetiva da área do player, já considerando a rotação do app. */
    private fun isPlayerAreaVertical(): Boolean {
        val root = playerContainer.parent as? View
        val w = root?.width ?: 0
        val h = root?.height ?: 0
        if (w == 0 || h == 0) {
            return wantVerticalScreen ||
                   resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        return h > w || (wantVerticalScreen && w > h)
    }

    /**
     * @return true  → mídia e área do player têm a mesma orientação: preencher
     *         false → orientações diferentes: mostrar inteira, com faixas pretas
     */
    private fun shouldFillScreen(item: PlaylistItem): Boolean =
        item.isVerticalDisplay == isPlayerAreaVertical()

    /**
     * [PLAYLIST-LOOP] Avança para o próximo item.
     * Se uma playlist nova já foi baixada em segundo plano, troca agora
     * e recomeça do primeiro item — sem esperar rede.
     */
    private fun advanceToNext() {
        handler.removeCallbacks(imageTimerRunnable)

        pendingItems?.let { newItems ->
            pendingItems = null
            playlistItems = newItems
            playlistHash = playlistSignature(newItems)
            currentIndex = 0
            Log.i(AppConstants.TAG_PLAYER, "[PLAYLIST-UPDATE] Playlist nova aplicada: ${newItems.size} itens")
            playCurrentItem()
            return
        }

        if (playlistItems.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlistItems.size
        playCurrentItem()
    }

    // ─── [ITEM-VIDEO] Reprodução de vídeo ────────────────────────────────

    /**
     * [ITEM-VIDEO] Reproduz um item de vídeo via ExoPlayer.
     *
     * Camadas: o PlayerView fica sempre por baixo e a imagem por cima.
     *   vídeo → vídeo:  keepContentOnPlayerReset mantém o último quadro
     *                   até o primeiro quadro do próximo (sem flash preto)
     *   imagem → vídeo: a imagem continua na tela e só some (fade) em
     *                   onRenderedFirstFrame, com o vídeo já desenhando
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun playVideo(item: PlaylistItem) {
        val uriStr = cacheManager.getPlayableUri(item)
        val uri = if (uriStr.startsWith("/")) {
            Uri.fromFile(File(uriStr))
        } else {
            Uri.parse(uriStr)
        }

        // [TRANSITION] Se há imagem por cima, revela o vídeo no 1º quadro
        revealVideoOnFirstFrame = imageView.visibility == View.VISIBLE

        // [ORIENTATION] Preenche quando combina com a tela; senão mostra inteiro
        playerView.resizeMode = if (shouldFillScreen(item)) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else AspectRatioFrameLayout.RESIZE_MODE_FIT

        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            play()
        }

        Log.d(AppConstants.TAG_PLAYER, "[ITEM-VIDEO] Reproduzindo: ${item.mediaFile.originalName} | vertical=${item.isVerticalDisplay}")
    }

    // ─── [ITEM-IMAGE] Exibição de imagem ─────────────────────────────────

    /**
     * [ITEM-IMAGE] Exibe uma imagem via Coil pelo tempo definido em duration.
     *
     * display_mode vertical → Scale.FIT (sem corte, barras laterais)
     * display_mode padrão   → Scale.FILL (preenche a tela, pode cortar)
     */
    private fun showImage(item: PlaylistItem) {
        val uriStr = cacheManager.getPlayableUri(item)
        val uri = if (uriStr.startsWith("/")) {
            File(uriStr)
        } else {
            uriStr
        }

        // O vídeo anterior já terminou; o último quadro fica por baixo
        revealVideoOnFirstFrame = false
        exoPlayer?.stop()

        val token = itemToken
        val displayMs = if (item.durationMs > 0) item.durationMs
                        else AppConstants.IMAGE_DEFAULT_DURATION_MS

        // O Coil só carrega depois que o ImageView tem tamanho: uma view GONE
        // nunca é medida e a imagem nunca chegava. Fica VISIBLE e transparente
        // por cima do vídeo; showImageLayer() faz o fade-in ao carregar.
        if (imageView.visibility != View.VISIBLE) {
            imageView.animate().cancel()
            imageView.alpha = 0f
            imageView.visibility = View.VISIBLE
        }

        // [ORIENTATION] Preenche quando combina com a tela; senão mostra inteira
        val fill = shouldFillScreen(item)
        val scale = if (fill) Scale.FILL else Scale.FIT
        imageView.scaleType = if (fill) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER

        imageView.load(uri) {
            scale(scale)
            crossfade(false)   // Coil crossfade desligado — usamos o nosso
            // Imagem → imagem: mantém a anterior até a nova carregar (sem flash)
            placeholder(imageView.drawable)
            listener(
                onSuccess = { _, _ ->
                    if (token != itemToken) return@listener   // item já mudou
                    Log.d(AppConstants.TAG_PLAYER, "[ITEM-IMAGE] Imagem carregada; exibindo por ${displayMs / 1000}s: ${item.mediaFile.originalName}")
                    // [TRANSITION] Vídeo → imagem: fade só com a imagem pronta
                    showImageLayer()
                    // Timer conta a partir da imagem NA TELA (não do início do download)
                    handler.removeCallbacks(imageTimerRunnable)
                    handler.postDelayed(imageTimerRunnable, displayMs)
                },
                onError = { _, err ->
                    if (token != itemToken) return@listener
                    Log.e(AppConstants.TAG_PLAYER, "[ITEM-IMAGE] Erro ao carregar imagem: ${err.throwable.message}")
                    handler.postDelayed({ if (token == itemToken) advanceToNext() }, 2_000L)
                }
            )
        }

        // Rede de segurança: se a imagem não carregar nem falhar (download
        // travado), não fica parada para sempre
        handler.removeCallbacks(imageTimerRunnable)
        handler.postDelayed(imageTimerRunnable, displayMs + AppConstants.IMAGE_LOAD_TIMEOUT_MS)
    }

    // ─── [TRANSITION] Camada de imagem sobre o vídeo ────────────────────

    /** Fade-in da imagem por cima do vídeo (TRANSITION_DURATION_MS). */
    private fun showImageLayer() {
        imageView.animate().cancel()
        if (imageView.visibility != View.VISIBLE) {
            imageView.alpha = 0f
            imageView.visibility = View.VISIBLE
        }
        if (imageView.alpha >= 1f) return
        imageView.animate()
            .alpha(1f)
            .setDuration(AppConstants.TRANSITION_DURATION_MS)
            .setListener(null)
            .start()
    }

    /** Fade-out da imagem, revelando o vídeo que já está desenhando por baixo. */
    private fun hideImageLayer() {
        imageView.animate().cancel()
        if (imageView.visibility != View.VISIBLE) return
        imageView.animate()
            .alpha(0f)
            .setDuration(AppConstants.TRANSITION_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) { canceled = true }
                override fun onAnimationEnd(animation: Animator) {
                    // cancel() também chama onAnimationEnd: não esconder se outro fade assumiu
                    if (canceled) return
                    imageView.visibility = View.GONE
                    imageView.alpha = 1f
                }
            })
            .start()
    }

    // ─── [REALTIME] Handlers de eventos em tempo real ────────────────────

    /**
     * [REALTIME] Playlist foi modificada no dashboard.
     * Não interrompe o item atual — agenda o reload para quando terminar.
     */
    private fun onPlaylistChangedFromRealtime() {
        Log.i(AppConstants.TAG_PLAYER, "[REALTIME] Playlist alterada — buscando em segundo plano")
        requestPlaylistRefresh("realtime")
    }

    /**
     * [REALTIME] Dashboard solicitou reset do dispositivo.
     * Limpa a sessão e volta para a tela de pairing.
     */
    private fun onRestartRequested() {
        resetToPairing(
            message = "Dispositivo desvinculado ou reiniciado pelo dashboard",
            clearMedia = true
        )
    }

    /**
     * [SIGNAL] Outro aparelho assumiu este código (conflito no boot ou HTTP 409
     * no heartbeat). Volta ao pareamento, mas MANTÉM as mídias baixadas: se a
     * pessoa digitar o mesmo código de novo, não precisa baixar tudo outra vez.
     */
    private fun onCodeTakenByOtherDevice() {
        resetToPairing(
            message = "Este código foi conectado em outro aparelho.\nDigite o código para reconectar esta TV.",
            clearMedia = false
        )
    }

    /** Para todos os serviços, limpa a sessão e mostra a tela de pareamento. */
    private fun resetToPairing(message: String, clearMedia: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { resetToPairing(message, clearMedia) }
            return
        }

        Log.i(AppConstants.TAG_PLAYER, "[SIGNAL] Voltando ao pareamento: $message (clearMedia=$clearMedia)")
        handler.removeCallbacksAndMessages(null)
        if (isBound) startPingingService()   // o ping ao PlayerService foi removido acima
        stopHeartbeat()
        stopPolling()
        stopReconnectLoop()
        refreshJob?.cancel()
        refreshJob = null
        refreshAgain = false
        pendingItems = null
        realtime.disconnect()
        releasePlayer()
        revealVideoOnFirstFrame = false
        imageView.animate().cancel()
        imageView.visibility = View.GONE
        playlistItems = emptyList()
        playlistHash = 0
        currentIndex = 0
        if (clearMedia) cacheManager.clearAll()
        lifecycleScope.launch { offlineCache.clearCache() }
        session.clearSession()

        // [STATE] Volta para pairing
        showPairing()
        tvPairingStatus.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * [PLAYLIST-UPDATE] Busca a playlist pela Edge Function device-api (mesma
     * fonte do startPlayback) e baixa os arquivos novos em segundo plano.
     * O player continua tocando a playlist atual durante todo o processo;
     * a troca acontece em advanceToNext() quando pendingItems estiver pronto.
     *
     * Falha de rede ou resposta vazia NÃO afeta a reprodução: a playlist atual
     * segue tocando e a busca é refeita após PLAYLIST_RETRY_DELAY_MS.
     */
    private fun requestPlaylistRefresh(reason: String) {
        val sess = session.getSession() ?: return
        handler.removeCallbacks(refreshRetryRunnable)

        if (refreshJob?.isActive == true) {
            // Uma busca já está em andamento e pode trazer dados anteriores a esta mudança
            refreshAgain = true
            return
        }

        Log.d(AppConstants.TAG_PLAYER, "[PLAYLIST-UPDATE] Buscando playlist ($reason)")
        refreshJob = lifecycleScope.launch {
            when (val result = supabase.fetchPlaylistFunction(sess.deviceCode)) {
                is PlaylistResult.Disconnected -> {
                    Log.w(AppConstants.TAG_PLAYER, "[PLAYLIST-UPDATE] Dispositivo desconectado via API")
                    onRestartRequested()
                    return@launch
                }
                is PlaylistResult.Error -> {
                    Log.w(AppConstants.TAG_PLAYER, "[PLAYLIST-UPDATE] Falha ao buscar (${result.message}); mantendo playlist atual")
                    handler.postDelayed(refreshRetryRunnable, AppConstants.PLAYLIST_RETRY_DELAY_MS)
                }
                is PlaylistResult.Success -> {
                    syncPlaylistId(result.playlistId, reconnectRealtime = true)
                    val items = result.items
                    val newHash = playlistSignature(items)
                    val currentTarget = pendingItems?.let { playlistSignature(it) } ?: playlistHash
                    when {
                        items.isEmpty() ->
                            Log.w(AppConstants.TAG_PLAYER, "[PLAYLIST-UPDATE] Playlist vazia no servidor; mantendo a atual")
                        playlistItems.isEmpty() -> {
                            // Nada tocando (tela de playlist vazia): começa já
                            Log.i(AppConstants.TAG_PLAYER, "[PLAYLIST-UPDATE] Conteúdo chegou — iniciando reprodução")
                            handler.removeCallbacks(emptyRetryRunnable)
                            startPlayback()
                        }
                        newHash == currentTarget ->
                            Log.d(AppConstants.TAG_PLAYER, "[PLAYLIST-UPDATE] Sem mudanças")
                        else -> {
                            Log.i(AppConstants.TAG_PLAYER, "[PLAYLIST-UPDATE] ${items.size} itens; baixando antes de aplicar")
                            cacheManager.preCachePlaylist(items)
                            offlineCache.savePlaylist(items)
                            pendingItems = items
                            Log.i(AppConstants.TAG_PLAYER, "[PLAYLIST-UPDATE] Pronta — entra ao fim do item atual")
                        }
                    }
                }
            }

            if (refreshAgain) {
                refreshAgain = false
                handler.post { requestPlaylistRefresh("mudança durante busca") }
            }
        }
    }

    /**
     * Se o dashboard trocou a playlist do dispositivo, atualiza a sessão e
     * (opcionalmente) reassina o Realtime com o filtro da playlist nova.
     */
    private fun syncPlaylistId(serverPlaylistId: String?, reconnectRealtime: Boolean) {
        val sess = session.getSession() ?: return
        if (serverPlaylistId.isNullOrBlank() || serverPlaylistId == sess.playlistId) return
        Log.i(AppConstants.TAG_PLAYER, "[PLAYLIST-UPDATE] playlist_id mudou: ${sess.playlistId} → $serverPlaylistId")
        session.updatePlaylistId(serverPlaylistId)
        if (reconnectRealtime) realtime.connect(sess.deviceId, serverPlaylistId, sess.signalTopic)
    }

    /** Assinatura da playlist: muda se qualquer item, ordem, arquivo, duração ou modo mudar */
    private fun playlistSignature(items: List<PlaylistItem>): Int =
        items.joinToString("|") {
            "${it.id}:${it.orderIndex}:${it.mediaFile.fileUrl}:${it.duration}:${it.isVerticalDisplay}:${it.isMarkedVertical}"
        }.hashCode()

    // ─── Service & Fullscreen ─────────────────────────────────────────────

    private fun ensureServiceRunning() {
        val intent = Intent(this, PlayerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun setImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    // ─── View binding ─────────────────────────────────────────────────────

    private fun bindViews() {
        pairingContainer = findViewById(R.id.pairingContainer)
        etCode           = findViewById(R.id.etCode)
        tvPairingStatus  = findViewById(R.id.tvPairingStatus)
        progressPairing  = findViewById(R.id.progressPairing)

        syncContainer    = findViewById(R.id.syncContainer)
        progressSync     = findViewById(R.id.progressSync)
        tvSyncStatus     = findViewById(R.id.tvSyncStatus)

        playerContainer  = findViewById(R.id.playerContainer)
        playerView       = findViewById(R.id.playerView)
        imageView        = findViewById(R.id.imageView)
        tvDeviceName     = findViewById(R.id.tvDeviceName)

        setupPairingInput()

        // [ORIENTATION] Reaplica a rotação quando o tamanho da tela fica conhecido
        // (primeiro layout) ou muda
        (playerContainer.parent as? View)?.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) handler.post { updatePlayerRotation() }
        }
    }
}
