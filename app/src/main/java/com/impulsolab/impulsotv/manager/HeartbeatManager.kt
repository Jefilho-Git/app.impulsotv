package com.impulsolab.impulsotv.manager

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.impulsolab.impulsotv.constants.AppConstants
import com.impulsolab.impulsotv.data.remote.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * HeartbeatManager
 * ════════════════════════════════════════════════════════════════
 * Envia um heartbeat ao Supabase a cada HEARTBEAT_INTERVAL_MS,
 * atualizando last_checkin e is_online na tabela devices.
 *
 * Isso alimenta o dashboard com o status online/offline em tempo
 * real de cada TV Box.
 * ════════════════════════════════════════════════════════════════
 */
class HeartbeatManager(
    private val supabase: SupabaseClient,
    private val deviceId: String
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            scope.launch {
                supabase.sendHeartbeat(deviceId)
            }
            handler.postDelayed(this, AppConstants.HEARTBEAT_INTERVAL_MS)
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        handler.post(heartbeatRunnable)   // primeiro heartbeat imediato
        Log.i(AppConstants.TAG_HEARTBEAT, "Heartbeat iniciado — intervalo: ${AppConstants.HEARTBEAT_INTERVAL_MS / 1000}s")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(heartbeatRunnable)
        Log.i(AppConstants.TAG_HEARTBEAT, "Heartbeat parado")
    }
}
