package com.impulsolab.impulsotv.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Monitora a conexão de rede.
 *
 * @param onNetworkAvailable chamado (em thread de rede) quando uma rede com
 *        internet fica disponível — usado para reconectar ao servidor na hora,
 *        sem esperar o próximo ciclo do loop de reconexão.
 *
 * Chame [release] no onDestroy para não vazar o callback do sistema.
 */
class NetworkStateManager(
    context: Context,
    private val onNetworkAvailable: () -> Unit = {}
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Online")
            onNetworkAvailable()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Offline")
        }
    }

    private var registered = false

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível monitorar a rede: ${e.message}")
        }
    }

    fun isOnline(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        return connectivityManager.activeNetworkInfo?.isConnected ?: false
    }

    /** Remove o callback do sistema (5.2). */
    fun release() {
        if (!registered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) { /* já removido */ }
        registered = false
    }

    private companion object {
        const val TAG = "ImpulsoTV.Network"
    }
}
