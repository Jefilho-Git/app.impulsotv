package com.impulsolab.impulsotv.adb

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * SelfPermissions — o app libera as próprias permissões especiais pelo adb
 * local (opção C), dispensando o Mac na preparação de cada TV.
 *
 *   SYSTEM_ALERT_WINDOW      "Sobrepor a outros apps": abrir sozinho após o
 *                            boot no Android 10 (a PROSB3000 não tem essa tela)
 *   REQUEST_INSTALL_PACKAGES "Instalar apps desconhecidos": reserva para a
 *                            atualização com confirmação, se o adb local cair
 *
 * São as mesmas permissões que se liberaria com "adb shell appops set …".
 * Chamar fora da thread principal.
 */
object SelfPermissions {

    private const val TAG = "ImpulsoTV.Adb"

    /** @return true se, ao final, o app pode sobrepor outros apps. */
    fun ensure(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val ctx = context.applicationContext
        val pkg = ctx.packageName

        val needOverlay = !Settings.canDrawOverlays(ctx)
        val needInstall = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                          !ctx.packageManager.canRequestPackageInstalls()
        if (!needOverlay && !needInstall) return true

        if (!LocalAdb.isAvailable()) {
            Log.w(TAG, "Permissões especiais pendentes e sem adb local (overlay=$needOverlay, install=$needInstall)")
            return !needOverlay
        }
        try {
            if (needOverlay) LocalAdb.exec("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            if (needInstall) LocalAdb.exec("appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao liberar permissões via adb local: ${e.message}")
        }
        val ok = Settings.canDrawOverlays(ctx)
        Log.i(TAG, "Permissões via adb local — sobreposição: ${if (ok) "liberada" else "NÃO liberada"}" +
              if (needInstall) ", instalar apps: ${if (ctx.packageManager.canRequestPackageInstalls()) "liberada" else "NÃO liberada"}" else "")
        return ok
    }
}
