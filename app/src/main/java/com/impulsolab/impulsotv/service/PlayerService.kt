package com.impulsolab.impulsotv.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.impulsolab.impulsotv.R
import com.impulsolab.impulsotv.constants.AppConstants
import com.impulsolab.impulsotv.ui.MainActivity

/**
 * PlayerService — ForegroundService 24/7
 * ════════════════════════════════════════════════════════════════
 * Mantém o processo do app vivo mesmo sob pressão de memória do
 * TV Box. Adquire WakeLock parcial para manter CPU ativa.
 * Monitora a saúde da MainActivity via ping/pong e a reinicia
 * se detectar que morreu (OOM kill).
 * Escuta eventos dinâmicos de tela/energia (ACTION_SCREEN_ON, etc.)
 * para reabrir a MainActivity imediatamente ao ligar a TV Box.
 * ════════════════════════════════════════════════════════════════
 */
class PlayerService : Service() {

    companion object {
        /**
         * [ACTIVITY-MONITOR] Até este instante (epoch ms) o serviço NÃO traz a
         * MainActivity para a frente. Usado enquanto uma tela do sistema
         * (ex.: permissão de sobreposição) está aberta de propósito.
         */
        @Volatile var suppressBringToFrontUntil: Long = 0L

        /** Extra: atraso (ms) antes de trazer a MainActivity para a frente (boot). */
        const val EXTRA_BRING_TO_FRONT_DELAY_MS = "bring_to_front_delay_ms"

        fun isBringToFrontSuppressed(): Boolean =
            System.currentTimeMillis() < suppressBringToFrontUntil
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@PlayerService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wakeLock: PowerManager.WakeLock
    private var lastPingTime = System.currentTimeMillis()
    private var isMonitoring = false

    // Receiver dinâmico para detectar ligamento da tela / botão power do TV Box
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.i(AppConstants.TAG_SERVICE, "[SCREEN-EVENT] Evento de tela/energia detectado: $action")
            if (action == Intent.ACTION_SCREEN_ON ||
                action == Intent.ACTION_USER_PRESENT ||
                action == Intent.ACTION_POWER_CONNECTED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
                action == "com.mstar.android.intent.action.POWER_ON"
            ) {
                bringMainActivityToFront()
            }
        }
    }

    // ─── Activity health monitor ──────────────────────────────────────────

    private val healthRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return
            val elapsed = System.currentTimeMillis() - lastPingTime
            if (elapsed > AppConstants.ACTIVITY_DEAD_THRESHOLD_MS) {
                Log.w(AppConstants.TAG_SERVICE, "[ACTIVITY-MONITOR] Activity inativa ou morta — reiniciando")
                restartMainActivity()
            }
            handler.postDelayed(this, AppConstants.ACTIVITY_PING_INTERVAL_MS)
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        registerScreenReceiver()
        Log.i(AppConstants.TAG_SERVICE, "PlayerService criado — WakeLock e ScreenReceiver ativos")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AppConstants.NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(AppConstants.NOTIFICATION_ID, buildNotification())
        }
        if (!isMonitoring) {
            isMonitoring = true
            lastPingTime = System.currentTimeMillis()
            handler.postDelayed(healthRunnable, AppConstants.ACTIVITY_DEAD_THRESHOLD_MS)
        }
        // Traz a MainActivity para a frente sempre que o serviço for iniciado ou receba comando.
        // No boot/atualização, espera o atraso pedido pelo BootReceiver — com o
        // serviço em foreground o processo continua vivo durante a espera.
        val delayMs = intent?.getLongExtra(EXTRA_BRING_TO_FRONT_DELAY_MS, 0L) ?: 0L
        if (delayMs > 0) {
            lastPingTime = System.currentTimeMillis() + delayMs   // watchdog não age antes
            handler.postDelayed({ bringMainActivityToFront() }, delayMs)
        } else {
            bringMainActivityToFront()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        isMonitoring = false
        handler.removeCallbacksAndMessages(null)
        unregisterScreenReceiver()
        releaseWakeLock()
        super.onDestroy()
        Log.w(AppConstants.TAG_SERVICE, "PlayerService destruído")
    }

    // ─── Screen / Power Receiver Registration ─────────────────────────────

    private fun registerScreenReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction("android.intent.action.QUICKBOOT_POWERON")
                addAction("com.htc.intent.action.QUICKBOOT_POWERON")
                addAction("com.mstar.android.intent.action.POWER_ON")
            }
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(AppConstants.TAG_SERVICE, "Erro ao registrar screenReceiver: ${e.message}")
        }
    }

    private fun unregisterScreenReceiver() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(AppConstants.TAG_SERVICE, "Erro ao desregistrar screenReceiver: ${e.message}")
        }
    }

    // ─── Bring Activity To Front ──────────────────────────────────────────

    fun bringMainActivityToFront() {
        handler.post {
            if (isBringToFrontSuppressed()) {
                Log.i(AppConstants.TAG_SERVICE, "[ACTIVITY-MONITOR] Trazer para a frente suspenso (tela do sistema aberta)")
                lastPingTime = System.currentTimeMillis()
                return@post
            }
            // Atualiza já: se algo abaixo falhar, não entra em loop a cada 15s
            lastPingTime = System.currentTimeMillis()
            try {
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }

                try {
                    startActivity(activityIntent)
                } catch (e: Exception) {
                    Log.w(AppConstants.TAG_SERVICE, "[SCREEN-EVENT] startActivity direto falhou: ${e.message}")
                }

                // Garantia Android 10: agenda disparo via AlarmManager
                val alarmManager = getSystemService(ALARM_SERVICE) as? AlarmManager
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    1003,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                if (alarmManager != null) {
                    // Android 12+: alarme exato exige SCHEDULE_EXACT_ALARM, que o app
                    // não tem. Nesse caso usa o alarme inexato (sem SecurityException).
                    val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        alarmManager.canScheduleExactAlarms()
                    if (!canExact) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + 100L,
                            pendingIntent
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + 100L,
                            pendingIntent
                        )
                    } else {
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + 100L,
                            pendingIntent
                        )
                    }
                }

                Log.i(AppConstants.TAG_SERVICE, "[SCREEN-EVENT] MainActivity solicitada para a frente")
            } catch (e: Exception) {
                Log.e(AppConstants.TAG_SERVICE, "[SCREEN-EVENT] Erro ao trazer MainActivity para a frente: ${e.message}")
            }
        }
    }

    // ─── Ping API (chamada pela MainActivity) ─────────────────────────────

    fun pingFromActivity() {
        Log.v(AppConstants.TAG_SERVICE, "[ACTIVITY-MONITOR] Ping recebido")
        lastPingTime = System.currentTimeMillis()
    }

    // ─── Restart ─────────────────────────────────────────────────────────

    private fun restartMainActivity() {
        handler.postDelayed({
            bringMainActivityToFront()
        }, 1_000L)
    }

    // ─── WakeLock ─────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ImpulsoTV:ServiceWakeLock"
        ).also { it.acquire() }
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
    }

    // ─── Notificação ─────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ImpulsoTV")
            .setContentText("Player ativo em execução")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}
