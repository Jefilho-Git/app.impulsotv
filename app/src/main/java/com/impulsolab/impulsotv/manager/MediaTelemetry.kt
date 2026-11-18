package com.impulsolab.impulsotv.manager

import android.os.SystemClock
import com.impulsolab.impulsotv.BuildConfig
import java.util.UUID

/**
 * MediaTelemetry
 * ════════════════════════════════════════════════════════════════
 * Fila em memória dos downloads de mídia feitos pela TV, enviada para a
 * Edge Function media-telemetry junto com o heartbeat (a cada 30 s).
 *
 * Regras de segurança (nunca atrapalhar o player):
 *   - nada aqui lança exceção nem faz I/O; o envio é do SupabaseClient
 *   - fila limitada (MAX_QUEUE): se encher, descarta o evento mais antigo
 *   - coleta desligada no servidor (503) → pausa os envios por 1 hora
 *   - sem token da TV (modo antigo) → nada é enviado
 *
 * O que é registrado: cada download do MediaCacheManager (rede), com o
 * Content-Length declarado e o status HTTP. Cache local não gera tráfego
 * e não é registrado. Nenhum nome de arquivo, URL ou código vai no evento.
 * ════════════════════════════════════════════════════════════════
 */
object MediaTelemetry {

    data class Event(
        val requestId: String,
        val responseBytes: Long?,
        val status: Int?,
    ) {
        fun toPayload(): Map<String, Any?> = mapOf(
            "request_id" to requestId,
            "source" to "network",
            "response_bytes" to responseBytes,
            "byte_basis" to if (responseBytes == null) "unknown" else "content_length",
            "status" to status,
            "sw_version" to "android-${BuildConfig.VERSION_NAME}",
        )
    }

    private const val MAX_QUEUE = 200
    const val MAX_BATCH = 50
    private const val DISABLED_PAUSE_MS = 60 * 60 * 1000L

    private val queue = ArrayDeque<Event>()
    @Volatile private var pausedUntil = 0L

    /** Registra um download. contentLength < 0 (desconhecido) vira null. */
    fun recordDownload(contentLength: Long, status: Int?) {
        val bytes = contentLength.takeIf { it >= 0 }
        val validStatus = status?.takeIf { it in 100..599 }
        synchronized(queue) {
            if (queue.size >= MAX_QUEUE) queue.removeFirst()
            queue.addLast(Event(UUID.randomUUID().toString(), bytes, validStatus))
        }
    }

    /** Próximo lote a enviar, sem tirar da fila (só sai depois de confirmado). */
    fun peekBatch(): List<Event> {
        if (SystemClock.elapsedRealtime() < pausedUntil) return emptyList()
        return synchronized(queue) { queue.take(MAX_BATCH) }
    }

    /**
     * Resultado do envio de um lote (código HTTP, ou -1 sem conexão).
     *  2xx        → confirmado, sai da fila
     *  503        → coleta desligada/indisponível: pausa 1 h e mantém
     *  429, -1, 5xx → mantém para a próxima tentativa (mesmo request_id)
     *  outros 4xx → recusado de vez (credencial ou payload): descarta
     */
    fun onBatchResult(batch: List<Event>, httpCode: Int) {
        val drop = httpCode in 200..299 || (httpCode in 400..499 && httpCode != 429)
        if (httpCode == 503) pausedUntil = SystemClock.elapsedRealtime() + DISABLED_PAUSE_MS
        if (drop) {
            val ids = batch.mapTo(HashSet()) { it.requestId }
            synchronized(queue) { queue.removeAll { it.requestId in ids } }
        }
    }

    fun pendingCount(): Int = synchronized(queue) { queue.size }
}
