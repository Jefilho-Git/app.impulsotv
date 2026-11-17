package com.impulsolab.impulsotv.constants

import com.impulsolab.impulsotv.BuildConfig

/**
 * AppConstants — fonte única de todas as configurações do app.
 *
 * Seções:
 *   [SUPABASE]    endpoints e autenticação
 *   [TIMING]      todos os intervalos em ms
 *   [SESSION]     chaves do SharedPreferences
 *   [TAGS]        tags do Logcat por componente
 *   [PLAYER]      constantes do player de mídia
 *   [NOTIFICATION] IDs do ForegroundService
 */
object AppConstants {

    // ── [SUPABASE] ───────────────────────────────────────────────────────
    val SUPABASE_URL: String      = BuildConfig.SUPABASE_URL
    val SUPABASE_ANON_KEY: String = BuildConfig.SUPABASE_ANON_KEY
    const val STORAGE_BUCKET     = "media-files"

    // Endpoints REST (PostgREST)
    val REST_URL      get() = "$SUPABASE_URL/rest/v1"
    val STORAGE_URL   get() = "$SUPABASE_URL/storage/v1"
    val FUNCTIONS_URL get() = "$SUPABASE_URL/functions/v1"

    // WebSocket Realtime
    // vsn=1.0.0 = Phoenix protocol v1 (formato de array JSON)
    val REALTIME_WS_URL get() =
        "wss://${SUPABASE_URL.removePrefix("https://")}/realtime/v1/websocket" +
        "?apikey=$SUPABASE_ANON_KEY&vsn=1.0.0"

    // ── [TIMING] ms ──────────────────────────────────────────────────────

    /** Heartbeat para o Supabase — atualiza last_checkin e is_online */
    const val HEARTBEAT_INTERVAL_MS         = 30_000L

    /** Ping Phoenix do WebSocket Realtime — mantém a conexão viva */
    const val REALTIME_PHOENIX_HEARTBEAT_MS = 25_000L

    /** Tentativa de reconexão após queda do WebSocket */
    const val REALTIME_RECONNECT_DELAY_MS   = 5_000L

    /** Duração padrão de imagens sem duration definida */
    const val IMAGE_DEFAULT_DURATION_MS     = 30_000L

    /** Crossfade entre itens da playlist (ms) */
    const val TRANSITION_DURATION_MS        = 400L

    /** Ping Activity → Service para monitoramento de saúde */
    const val ACTIVITY_PING_INTERVAL_MS     = 15_000L

    /** Threshold: se sem ping por este tempo, Service reinicia a Activity */
    const val ACTIVITY_DEAD_THRESHOLD_MS    = 30_000L

    /** Retry após erro de rede na busca de playlist */
    const val PLAYLIST_RETRY_DELAY_MS       = 8_000L

    /** Intervalo de polling para playlist (Fallback muito mais conservador) */
    const val PLAYLIST_POLLING_INTERVAL_MS  = 300_000L // 5 minutos (Realtime cuida do instantâneo)

    /** Tempo de retenção de arquivos órfãos em cache (30 dias) */
    const val CACHE_RETENTION_DAYS = 30L

    // ── [SESSION] SharedPreferences keys ────────────────────────────────
    const val PREF_FILE          = "impulsotv_session"
    const val PREF_DEVICE_ID     = "device_id"
    const val PREF_DEVICE_CODE   = "device_code"
    const val PREF_DEVICE_NAME   = "device_name"
    const val PREF_PLAYLIST_ID   = "playlist_id"
    const val PREF_FINGERPRINT   = "fingerprint"
    const val PREF_LAST_RESTART  = "last_restart_at"

    // ── [TAGS] Logcat — filtre com: adb logcat -s ImpulsoTV.* ───────────
    const val TAG_APP      = "ImpulsoTV.App"
    const val TAG_MAIN     = "ImpulsoTV.Main"
    const val TAG_PAIRING  = "ImpulsoTV.Pairing"
    const val TAG_PLAYER   = "ImpulsoTV.Player"
    const val TAG_SUPABASE = "ImpulsoTV.Supabase"
    const val TAG_REALTIME = "ImpulsoTV.Realtime"
    const val TAG_CACHE    = "ImpulsoTV.Cache"
    const val TAG_SERVICE  = "ImpulsoTV.Service"
    const val TAG_BOOT     = "ImpulsoTV.Boot"
    const val TAG_HEARTBEAT= "ImpulsoTV.Heartbeat"

    // ── [PLAYER] ─────────────────────────────────────────────────────────
    const val DISPLAY_MODE_VERTICAL = "vertical"

    // ── [NOTIFICATION] ───────────────────────────────────────────────────
    const val NOTIFICATION_CHANNEL_ID   = "impulsotv_player"
    const val NOTIFICATION_CHANNEL_NAME = "ImpulsoTV Player"
    const val NOTIFICATION_ID           = 1001
}
