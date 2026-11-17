package com.impulsolab.impulsotv.data.model

import com.google.gson.annotations.SerializedName
import com.impulsolab.impulsotv.constants.AppConstants

// ════════════════════════════════════════════════════════════════
// Modelos mapeados diretamente do schema Supabase
// snake_case do banco → camelCase Kotlin via @SerializedName
// ════════════════════════════════════════════════════════════════

/**
 * Tabela: public.devices
 * Campos relevantes para o app Android
 */
data class Device(
    @SerializedName("id")                    val id: String,
    @SerializedName("user_id")               val userId: String,
    @SerializedName("device_code")           val deviceCode: String,
    @SerializedName("name")                  val name: String,
    @SerializedName("location")              val location: String?,
    @SerializedName("playlist_id")           val playlistId: String?,   // null: dispositivo ainda sem playlist
    @SerializedName("is_online")             val isOnline: Boolean = false,
    @SerializedName("is_connected")          val isConnected: Boolean = false,
    @SerializedName("connection_type")       val connectionType: String?,
    @SerializedName("connection_fingerprint")val connectionFingerprint: String?,
    @SerializedName("restart_requested_at")  val restartRequestedAt: String?,
    @SerializedName("last_checkin")          val lastCheckin: String?
)

/**
 * Tabela: public.media_files
 * Metadados do arquivo de mídia no Supabase Storage
 */
data class MediaFile(
    @SerializedName("id")             val id: String,
    @SerializedName("file_url")       val fileUrl: String,
    @SerializedName("file_type")      val fileType: String,       // "video" | "image"
    @SerializedName("file_extension") val fileExtension: String,  // "mp4" | "webm" | "jpg"
    @SerializedName("file_size")      val fileSize: Long,
    @SerializedName("is_vertical")    val isVertical: Boolean,
    @SerializedName("width")          val width: Int,
    @SerializedName("height")         val height: Int,
    @SerializedName("original_name")  val originalName: String?
) {
    val isVideo: Boolean get() = fileType == "video"
    val isImage: Boolean get() = fileType == "image"
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height.toFloat() else 16f / 9f
}

/**
 * JSONB metadata de playlist_items
 * Ex: {"display_mode":"vertical"} ou {}
 */
data class ItemMetadata(
    @SerializedName("display_mode") val displayMode: String?
) {
    val isVertical: Boolean get() = displayMode?.equals(AppConstants.DISPLAY_MODE_VERTICAL, ignoreCase = true) == true

    companion object {
        val EMPTY = ItemMetadata(null)
    }
}

/**
 * Tabela: public.playlist_items
 * Join com media_files via alias "media_file" no select PostgREST
 *
 * Query usada:
 *   /rest/v1/playlist_items
 *   ?playlist_id=eq.UUID
 *   &select=id,order_index,duration,metadata,media_file:media_files(*)
 *   &order=order_index.asc
 */
data class PlaylistItem(
    @SerializedName("id")           val idFromApi: String?,
    @SerializedName("playlist_id")  val playlistId: String?,
    @SerializedName("order_index")  val orderIndex: Int,
    @SerializedName("duration")     val duration: Int,       // segundos
    @SerializedName("metadata")     val metadata: ItemMetadata?,
    @SerializedName("media_file")   private val nestedMediaFile: MediaFile?, // Formato PostgREST

    // Campos do formato achatado (Edge Function)
    @SerializedName("file_url")      val flatFileUrl: String? = null,
    @SerializedName("file_type")     val flatFileType: String? = null,
    @SerializedName("original_name") val flatOriginalName: String? = null,
    @SerializedName("file_extension")val flatFileExtension: String? = null,
    @SerializedName("file_size")     val flatFileSize: Long? = null,
    @SerializedName("is_vertical")   val flatIsVertical: Boolean? = null,
    @SerializedName("display_mode")  val flatDisplayMode: String? = null
) {
    /** ID único do item (da tabela playlist_items ou fallback para o id do arquivo) */
    val id: String get() = idFromApi ?: nestedMediaFile?.id ?: flatOriginalName ?: "unknown"

    /** Retorna os dados da mídia independente do formato da API */
    val mediaFile: MediaFile get() {
        if (nestedMediaFile != null) return nestedMediaFile
        return MediaFile(
            id = id,
            fileUrl = flatFileUrl ?: "",
            fileType = flatFileType ?: "video",
            fileExtension = flatFileExtension ?: "",
            fileSize = flatFileSize ?: 0L,
            isVertical = flatIsVertical ?: (flatDisplayMode == AppConstants.DISPLAY_MODE_VERTICAL),
            width = 0,
            height = 0,
            originalName = flatOriginalName ?: "Sem nome"
        )
    }

    val isVideo: Boolean get() = mediaFile.isVideo
    val isImage: Boolean get() = mediaFile.isImage

    /** Vertical = metadata diz "vertical"/"portrait" OU arquivo é intrinsecamente vertical */
    val isVerticalDisplay: Boolean get() {
        val metaMode = metadata?.displayMode
        val isMetaVert = metaMode?.equals("vertical", ignoreCase = true) == true ||
                         metaMode?.equals("portrait", ignoreCase = true) == true
        val isFlatVert = flatDisplayMode?.equals("vertical", ignoreCase = true) == true ||
                         flatDisplayMode?.equals("portrait", ignoreCase = true) == true
        val isDimVert = (mediaFile.height > 0 && mediaFile.height > mediaFile.width)
        return isMetaVert || isFlatVert || mediaFile.isVertical || flatIsVertical == true || isDimVert
    }

    /** Duração em ms. Vídeos usam duração natural do arquivo (ignoram este campo). */
    val durationMs: Long get() = duration * 1000L
}

/**
 * Respostas das Edge Functions do Supabase
 */
data class ConnectResponse(
    @SerializedName("status")       val status: String, // "connected" | "conflict" | "ok"
    @SerializedName("device")       val device: Device?,
    @SerializedName("current")      val conflictInfo: ConflictInfo?
)

data class ConflictInfo(
    @SerializedName("device_name")      val deviceName: String?,
    @SerializedName("connection_type")  val connectionType: String?,
    @SerializedName("last_connected_at") val lastConnectedAt: String?
)

data class DeviceApiResponse(
    @SerializedName("status")      val status: String?, // "ok" | "disconnected"
    @SerializedName("playlist_id") val playlistId: String?,
    @SerializedName("media_files") val mediaFiles: List<PlaylistItem>?
)

/**
 * Resultado de um reload de playlist — encapsula sucesso ou falha
 */
sealed class PlaylistResult {
    data class Success(
        val items: List<PlaylistItem>,
        /** playlist_id atual do dispositivo segundo o servidor (pode ter sido trocada no dashboard) */
        val playlistId: String? = null
    ) : PlaylistResult()
    object Disconnected : PlaylistResult()
    data class Error(val message: String, val cause: Throwable? = null) : PlaylistResult()
}
