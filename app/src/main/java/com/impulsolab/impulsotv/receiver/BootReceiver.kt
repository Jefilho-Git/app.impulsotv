package com.impulsolab.impulsotv.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.impulsolab.impulsotv.constants.AppConstants
import com.impulsolab.impulsotv.service.PlayerService
import com.impulsolab.impulsotv.ui.MainActivity

/**
 * BootReceiver — inicia o app e o serviço no boot frio e pós queda de energia.
 *
 * Não é directBootAware de propósito: sessão (SharedPreferences), playlist
 * offline (Room) e mídias (filesDir) ficam no armazenamento que só abre após
 * o desbloqueio do usuário. Por isso reagimos a BOOT_COMPLETED, nunca a
 * LOCKED_BOOT_COMPLETED.
 * Aguarda 5 segundos para tolerar a inicialização pesada do SO em TV Boxes lentas.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val acao = intent.action ?: return
        val validas = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        if (acao !in validas) return

        Log.i(AppConstants.TAG_BOOT, "Boot a frio / Queda de energia detectado: $acao")

        // Inicia o Foreground Service JÁ, dentro do onReceive: o Android permite
        // isso para BOOT_COMPLETED / MY_PACKAGE_REPLACED, e o serviço mantém o
        // processo vivo. Antes, um postDelayed de 5s rodava aqui — mas a TV Box
        // (Android 10, pouca RAM) encerrava o processo antes e o app não abria.
        // O atraso de 5s (System UI carregar) agora acontece dentro do serviço.
        try {
            val serviceIntent = Intent(context, PlayerService::class.java).apply {
                putExtra(PlayerService.EXTRA_BRING_TO_FRONT_DELAY_MS, BOOT_ACTIVITY_DELAY_MS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(AppConstants.TAG_BOOT, "PlayerService iniciado; MainActivity abre em ${BOOT_ACTIVITY_DELAY_MS / 1000}s")
        } catch (e: Exception) {
            Log.e(AppConstants.TAG_BOOT, "Erro ao iniciar PlayerService: ${e.message}", e)
            // Último recurso: alarme que abre a MainActivity
            val activityIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 1002, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.set(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + BOOT_ACTIVITY_DELAY_MS, pendingIntent
            )
        }
    }

    private companion object {
        const val BOOT_ACTIVITY_DELAY_MS = 5_000L
    }
}
