package com.impulsolab.impulsotv.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.impulsolab.impulsotv.update.UpdateManager

/**
 * Modo administrador (Device Owner)
 * ════════════════════════════════════════════════════════════════
 * Ativado UMA vez por TV Box, via adb, sem contas no aparelho:
 *   adb shell dpm set-device-owner com.impulsolab.impulsotv/.admin.ImpulsoDeviceAdminReceiver
 *
 * Com ele o app:
 *   - instala as próprias atualizações em silêncio (UpdateManager)
 *   - vira a tela inicial (HOME) sem o Android perguntar qual launcher
 *   - bloqueia o hotspot (o Ajustes da TV o ligava a cada boot, derrubando o Wi‑Fi)
 *   - religa o Wi‑Fi se alguém/algo desligar
 *   - mantém a tela ligada enquanto estiver na tomada
 *
 * Sem o modo administrador, tudo isso é ignorado e o app funciona como antes.
 * Para desfazer (ex.: devolver a TV Box): ver AdminCommandReceiver.
 * ════════════════════════════════════════════════════════════════
 */
object DeviceOwner {

    private const val TAG = "ImpulsoTV.Admin"

    /** Ponte de HOME desativada no manifest; só é ligada no modo administrador. */
    private const val HOME_ALIAS = "com.impulsolab.impulsotv.ui.HomeActivity"

    private fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(ctx: Context) = ComponentName(ctx, ImpulsoDeviceAdminReceiver::class.java)

    fun isActive(ctx: Context): Boolean =
        try { dpm(ctx).isDeviceOwnerApp(ctx.packageName) } catch (_: Exception) { false }

    /** Aplica as políticas do modo administrador. Idempotente — chamado a cada início. */
    fun apply(ctx: Context) {
        if (!isActive(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        try {
            // 1. Tela inicial = ImpulsoTV, sem perguntar qual launcher
            val alias = ComponentName(ctx.packageName, HOME_ALIAS)
            ctx.packageManager.setComponentEnabledSetting(
                alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
            dpm.addPersistentPreferredActivity(admin, home, alias)

            // 2. Bloqueia hotspot/tethering (impede o Ajustes da TV de ligá-lo no boot)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)

            // 3. Tela sempre ligada na tomada (AC | USB | wireless)
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7")

            Log.i(TAG, "Modo administrador ativo — políticas aplicadas")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao aplicar políticas: ${e.message}")
        }
        ensureWifiOn(ctx)
    }

    /**
     * Religa o Wi‑Fi se estiver desligado e não houver cabo de rede.
     * No Android 10+ só Device Owner/sistema podem fazer isso.
     */
    @Suppress("DEPRECATION")
    fun ensureWifiOn(ctx: Context) {
        if (!isActive(ctx)) return
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifi.isWifiEnabled || hasEthernet(ctx)) return
            val ok = wifi.setWifiEnabled(true)
            Log.w(TAG, "Wi‑Fi estava desligado — religando: ${if (ok) "ok" else "recusado"}")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao religar Wi‑Fi: ${e.message}")
        }
    }

    private fun hasEthernet(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
    }

    /** Desfaz tudo e deixa de ser administrador (a TV volta ao normal). */
    fun release(ctx: Context) {
        if (!isActive(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        try {
            dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx.packageName, HOME_ALIAS),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(ctx.packageName)
            Log.w(TAG, "Modo administrador removido")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao remover modo administrador: ${e.message}")
        }
    }
}

/** Receiver exigido pelo Android para o modo administrador. */
class ImpulsoDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("ImpulsoTV.Admin", "Administrador habilitado")
        DeviceOwner.apply(context)
    }
}

/**
 * Comandos de manutenção via adb. Protegido pela permissão DUMP, que só o
 * shell do adb e o sistema têm — nenhum outro app consegue enviar.
 *
 *   PKG=com.impulsolab.impulsotv
 *   # Procurar atualização agora (ignora o horário permitido):
 *   adb shell am broadcast -a com.impulsolab.impulsotv.CHECK_UPDATE -n $PKG/com.impulsolab.impulsotv.admin.AdminCommandReceiver
 *   # Remover o modo administrador:
 *   adb shell am broadcast -a com.impulsolab.impulsotv.RELEASE_ADMIN -n $PKG/com.impulsolab.impulsotv.admin.AdminCommandReceiver
 */
class AdminCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK_UPDATE -> {
                Log.i("ImpulsoTV.Admin", "Comando: procurar atualização agora")
                UpdateManager.get(context).checkNow(
                    baseUrlOverride = intent.getStringExtra(EXTRA_BASE_URL)
                )
            }
            ACTION_RELEASE_ADMIN -> DeviceOwner.release(context)
        }
    }

    companion object {
        const val ACTION_CHECK_UPDATE = "com.impulsolab.impulsotv.CHECK_UPDATE"
        const val ACTION_RELEASE_ADMIN = "com.impulsolab.impulsotv.RELEASE_ADMIN"
        /** Só em builds de debug: testar com servidor local. */
        const val EXTRA_BASE_URL = "base_url"
    }
}
