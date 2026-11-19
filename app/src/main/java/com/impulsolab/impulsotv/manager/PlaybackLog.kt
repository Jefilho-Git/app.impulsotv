package com.impulsolab.impulsotv.manager

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * PlaybackLog — comprovação de exibição (3.6.0)
 * ════════════════════════════════════════════════════════════════
 * Registra cada mídia que ESTEVE NA TELA: quando apareceu (hora do servidor),
 * por quanto tempo ficou e se tocou até o fim. Enviado para a Edge Function
 * device-playback junto com o heartbeat, com o token da TV.
 *
 * Regras de segurança (nunca atrapalhar o player):
 *   - nada aqui lança exceção para quem chama
 *   - item que não chegou à tela (erro, arquivo faltando) não é registrado
 *   - fila limitada (MAX_QUEUE): se encher, descarta o registro mais antigo
 *   - a fila vai para o disco (no máximo 1x/min e ao parar o app), então
 *     exibições feitas sem internet são enviadas quando a conexão voltar
 *   - 503 (coleta desligada no servidor) → pausa os envios por 1 hora
 * ════════════════════════════════════════════════════════════════
 */
object PlaybackLog {

    data class Event(
        val eventId: String,
        val mediaFileId: String,
        val playlistId: String?,
        val mediaType: String,
        val startedAt: Long,
        val durationMs: Long,
        val completed: Boolean,
        val source: String,
    ) {
        fun toPayload(): Map<String, Any?> = mapOf(
            "event_id" to eventId,
            "media_file_id" to mediaFileId,
            "playlist_id" to playlistId,
            "media_type" to mediaType,
            "started_at" to startedAt,
            "duration_ms" to durationMs,
            "completed" to completed,
            "source" to source,
        )
    }

    private data class Open(
        val mediaFileId: String,
        val playlistId: String?,
        val mediaType: String,
        val source: String,
        var onScreenAt: Long = 0L,        // hora do servidor
        var onScreenElapsed: Long = 0L,   // relógio monotônico
    )

    private const val TAG = "ImpulsoTV.Playback"
    private const val MAX_QUEUE = 5000
    const val MAX_BATCH = 100
    private const val MAX_DURATION_MS = 24 * 3600_000L
    private const val SAVE_EVERY_MS = 60_000L
    private const val DISABLED_PAUSE_MS = 60 * 60 * 1000L
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private val gson = Gson()
    private val queue = ArrayDeque<Event>()
    private var open: Open? = null
    private var file: File? = null
    private var dirty = false
    private var lastSave = 0L
    @Volatile private var pausedUntil = 0L

    /** Carrega a fila salva (chamado no onCreate). */
    fun init(context: Context) = guard {
        synchronized(queue) {
            if (file != null) return@guard
            val f = File(context.filesDir, "playback-queue.json")
            file = f
            if (f.exists()) {
                val type = object : TypeToken<List<Event>>() {}.type
                val saved: List<Event>? = try { gson.fromJson(f.readText(), type) } catch (_: Exception) { null }
                saved?.takeLast(MAX_QUEUE)?.forEach { queue.addLast(it) }
                Log.i(TAG, "Fila de exibições restaurada: ${queue.size}")
            }
        }
    }

    /** Novo item começou a ser preparado. Fecha o anterior como interrompido. */
    fun onItemStarted(mediaFileId: String, playlistId: String?, isVideo: Boolean, fromCache: Boolean) = guard {
        finish(completed = false)
        if (!UUID_RE.matches(mediaFileId)) return@guard   // sem ID da biblioteca não dá para comprovar
        synchronized(queue) {
            open = Open(
                mediaFileId = mediaFileId.lowercase(),
                playlistId = playlistId?.takeIf { UUID_RE.matches(it) }?.lowercase(),
                mediaType = if (isVideo) "video" else "image",
                source = if (fromCache) "cache" else "network",
            )
        }
    }

    /** O item apareceu na tela (1º quadro do vídeo / imagem carregada). */
    fun onScreen() = guard {
        synchronized(queue) {
            val o = open ?: return@guard
            if (o.onScreenElapsed == 0L) {
                o.onScreenAt = ServerClock.nowMillis()
                o.onScreenElapsed = SystemClock.elapsedRealtime()
            }
        }
    }

    /** Fecha o item atual. Só registra se ele chegou à tela. */
    fun finish(completed: Boolean) = guard {
        synchronized(queue) {
            val o = open ?: return@guard
            open = null
            if (o.onScreenElapsed == 0L) return@guard
            val duration = (SystemClock.elapsedRealtime() - o.onScreenElapsed).coerceIn(0L, MAX_DURATION_MS)
            if (queue.size >= MAX_QUEUE) queue.removeFirst()
            queue.addLast(Event(UUID.randomUUID().toString(), o.mediaFileId, o.playlistId, o.mediaType, o.onScreenAt, duration, completed, o.source))
            dirty = true
        }
    }

    /** Próximo lote a enviar, sem tirar da fila (só sai depois de confirmado). */
    fun peekBatch(): List<Event> {
        if (SystemClock.elapsedRealtime() < pausedUntil) return emptyList()
        return synchronized(queue) { queue.take(MAX_BATCH) }
    }

    /**
     * Resultado do envio (código HTTP, ou -1 sem conexão).
     *  2xx → confirmado, sai da fila · 503 → pausa 1 h e mantém
     *  429, -1, 5xx → mantém (mesmo event_id, sem duplicar) · outros 4xx → descarta
     * @return true se o lote saiu da fila (pode mandar o próximo).
     */
    fun onBatchResult(batch: List<Event>, httpCode: Int): Boolean {
        val drop = httpCode in 200..299 || (httpCode in 400..499 && httpCode != 429)
        if (httpCode == 503) pausedUntil = SystemClock.elapsedRealtime() + DISABLED_PAUSE_MS
        if (drop) {
            val ids = batch.mapTo(HashSet()) { it.eventId }
            synchronized(queue) {
                queue.removeAll { it.eventId in ids }
                dirty = true
            }
        }
        return drop
    }

    /** Salva a fila se mudou e já passou 1 min (chamado pelo heartbeat). */
    fun maybeSave() {
        if (SystemClock.elapsedRealtime() - lastSave >= SAVE_EVERY_MS) saveNow()
    }

    /** Salva a fila agora (ao parar o app). Escrita atômica: tmp + rename. */
    fun saveNow() = guard {
        val f = file ?: return@guard
        val json = synchronized(queue) {
            if (!dirty) return@guard
            dirty = false
            gson.toJson(queue.toList())
        }
        lastSave = SystemClock.elapsedRealtime()
        val tmp = File(f.absolutePath + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun pendingCount(): Int = synchronized(queue) { queue.size }

    private inline fun guard(block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "ignorado: ${e.message}") }
    }
}
