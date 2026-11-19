package com.impulsolab.impulsotv.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.impulsolab.impulsotv.constants.AppConstants
import com.impulsolab.impulsotv.data.model.ConnectResponse
import com.impulsolab.impulsotv.data.model.DeviceApiResponse
import com.impulsolab.impulsotv.data.model.ItemMetadata
import com.impulsolab.impulsotv.data.model.PlaylistResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

/**
 * SupabaseClient
 * ════════════════════════════════════════════════════════════════
 * Cliente HTTP das Edge Functions do Supabase usadas pelo app:
 *   device-connect   → pareamento / reconexão (conflito, takeover)
 *   device-heartbeat → status online a cada 30s (409 = outro aparelho assumiu)
 *   device-api       → playlist do dispositivo (ou "disconnected")
 * Todas as chamadas são suspending functions — rodam em Dispatchers.IO.
 * Autenticação: apikey + Authorization Bearer com a chave publishable.
 * Depois do pareamento, a TV também envia x-device-token (Fase 2).
 * ════════════════════════════════════════════════════════════════
 */
class SupabaseClient {

    /** Token desta TV, entregue pela device-connect. Null antes do pareamento. */
    @Volatile var deviceToken: String? = null

    /** Fingerprint (UUID do app); usado no modo antigo, sem token. */
    @Volatile var fingerprint: String? = null

    // ─── HTTP client ──────────────────────────────────────────────────────

    private val http = OkHttpClient.Builder()
        .addInterceptor(com.impulsolab.impulsotv.manager.ServerClock.interceptor)   // hora do servidor
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ─── Gson com deserializer customizado para ItemMetadata ──────────────

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(ItemMetadata::class.java, object : JsonDeserializer<ItemMetadata> {
            override fun deserialize(
                json: JsonElement, type: Type, ctx: JsonDeserializationContext
            ): ItemMetadata {
                // metadata pode ser {} (objeto vazio) ou {"display_mode":"vertical"}
                return if (json.isJsonObject) {
                    val obj = json.asJsonObject
                    ItemMetadata(obj.get("display_mode")?.takeIf { !it.isJsonNull }?.asString)
                } else {
                    ItemMetadata.EMPTY
                }
            }
        })
        .create()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val telemetryGson: Gson = GsonBuilder().serializeNulls().create()

    // ─── Headers padrão Supabase ──────────────────────────────────────────

    private fun Request.Builder.supabaseHeaders(): Request.Builder = this
        .addHeader("apikey",        AppConstants.SUPABASE_ANON_KEY)
        .addHeader("Authorization", "Bearer ${AppConstants.SUPABASE_ANON_KEY}")
        .addHeader("Content-Type",  "application/json")
        .addHeader("Prefer",        "return=representation")
        .apply { deviceToken?.let { addHeader("x-device-token", it) } }

    // ════════════════════════════════════════════════════════════════════
    // EDGE FUNCTIONS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Chama a Edge Function 'device-connect'
     */
    suspend fun connectDevice(
        deviceCode: String,
        fingerprint: String,
        force: Boolean = false
    ): ConnectResponse? = withContext(Dispatchers.IO) {
        val url = "${AppConstants.FUNCTIONS_URL}/device-connect"
        val payload = mapOf(
            "device_code" to deviceCode.uppercase(),
            "fingerprint" to fingerprint,
            "connection_type" to "android",
            "force" to force
        )
        val body = gson.toJson(payload).toRequestBody(JSON)
        val request = Request.Builder().url(url).supabaseHeaders().post(body).build()

        try {
            http.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                Log.d(AppConstants.TAG_SUPABASE, "[FUNCTION] device-connect status=${response.code} body=$responseBody")
                gson.fromJson(responseBody, ConnectResponse::class.java)
            }
        } catch (e: Exception) {
            Log.e(AppConstants.TAG_SUPABASE, "[FUNCTION] Erro ao conectar: ${e.message}")
            null
        }
    }

    /**
     * Chama a Edge Function 'device-heartbeat'
     */
    suspend fun sendHeartbeatFunction(
        deviceCode: String,
        fingerprint: String,
        orientation: String,
        isOnline: Boolean,
        playerStatus: Map<String, Any?>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "${AppConstants.FUNCTIONS_URL}/device-heartbeat"
        val payload = mapOf(
            "device_code" to deviceCode.uppercase(),
            "fingerprint" to fingerprint,
            "player_status" to "playing",
            "screen_orientation" to orientation,
            "is_online" to isOnline,
            "app_version" to "${com.impulsolab.impulsotv.BuildConfig.VERSION_NAME}-android",
            // Hora do SERVIDOR (o relógio da TV atrasa sem internet/energia)
            "last_checkin" to com.impulsolab.impulsotv.manager.ServerClock.nowMillis(),
            // Ficha técnica (3.5+). Nulo = não enviada (o Gson omite).
            "player_status" to playerStatus
        )
        val body = gson.toJson(payload).toRequestBody(JSON)
        val request = Request.Builder().url(url).supabaseHeaders().post(body).build()

        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 409) {
                    throw IllegalStateException("TAKEOVER")
                }
                // 200 {"status":"disconnected"}: painel desconectou, outro
                // aparelho assumiu ou o token foi revogado.
                val status = try {
                    gson.fromJson(response.body?.string() ?: "", Map::class.java)?.get("status") as? String
                } catch (_: Exception) { null }
                if (status == "disconnected") {
                    throw IllegalStateException("TAKEOVER")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            if (e.message == "TAKEOVER") throw e
            Log.w(AppConstants.TAG_HEARTBEAT, "[FUNCTION] Heartbeat erro: ${e.message}")
            false
        }
    }

    /**
     * Chama a Edge Function 'device-api' para buscar playlist
     */
    suspend fun fetchPlaylistFunction(deviceCode: String): PlaylistResult =
        withContext(Dispatchers.IO) {
            val fp = fingerprint?.let { "&fingerprint=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
            val url = "${AppConstants.FUNCTIONS_URL}/device-api?device_code=${deviceCode.uppercase()}$fp"
            val request = Request.Builder().url(url).supabaseHeaders().get().build()

            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext PlaylistResult.Error("HTTP ${response.code}")
                    
                    val body = response.body?.string() ?: ""
                    val data = gson.fromJson(body, DeviceApiResponse::class.java)
                        ?: return@withContext PlaylistResult.Error("Resposta do servidor vazia")
                    
                    Log.d(AppConstants.TAG_SUPABASE, "[FUNCTION] device-api status=${data.status}")
                    
                    if (data.status == "disconnected") {
                        return@withContext PlaylistResult.Disconnected
                    }
                    
                    val mediaList = data.mediaFiles
                    if (mediaList == null) {
                        return@withContext PlaylistResult.Success(emptyList(), data.playlistId)
                    }

                    val items = mediaList.sortedBy { it.orderIndex }
                    PlaylistResult.Success(items, data.playlistId)
                }
            } catch (e: Exception) {
                Log.e(AppConstants.TAG_SUPABASE, "[FUNCTION] device-api erro: ${e.message}")
                PlaylistResult.Error(e.message ?: "Erro desconhecido", e)
            }
        }


    /**
     * Envia um lote da coleta de mídia (Edge Function media-telemetry).
     * Só com o token da TV; no modo antigo não envia nada.
     * @return código HTTP, ou -1 se não houve resposta. Nunca lança exceção.
     */
    /**
     * Envia um lote da comprovação de exibição (device-playback).
     * @return código HTTP, -1 sem resposta, 0 se não há o que enviar (sem token = modo antigo).
     */
    suspend fun sendPlayback(
        deviceCode: String,
        events: List<com.impulsolab.impulsotv.manager.PlaybackLog.Event>
    ): Int = withContext(Dispatchers.IO) {
        if (deviceToken == null || events.isEmpty()) return@withContext 0
        try {
            val payload = mapOf(
                "device_code" to deviceCode.uppercase(),
                "app_version" to "${com.impulsolab.impulsotv.BuildConfig.VERSION_NAME}-android",
                "events" to events.map { it.toPayload() }
            )
            val body = gson.toJson(payload).toRequestBody(JSON)
            val request = Request.Builder().url("${AppConstants.FUNCTIONS_URL}/device-playback")
                .supabaseHeaders().post(body).build()
            http.newCall(request).execute().use { response ->
                Log.d(AppConstants.TAG_SUPABASE, "[FUNCTION] device-playback status=${response.code} eventos=${events.size}")
                response.code
            }
        } catch (e: Exception) {
            Log.w(AppConstants.TAG_SUPABASE, "[FUNCTION] device-playback sem resposta: ${e.message}")
            -1
        }
    }

    suspend fun sendMediaTelemetry(
        deviceCode: String,
        events: List<com.impulsolab.impulsotv.manager.MediaTelemetry.Event>
    ): Int = withContext(Dispatchers.IO) {
        if (deviceToken == null || events.isEmpty()) return@withContext 0
        try {
            val payload = mapOf(
                "device_code" to deviceCode.uppercase(),
                "events" to events.map { it.toPayload() }
            )
            // serializeNulls: o servidor exige response_bytes/status null explícitos.
            val body = telemetryGson.toJson(payload).toRequestBody(JSON)
            val request = Request.Builder().url("${AppConstants.FUNCTIONS_URL}/media-telemetry")
                .supabaseHeaders().post(body).build()
            http.newCall(request).execute().use { response ->
                Log.d(AppConstants.TAG_SUPABASE, "[FUNCTION] media-telemetry status=${response.code} eventos=${events.size}")
                response.code
            }
        } catch (e: Exception) {
            Log.w(AppConstants.TAG_SUPABASE, "[FUNCTION] media-telemetry sem resposta: ${e.message}")
            -1
        }
    }
}
