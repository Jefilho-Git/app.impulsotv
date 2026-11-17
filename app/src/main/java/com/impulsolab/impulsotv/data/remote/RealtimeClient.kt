package com.impulsolab.impulsotv.data.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.impulsolab.impulsotv.constants.AppConstants
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * RealtimeClient — Supabase Realtime via protocolo Phoenix
 * ════════════════════════════════════════════════════════════════
 * Implementa o protocolo Phoenix Channels sobre WebSocket para
 * escutar mudanças nas tabelas do Supabase em tempo real.
 *
 * Eventos monitorados:
 *   1. UPDATE em devices WHERE id = deviceId
 *      → restart_requested_at alterado (comando de reset pelo dashboard)
 *      → is_connected = false (desconexão pelo dashboard)
 *      → playlist_id alterado (playlist trocada no dashboard)
 *
 *   2. INSERT/UPDATE/DELETE em playlist_items WHERE playlist_id = playlistId
 *      → detecta mudanças na playlist em tempo real
 *
 * Protocolo Phoenix serializer v1 (vsn=1.0.0) — mensagens como OBJETOS JSON:
 *   {"topic": ..., "event": ..., "payload": {...}, "ref": ..., "join_ref": ...}
 *
 * Formato do evento postgres_changes enviado pelo servidor:
 *   payload = {"ids":[...], "data":{"table":..., "type":"UPDATE",
 *              "record":{...}, "old_record":{...}, ...}}
 *
 * [REALTIME-PROTOCOL] — busque este tag para rastrear toda a lógica
 * ════════════════════════════════════════════════════════════════
 */
class RealtimeClient(
    private val onPlaylistChanged: () -> Unit,
    private val onRestartRequested: () -> Unit,
    /** Último restart_requested_at já processado (persistido entre reinícios do app). */
    private val loadLastRestartAt: () -> String? = { null },
    private val saveLastRestartAt: (String) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private val refCounter = AtomicInteger(0)

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var deviceId: String = ""
    @Volatile private var playlistId: String = ""
    @Volatile private var isConnected = false
    @Volatile private var shouldReconnect = true

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WebSocket: sem timeout de leitura
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val reconnectRunnable = Runnable {
        if (shouldReconnect && deviceId.isNotEmpty()) openWebSocket()
    }

    private val phoenixHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isConnected) return
            send(phoenixMessage(topic = "phoenix", event = "heartbeat", payload = JsonObject()))
            Log.v(AppConstants.TAG_REALTIME, "Phoenix heartbeat enviado")
            handler.postDelayed(this, AppConstants.REALTIME_PHOENIX_HEARTBEAT_MS)
        }
    }

    // ─── Conexão ──────────────────────────────────────────────────────────

    /**
     * Conecta ao Supabase Realtime e assina os canais do dispositivo.
     * Se já houver uma conexão aberta, ela é fechada e substituída
     * (ex.: playlist do dispositivo foi trocada no dashboard).
     *
     * @param deviceId   UUID do dispositivo (tabela devices)
     * @param playlistId UUID da playlist vinculada
     */
    fun connect(deviceId: String, playlistId: String) {
        closeSocket("Reconectando")
        this.deviceId = deviceId
        this.playlistId = playlistId
        shouldReconnect = true
        openWebSocket()
    }

    fun disconnect() {
        shouldReconnect = false
        closeSocket("App encerrado")
        Log.i(AppConstants.TAG_REALTIME, "Desconectado")
    }

    private fun closeSocket(reason: String) {
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(phoenixHeartbeatRunnable)
        val ws = webSocket
        webSocket = null
        isConnected = false
        ws?.close(1000, reason)
    }

    // ─── WebSocket ────────────────────────────────────────────────────────

    private fun openWebSocket() {
        Log.i(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] Conectando...")
        val request = Request.Builder().url(AppConstants.REALTIME_WS_URL).build()
        webSocket = http.newWebSocket(request, object : WebSocketListener() {

            // Ignora callbacks de sockets antigos (substituídos por connect/reconexão)
            private fun isCurrent(ws: WebSocket) = ws === webSocket

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(webSocket)) return
                isConnected = true
                Log.i(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] Conectado")
                joinChannels()
                handler.removeCallbacks(phoenixHeartbeatRunnable)
                handler.postDelayed(phoenixHeartbeatRunnable, AppConstants.REALTIME_PHOENIX_HEARTBEAT_MS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(webSocket)) return
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(webSocket)) return
                Log.e(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] Falha: ${t.message}")
                isConnected = false
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(webSocket)) return
                Log.w(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] Fechado: $code $reason")
                isConnected = false
                scheduleReconnect()
            }
        })
    }

    private fun send(message: JsonObject) {
        webSocket?.send(message.toString())
    }

    private fun phoenixMessage(
        topic: String,
        event: String,
        payload: JsonObject,
        joinRef: String? = null
    ): JsonObject = JsonObject().apply {
        addProperty("topic", topic)
        addProperty("event", event)
        add("payload", payload)
        addProperty("ref", nextRef())
        if (joinRef != null) addProperty("join_ref", joinRef)
    }

    // ─── Join Channels ────────────────────────────────────────────────────

    /**
     * [REALTIME-PROTOCOL] Junta duas assinaturas de postgres_changes em um
     * único canal para minimizar o número de joins.
     *
     * A autenticação vem do parâmetro `apikey` da URL do WebSocket; não
     * enviamos `access_token` porque a chave publishable (sb_publishable_…)
     * não é um JWT e seria rejeitada como token.
     */
    private fun joinChannels() {
        val topic = "realtime:impulsotv-$deviceId"   // nome único por dispositivo

        val changes = JsonArray().apply {
            // 1: mudanças no próprio dispositivo (restart / desconexão / troca de playlist)
            add(JsonObject().apply {
                addProperty("event", "UPDATE")
                addProperty("schema", "public")
                addProperty("table", "devices")
                addProperty("filter", "id=eq.$deviceId")
            })
            // 2: mudanças na playlist (itens adicionados/removidos/reordenados)
            add(JsonObject().apply {
                addProperty("event", "*")
                addProperty("schema", "public")
                addProperty("table", "playlist_items")
                addProperty("filter", "playlist_id=eq.$playlistId")
            })
        }

        val config = JsonObject().apply {
            add("broadcast", JsonObject().apply { addProperty("self", false) })
            add("presence", JsonObject().apply { addProperty("key", "") })
            add("postgres_changes", changes)
        }
        val payload = JsonObject().apply { add("config", config) }

        val joinRef = nextRef()
        send(phoenixMessage(topic = topic, event = "phx_join", payload = payload, joinRef = joinRef))
        Log.d(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] Join enviado: $topic")
    }

    // ─── Message Handling ─────────────────────────────────────────────────

    /**
     * [REALTIME-PROTOCOL] Processa mensagens recebidas do servidor Phoenix.
     *
     * Eventos relevantes:
     *   "phx_reply"        → confirmação (ou erro) do join / heartbeat
     *   "postgres_changes" → mudança em tabela monitorada
     *   "system"           → status das assinaturas postgres_changes
     */
    private fun handleMessage(text: String) {
        try {
            Log.v(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] Raw: $text")
            val msg = JsonParser.parseString(text).asJsonObject
            val event = msg.get("event")?.takeIf { !it.isJsonNull }?.asString ?: return
            val payload = msg.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

            when (event) {
                "phx_reply" -> {
                    val status = payload.get("status")?.takeIf { !it.isJsonNull }?.asString
                    if (status == "ok") {
                        Log.v(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] phx_reply: ok")
                    } else {
                        Log.e(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] phx_reply: $status ${payload.get("response")}")
                    }
                }

                "system" -> Log.i(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] system: $payload")

                "postgres_changes" -> {
                    // Formato do servidor: payload.data.{table,type,record}
                    // (fallback para o formato achatado {table,eventType,new} por robustez)
                    val data = payload.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: payload
                    val table = data.get("table")?.takeIf { !it.isJsonNull }?.asString ?: return
                    val type = (data.get("type") ?: data.get("eventType"))
                        ?.takeIf { !it.isJsonNull }?.asString ?: return
                    val record = (data.get("record") ?: data.get("new"))
                        ?.takeIf { it.isJsonObject }?.asJsonObject

                    Log.d(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] Mudança detectada: $type em $table")

                    when (table) {
                        "devices" -> handleDeviceUpdate(record, type)
                        "playlist_items" -> handlePlaylistChange(type)
                    }
                }

                "phx_error" -> {
                    Log.e(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] Erro no canal: $payload")
                    // Canal caiu do lado do servidor: força um novo socket + join
                    webSocket?.cancel()
                }
                "phx_close" -> Log.w(AppConstants.TAG_REALTIME, "[REALTIME-PROTOCOL] Canal fechado")
            }
        } catch (e: Exception) {
            Log.w(AppConstants.TAG_REALTIME, "Erro ao processar mensagem: ${e.message}")
        }
    }

    /**
     * [RESTART] Recebeu UPDATE no registro do dispositivo.
     *
     * O registro recebe UPDATEs frequentes (heartbeat / last_checkin), e
     * restart_requested_at permanece preenchido depois de um reset. Por isso
     * só disparamos o reset quando o VALOR muda em relação ao último já visto.
     * Sem referência salva (primeiro evento após o pareamento), o valor
     * atual é adotado como referência sem disparar reset.
     */
    private fun handleDeviceUpdate(record: JsonObject?, eventType: String) {
        if (eventType != "UPDATE" || record == null) return

        // 1. Comando de Restart
        val restartAt = record.get("restart_requested_at")
            ?.takeIf { !it.isJsonNull }?.asString
        var restartRequested = false
        if (!restartAt.isNullOrBlank()) {
            val last = loadLastRestartAt()
            if (last == null) {
                saveLastRestartAt(restartAt)
                Log.d(AppConstants.TAG_REALTIME, "[RESTART] Referência inicial de restart_requested_at: $restartAt")
            } else if (last != restartAt) {
                saveLastRestartAt(restartAt)
                restartRequested = true
            }
        }

        // 2. Comando de Desconexão (is_connected = false)
        val isDeviceConnected = record.get("is_connected")
            ?.takeIf { !it.isJsonNull }?.asBoolean ?: true

        Log.d(AppConstants.TAG_REALTIME, "[SIGNAL] Device Update: isConnected=$isDeviceConnected restartAt=$restartAt")

        if (restartRequested || !isDeviceConnected) {
            val reason = if (!isDeviceConnected) "Desconexão (is_connected=false)" else "Restart (requested_at)"
            Log.i(AppConstants.TAG_REALTIME, "[SIGNAL] Comando recebido via Realtime: $reason")
            handler.post { onRestartRequested() }
            return
        }

        // 3. Playlist trocada no dashboard
        val newPlaylistId = record.get("playlist_id")
            ?.takeIf { !it.isJsonNull }?.asString
        if (!newPlaylistId.isNullOrBlank() && newPlaylistId != playlistId) {
            Log.i(AppConstants.TAG_REALTIME, "[PLAYLIST-UPDATE] Playlist do dispositivo trocada: $playlistId → $newPlaylistId")
            handler.post { onPlaylistChanged() }
        }
    }

    /**
     * [PLAYLIST-UPDATE] Recebeu mudança na tabela playlist_items.
     * Notifica o player para buscar a playlist nova em segundo plano.
     */
    private fun handlePlaylistChange(eventType: String) {
        Log.i(AppConstants.TAG_REALTIME, "[PLAYLIST-UPDATE] Mudança detectada: $eventType")
        handler.post { onPlaylistChanged() }
    }

    // ─── Reconexão ───────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        handler.removeCallbacks(phoenixHeartbeatRunnable)
        if (!shouldReconnect) return
        Log.i(AppConstants.TAG_REALTIME, "Reconectando em ${AppConstants.REALTIME_RECONNECT_DELAY_MS / 1000}s...")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, AppConstants.REALTIME_RECONNECT_DELAY_MS)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun nextRef(): String = refCounter.incrementAndGet().toString()
}
