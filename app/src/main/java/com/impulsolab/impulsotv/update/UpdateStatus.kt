package com.impulsolab.impulsotv.update

import com.impulsolab.impulsotv.manager.ServerClock

/**
 * UpdateStatus
 * ════════════════════════════════════════════════════════════════
 * Andamento da atualização em curso, enviado na ficha técnica do heartbeat
 * (campo player_status.update) para o painel mostrar "baixando 60%",
 * "aguardando confirmação na tela" ou o motivo da falha.
 *
 * Só memória: nada aqui lança exceção nem faz I/O. Sem atualização em
 * andamento (ou já na versão mais nova) não há o que informar.
 *
 * state: downloading | verifying | installing | waiting_confirmation | failed
 * error: no_space | download_http | download_failed | checksum | invalid_apk |
 *        install_failed | error
 * ════════════════════════════════════════════════════════════════
 */
object UpdateStatus {

    private data class Snapshot(
        val state: String,
        val target: String?,
        val progress: Int?,
        val error: String?,
        val at: Long,
    )

    @Volatile private var current: Snapshot? = null

    fun set(state: String, target: String? = current?.target, progress: Int? = null, error: String? = null) {
        current = Snapshot(state, target, progress?.coerceIn(0, 100), error, safeNow())
    }

    /** Atualiza só a porcentagem do download (sem trocar o resto). */
    fun progress(percent: Int) {
        val c = current ?: return
        if (c.state == "downloading" && c.progress != percent) {
            current = c.copy(progress = percent.coerceIn(0, 100), at = safeNow())
        }
    }

    fun fail(error: String) = set("failed", error = error)

    fun clear() {
        current = null
    }

    fun toPayload(): Map<String, Any?>? {
        val c = current ?: return null
        return mapOf(
            "state" to c.state,
            "target_version" to c.target,
            "progress" to c.progress,
            "error" to c.error,
            "at" to c.at,
        )
    }

    private fun safeNow(): Long = try { ServerClock.nowMillis() } catch (_: Exception) { System.currentTimeMillis() }
}
