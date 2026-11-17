package com.impulsolab.impulsotv.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.impulsolab.impulsotv.constants.AppConstants
import com.impulsolab.impulsotv.data.model.ConnectResponse
import com.impulsolab.impulsotv.data.model.Device
import com.impulsolab.impulsotv.data.model.DeviceApiResponse
import com.impulsolab.impulsotv.data.model.ItemMetadata
import com.impulsolab.impulsotv.data.model.PlaylistItem
import com.impulsolab.impulsotv.data.model.PlaylistResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

/**
 * SupabaseClient
 * ════════════════════════════════════════════════════════════════
 * Cliente HTTP para a API REST do Supabase (PostgREST).
 * Todas as chamadas são suspending functions — rodam em Dispatchers.IO.
 *
 * Autenticação: apikey + Authorization Bearer com a anon key.
 * RLS policies necessárias (ver ARCHITECTURE.md):
 *   - devices: SELECT para anon WHERE device_code = ?
 *   - devices: UPDATE para anon WHERE id = ?
 *   - playlist_items: SELECT para anon WHERE playlist_id = ?
 *   - media_files: SELECT para anon
 * ════════════════════════════════════════════════════════════════
 */
class SupabaseClient {

    // ─── HTTP client ──────────────────────────────────────────────────────

    private val http = OkHttpClient.Builder()
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

    // ─── Headers padrão Supabase ──────────────────────────────────────────

    private fun Request.Builder.supabaseHeaders(): Request.Builder = this
        .addHeader("apikey",        AppConstants.SUPABASE_ANON_KEY)
        .addHeader("Authorization", "Bearer ${AppConstants.SUPABASE_ANON_KEY}")
        .addHeader("Content-Type",  "application/json")
        .addHeader("Prefer",        "return=representation")

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
        isOnline: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "${AppConstants.FUNCTIONS_URL}/device-heartbeat"
        val payload = mapOf(
            "device_code" to deviceCode.uppercase(),
            "fingerprint" to fingerprint,
            "player_status" to "playing",
            "screen_orientation" to orientation,
            "is_online" to isOnline,
            "app_version" to "3.0.0-android",
            "last_checkin" to System.currentTimeMillis()
        )
        val body = gson.toJson(payload).toRequestBody(JSON)
        val request = Request.Builder().url(url).supabaseHeaders().post(body).build()

        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 409) {
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
            val url = "${AppConstants.FUNCTIONS_URL}/device-api?device_code=${deviceCode.uppercase()}"
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
     * Chama a Edge Function 'device-disconnect'
     */
    suspend fun disconnectDevice(deviceCode: String, fingerprint: String) = withContext(Dispatchers.IO) {
        val url = "${AppConstants.FUNCTIONS_URL}/device-disconnect"
        val payload = mapOf(
            "device_code" to deviceCode.uppercase(),
            "fingerprint" to fingerprint
        )
        val body = gson.toJson(payload).toRequestBody(JSON)
        val request = Request.Builder().url(url).supabaseHeaders().post(body).build()
        try {
            http.newCall(request).execute().close()
        } catch (_: Exception) {}
    }

    // ════════════════════════════════════════════════════════════════════
    // PAIRING: busca dispositivo pelo device_code
    // ════════════════════════════════════════════════════════════════════

    /**
     * Busca o dispositivo no Supabase pelo código de 6 caracteres
     * que o usuário digitou na tela de pairing.
     *
     * Query: GET /rest/v1/devices?device_code=eq.CODE&select=*
     *
     * @param code  código alfanumérico de 6 chars (ex: "UBPTXF")
     * @return Device encontrado, ou null se código inválido/não encontrado
     */
    suspend fun findDeviceByCode(code: String): Device? = withContext(Dispatchers.IO) {
        val trimmedCode = code.trim().uppercase()
        val url = "${AppConstants.REST_URL}/devices" +
                  "?device_code=eq.$trimmedCode" +
                  "&select=*"

        Log.d(AppConstants.TAG_SUPABASE, "[PAIRING] Buscando device: '$trimmedCode' (len=${trimmedCode.length})")

        val request = Request.Builder().url(url).supabaseHeaders().get().build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(AppConstants.TAG_SUPABASE, "[PAIRING] HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val type = object : TypeToken<List<Device>>() {}.type
                val devices: List<Device> = gson.fromJson(body, type)

                devices.firstOrNull().also {
                    if (it != null) Log.i(AppConstants.TAG_SUPABASE, "[PAIRING] Device encontrado: ${it.name} (${it.id})")
                    else Log.w(AppConstants.TAG_SUPABASE, "[PAIRING] Código não encontrado: $trimmedCode")
                }
            }
        } catch (e: Exception) {
            Log.e(AppConstants.TAG_SUPABASE, "[PAIRING] Erro: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PAIRING: registra o app Android no registro do dispositivo
    // ════════════════════════════════════════════════════════════════════

    /**
     * Após o pairing bem-sucedido, atualiza o registro do dispositivo
     * com o fingerprint do hardware Android e o tipo de conexão.
     *
     * Campos atualizados:
     *   connection_type        = "android"
     *   connection_fingerprint = MD5(hardwareId)
     *   is_connected           = true
     *   last_connected_at      = now()
     */
    suspend fun registerDeviceConnection(
        deviceId: String,
        fingerprint: String
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "${AppConstants.REST_URL}/devices?id=eq.$deviceId"
        val body = """
            {
              "connection_type": "android",
              "connection_fingerprint": "$fingerprint",
              "is_connected": true,
              "last_connected_at": "now()"
            }
        """.trimIndent().toRequestBody(JSON)

        val request = Request.Builder().url(url).supabaseHeaders()
            .patch(body).build()

        try {
            http.newCall(request).execute().use { response ->
                response.isSuccessful.also {
                    Log.i(AppConstants.TAG_SUPABASE, "[PAIRING] Registro de conexão: ${if (it) "OK" else "FALHOU ${response.code}"}")
                }
            }
        } catch (e: Exception) {
            Log.e(AppConstants.TAG_SUPABASE, "[PAIRING] Erro ao registrar: ${e.message}")
            false
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PLAYLIST: carrega itens com join em media_files
    // ════════════════════════════════════════════════════════════════════

    /**
     * Carrega todos os itens da playlist com os metadados do arquivo de mídia.
     *
     * Query PostgREST com embedded resource:
     *   GET /rest/v1/playlist_items
     *     ?playlist_id=eq.UUID
     *     &select=id,order_index,duration,metadata,
     *             media_file:media_files!media_file_id(
     *               id,file_url,file_type,file_extension,
     *               file_size,is_vertical,width,height,original_name
     *             )
     *     &order=order_index.asc
     *
     * O alias "media_file" é criado pelo PostgREST via "media_file:media_files"
     * e mapeia para o campo @SerializedName("media_file") no PlaylistItem.
     *
     * @param playlistId UUID da playlist vinculada ao dispositivo
     * @return Lista ordenada de PlaylistItem, ou lista vazia em caso de erro
     */
    suspend fun fetchPlaylistItems(playlistId: String): List<PlaylistItem> =
        withContext(Dispatchers.IO) {
            val select = listOf(
                "id", "order_index", "duration", "metadata",
                "media_file:media_files!media_file_id(" +
                    "id,file_url,file_type,file_extension," +
                    "file_size,is_vertical,width,height,original_name)"
            ).joinToString(",")

            val url = "${AppConstants.REST_URL}/playlist_items" +
                      "?playlist_id=eq.$playlistId" +
                      "&select=$select" +
                      "&order=order_index.asc"

            Log.d(AppConstants.TAG_SUPABASE, "[PLAYLIST] Carregando itens de: $playlistId")

            val request = Request.Builder().url(url).supabaseHeaders().get().build()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(AppConstants.TAG_SUPABASE, "[PLAYLIST] HTTP ${response.code}")
                        return@withContext emptyList()
                    }
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val type = object : TypeToken<List<PlaylistItem>>() {}.type
                    val items: List<PlaylistItem> = gson.fromJson(body, type)
                    Log.i(AppConstants.TAG_SUPABASE, "[PLAYLIST] ${items.size} itens carregados")
                    items
                }
            } catch (e: Exception) {
                Log.e(AppConstants.TAG_SUPABASE, "[PLAYLIST] Erro: ${e.message}")
                emptyList()
            }
        }

    // ════════════════════════════════════════════════════════════════════
    // HEARTBEAT: atualiza status online do dispositivo
    // ════════════════════════════════════════════════════════════════════

    /**
     * Enviado a cada HEARTBEAT_INTERVAL_MS para sinalizar ao dashboard
     * que o dispositivo está online e funcionando.
     *
     * Campos atualizados:
     *   last_checkin = now()
     *   is_online    = true
     */
    suspend fun sendHeartbeat(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val url = "${AppConstants.REST_URL}/devices?id=eq.$deviceId"
        val body = """{"last_checkin": "now()", "is_online": true}"""
            .toRequestBody(JSON)

        val request = Request.Builder().url(url).supabaseHeaders()
            .patch(body).build()

        try {
            http.newCall(request).execute().use { response ->
                response.isSuccessful.also {
                    Log.v(AppConstants.TAG_HEARTBEAT, "Heartbeat: ${if (it) "OK" else "FALHOU ${response.code}"}")
                }
            }
        } catch (e: Exception) {
            Log.w(AppConstants.TAG_HEARTBEAT, "Erro: ${e.message}")
            false
        }
    }

    /**
     * Marca dispositivo como offline quando o app está sendo encerrado.
     * Fire-and-forget: não esperamos a resposta.
     */
    suspend fun setDeviceOffline(deviceId: String) = withContext(Dispatchers.IO) {
        try {
            val url = "${AppConstants.REST_URL}/devices?id=eq.$deviceId"
            val body = """{"is_online": false, "is_connected": false}"""
                .toRequestBody(JSON)
            val request = Request.Builder().url(url).supabaseHeaders().patch(body).build()
            http.newCall(request).execute().close()
        } catch (_: Exception) { /* best-effort */ }
    }
}
