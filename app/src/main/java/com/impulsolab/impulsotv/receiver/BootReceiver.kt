package com.impulsolab.impulsotv.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.impulsolab.impulsotv.constants.AppConstants
import com.impulsolab.impulsotv.service.PlayerService
import com.impulsolab.impulsotv.ui.MainActivity

/**
 * BootReceiver — inicia o app e o serviço no boot frio e pós queda de energia.
 * Aguarda 5 segundos para tolerar a inicialização pesada do SO em TV Boxes lentas.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val acao = intent.action ?: return
        val validas = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        if (acao !in validas) return

        Log.i(AppConstants.TAG_BOOT, "Boot a frio / Queda de energia detectado: $acao")

        // Atraso de 5 segundos para garantir que o System UI e serviços base estejam totalmente carregados
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // 1. Inicia o Foreground Service 24/7
                val serviceIntent = Intent(context, PlayerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                // 2. Prepara Intent para MainActivity
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                try {
                    context.startActivity(activityIntent)
                } catch (e: Exception) {
                    Log.w(AppConstants.TAG_BOOT, "startActivity direto falhou: ${e.message}")
                }

                // 3. Garantia via AlarmManager RTC_WAKEUP para Android 10
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    1002,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager?.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 200L,
                    pendingIntent
                )

                Log.i(AppConstants.TAG_BOOT, "PlayerService e MainActivity (com delay de 5s) iniciados pós-boot")
            } catch (e: Exception) {
                Log.e(AppConstants.TAG_BOOT, "Erro no BootReceiver: ${e.message}", e)
            }
        }, 5_000L)
    }
}
