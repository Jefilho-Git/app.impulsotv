package com.impulsolab.impulsotv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.impulsolab.impulsotv.constants.AppConstants

class ImpulsoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(AppConstants.TAG_APP, "ImpulsoTV v${BuildConfig.VERSION_NAME} iniciando")
        createNotificationChannel()
    }

    /**
     * Canal de notificação obrigatório para ForegroundService no Android 8+.
     * IMPORTANCE_HIGH permite FullScreenIntent (abertura automática na tela no Android 10+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConstants.NOTIFICATION_CHANNEL_ID,
                AppConstants.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "ImpulsoTV player em execução contínua"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
