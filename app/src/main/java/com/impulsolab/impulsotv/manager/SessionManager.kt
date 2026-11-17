package com.impulsolab.impulsotv.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.impulsolab.impulsotv.constants.AppConstants

/**
 * SessionManager
 * ════════════════════════════════════════════════════════════════
 * Persiste o estado de pairing do dispositivo no SharedPreferences.
 *
 * Após o pairing bem-sucedido, os dados do dispositivo são salvos
 * localmente. No próximo boot, o app lê os dados salvos e vai direto
 * para o player sem precisar parear novamente.
 *
 * Também gera o fingerprint único do hardware Android que identifica
 * este device no campo connection_fingerprint da tabela devices.
 * ════════════════════════════════════════════════════════════════
 */
class SessionManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        AppConstants.PREF_FILE, Context.MODE_PRIVATE
    )

    // ─── Pairing state ────────────────────────────────────────────────────

    data class Session(
        val deviceId: String,
        val deviceCode: String,
        val deviceName: String,
        val playlistId: String,
        val fingerprint: String
    )

    /** Retorna a sessão salva, ou null se não pareado */
    fun getSession(): Session? {
        val deviceId   = prefs.getString(AppConstants.PREF_DEVICE_ID, null) ?: return null
        val deviceCode = prefs.getString(AppConstants.PREF_DEVICE_CODE, null) ?: return null
        val deviceName = prefs.getString(AppConstants.PREF_DEVICE_NAME, null) ?: return null
        val playlistId = prefs.getString(AppConstants.PREF_PLAYLIST_ID, null) ?: return null
        val fingerprint = prefs.getString(AppConstants.PREF_FINGERPRINT, null) ?: getOrCreateFingerprint()
        return Session(deviceId, deviceCode, deviceName, playlistId, fingerprint)
    }

    /** Salva os dados do pairing após sucesso */
    fun saveSession(
        deviceId: String,
        deviceCode: String,
        deviceName: String,
        playlistId: String
    ) {
        val fingerprint = getOrCreateFingerprint()
        prefs.edit()
            .putString(AppConstants.PREF_DEVICE_ID,   deviceId)
            .putString(AppConstants.PREF_DEVICE_CODE,  deviceCode)
            .putString(AppConstants.PREF_DEVICE_NAME,  deviceName)
            .putString(AppConstants.PREF_PLAYLIST_ID,  playlistId)
            .putString(AppConstants.PREF_FINGERPRINT,  fingerprint)
            .apply()
        Log.i("SessionManager", "Sessão salva: $deviceName ($deviceId)")
    }

    /** Atualiza a playlist vinculada (trocada no dashboard sem novo pareamento) */
    fun updatePlaylistId(playlistId: String) {
        prefs.edit().putString(AppConstants.PREF_PLAYLIST_ID, playlistId).apply()
        Log.i("SessionManager", "Playlist atualizada: $playlistId")
    }

    /** Limpa a sessão — vai para tela de pairing no próximo boot */
    fun clearSession() {
        prefs.edit().clear().apply()
        Log.i("SessionManager", "Sessão limpa")
    }

    // ─── Restart tracking ─────────────────────────────────────────────────

    /**
     * Salva o timestamp do último restart_requested_at processado.
     * Evita processar o mesmo comando de restart mais de uma vez.
     */
    fun saveLastRestartAt(timestamp: String) {
        prefs.edit().putString(AppConstants.PREF_LAST_RESTART, timestamp).apply()
    }

    fun getLastRestartAt(): String? =
        prefs.getString(AppConstants.PREF_LAST_RESTART, null)

    // ─── Device fingerprint ───────────────────────────────────────────────

    /**
     * Retorna o fingerprint único deste app.
     * Se não existir, gera um novo UUID e salva permanentemente.
     */
    fun getOrCreateFingerprint(): String {
        var fingerprint = prefs.getString(AppConstants.PREF_FINGERPRINT, null)
        if (fingerprint == null) {
            fingerprint = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(AppConstants.PREF_FINGERPRINT, fingerprint).apply()
            Log.d("ImpulsoTV", "Novo fingerprint criado: $fingerprint")
        }
        return fingerprint
    }


}
