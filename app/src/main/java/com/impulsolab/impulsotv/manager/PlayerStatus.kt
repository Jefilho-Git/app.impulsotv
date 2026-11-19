package com.impulsolab.impulsotv.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * PlayerStatus
 * ════════════════════════════════════════════════════════════════
 * Ficha técnica da TV enviada junto com o heartbeat (a cada 30 s), para o
 * painel mostrar "tocando agora", espaço livre, modelo e rede.
 *
 * Regras de segurança (nunca atrapalhar o player):
 *   - snapshot() nunca lança exceção: em qualquer erro devolve null e o
 *     heartbeat segue sem a ficha
 *   - só leitura do sistema; nada é gravado
 *   - update: andamento da atualização em curso (UpdateStatus)
 *   - do item atual vai só o ID da mídia (o painel resolve o nome), a
 *     posição na playlist e se toca do armazenamento ou da internet
 * ════════════════════════════════════════════════════════════════
 */
object PlayerStatus {

    private data class NowPlaying(
        val mediaFileId: String,
        val type: String,
        val position: Int,
        val total: Int,
        val since: Long,
        val source: String,
    )

    @Volatile private var nowPlaying: NowPlaying? = null

    /** Chamado pelo player ao começar cada item. */
    fun onItemStarted(mediaFileId: String, isVideo: Boolean, position: Int, total: Int, fromCache: Boolean) {
        nowPlaying = NowPlaying(
            mediaFileId = mediaFileId,
            type = if (isVideo) "video" else "image",
            position = position,
            total = total,
            since = ServerClock.nowMillis(),
            source = if (fromCache) "cache" else "network",
        )
    }

    /** Playlist vazia, desconexão etc. */
    fun clear() {
        nowPlaying = null
    }

    fun snapshot(context: Context, cacheBytes: Long, cacheFiles: Int): Map<String, Any?>? = try {
        val np = nowPlaying
        val metrics = context.resources.displayMetrics
        val dataDir = context.filesDir
        buildMap {
            if (np != null) {
                put("now_playing", mapOf(
                    "media_file_id" to np.mediaFileId,
                    "type" to np.type,
                    "position" to np.position,
                    "total" to np.total,
                    "since" to np.since,
                    "source" to np.source,
                ))
            }
            put("device", mapOf(
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "android" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
                "screen" to "${maxOf(metrics.widthPixels, metrics.heightPixels)}x${minOf(metrics.widthPixels, metrics.heightPixels)}",
            ))
            put("storage", mapOf(
                "free_bytes" to dataDir.usableSpace,
                "total_bytes" to dataDir.totalSpace,
                "cache_bytes" to cacheBytes,
                "cache_files" to cacheFiles,
            ))
            put("network", networkType(context))
            put("uptime_ms", SystemClock.elapsedRealtime())
            com.impulsolab.impulsotv.update.UpdateStatus.toPayload()?.let { put("update", it) }
        }
    } catch (e: Exception) {
        Log.w("ImpulsoTV.Status", "ficha técnica ignorada: ${e.message}")
        null
    }

    private fun networkType(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
        } else {
            @Suppress("DEPRECATION")
            when (cm.activeNetworkInfo?.type) {
                null -> "none"
                ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "cellular"
                else -> "other"
            }
        }
    } catch (_: Exception) {
        "other"
    }
}
