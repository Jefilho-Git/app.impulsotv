package com.impulsolab.impulsotv.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.impulsolab.impulsotv.admin.DeviceOwner
import java.util.concurrent.Executors

/**
 * WifiGuard — mantém o Wi‑Fi ligado.
 *
 * Na PROSB3000 o Ajustes da TV liga o hotspot a cada boot, o que desliga o
 * Wi‑Fi cliente e deixa a TV offline. Se o Wi‑Fi estiver desligado e não
 * houver cabo de rede, religa:
 *   - modo administrador → WifiManager (permitido a Device Owner no Android 10+)
 *   - senão, adb local    → "svc wifi enable" (o mesmo que via adb)
 *
 * Roda fora da thread principal e no máximo a cada MIN_INTERVAL_MS.
 */
object WifiGuard {

    private const val TAG = "ImpulsoTV.Wifi"
    private const val MIN_INTERVAL_MS = 60_000L

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var lastAttempt = 0L

    fun check(context: Context) {
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        if (now - lastAttempt < MIN_INTERVAL_MS) return
        lastAttempt = now
        executor.execute { checkNow(ctx) }
    }

    @Suppress("DEPRECATION")
    private fun checkNow(ctx: Context) {
        try {
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifi.isWifiEnabled || hasEthernet(ctx)) return

            if (DeviceOwner.isActive(ctx)) {
                val ok = wifi.setWifiEnabled(true)
                Log.w(TAG, "Wi‑Fi desligado — religando (administrador): ${if (ok) "ok" else "recusado"}")
                return
            }
            if (!LocalAdb.isAvailable()) {
                Log.w(TAG, "Wi‑Fi desligado e sem adb local — não há como religar sozinho")
                return
            }
            val out = LocalAdb.exec("svc wifi enable").trim()
            Log.w(TAG, "Wi‑Fi desligado — religado via adb local${if (out.isNotEmpty()) ": $out" else ""}")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar/religar Wi‑Fi: ${e.message}")
        }
    }

    private fun hasEthernet(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
    }
}
