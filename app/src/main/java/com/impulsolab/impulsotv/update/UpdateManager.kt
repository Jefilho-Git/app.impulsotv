package com.impulsolab.impulsotv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.IntentCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.impulsolab.impulsotv.BuildConfig
import com.impulsolab.impulsotv.adb.LocalAdb
import com.impulsolab.impulsotv.admin.DeviceOwner
import com.impulsolab.impulsotv.constants.AppConstants
import com.impulsolab.impulsotv.service.PlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Atualização automática (Fase 6)
 * ════════════════════════════════════════════════════════════════
 * Na Hostinger, ao lado do APK, fica um versao.json:
 *   { "versionCode": 5, "versionName": "3.2.0",
 *     "url": "impulsotv-3.2.0.apk", "sha256": "…" }
 * (gerado por scripts/publicar.sh)
 *
 * O app confere a cada UPDATE_CHECK_INTERVAL_MS e instala SÓ dentro da
 * janela permitida: até UPDATE_BOOT_WINDOW_MS após ligar a TV, ou de
 * madrugada (UPDATE_NIGHT_START_HOUR..UPDATE_NIGHT_END_HOUR).
 *
 * Segurança antes de instalar:
 *   - SHA-256 do arquivo tem que bater com o versao.json
 *   - o APK precisa ser do MESMO pacote e de versão MAIOR
 *   - o Android ainda exige a MESMA assinatura (chave de release)
 *
 * Instalação (o primeiro caminho disponível):
 *   1. modo administrador → silenciosa (PackageInstaller)
 *   2. adb local (LocalAdb, opção C) → silenciosa ("cmd package install")
 *   3. tela "Instalar?" do Android (alguém aperta OK)
 * Após instalar, o BootReceiver (MY_PACKAGE_REPLACED) reabre o app.
 * ════════════════════════════════════════════════════════════════
 */
class UpdateManager private constructor(context: Context) {

    private val ctx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loopJob: Job? = null
    private val gson = Gson()
    private val updateDir = File(ctx.filesDir, "update").also { it.mkdirs() }

    private val http = OkHttpClient.Builder()
        .addInterceptor(com.impulsolab.impulsotv.manager.ServerClock.interceptor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        @SerializedName("versionCode") val versionCode: Int,
        @SerializedName("versionName") val versionName: String?,
        @SerializedName("url") val url: String?,
        @SerializedName("sha256") val sha256: String?
    )

    /** Liga o ciclo de verificação (chamado pelo PlayerService). Idempotente. */
    fun start() {
        if (loopJob?.isActive == true) return
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Build de debug: verificação automática desligada (use o comando CHECK_UPDATE)")
            return
        }
        loopJob = scope.launch {
            delay(AppConstants.UPDATE_FIRST_CHECK_DELAY_MS)
            while (isActive) {
                runCheck(force = false, baseUrl = AppConstants.UPDATE_BASE_URL)
                delay(AppConstants.UPDATE_CHECK_INTERVAL_MS)
            }
        }
        Log.i(TAG, "Atualização automática ativa — ${AppConstants.UPDATE_BASE_URL}")
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** Verifica e instala agora, ignorando a janela de horário (comando de manutenção). */
    fun checkNow(baseUrlOverride: String? = null) {
        val base = if (BuildConfig.DEBUG && !baseUrlOverride.isNullOrBlank()) baseUrlOverride
                   else AppConstants.UPDATE_BASE_URL
        scope.launch { runCheck(force = true, baseUrl = base) }
    }

    // ─── Ciclo ────────────────────────────────────────────────────────────

    private suspend fun runCheck(force: Boolean, baseUrl: String) = mutex.withLock {
        try {
            if (!force && !installWindowOpen()) {
                Log.v(TAG, "Fora da janela de instalação — próxima verificação depois")
                return@withLock
            }
            val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val info = fetchInfo(base) ?: return@withLock
            val current = BuildConfig.VERSION_CODE
            if (info.versionCode <= current) {
                Log.i(TAG, "Já na versão mais recente ($current; servidor ${info.versionCode})")
                cleanupOld(keepVersion = -1)
                return@withLock
            }
            Log.i(TAG, "Nova versão disponível: ${info.versionName} (${info.versionCode}) — atual $current")

            val apk = download(base, info) ?: return@withLock
            if (!verify(apk, info)) {
                apk.delete()
                return@withLock
            }
            cleanupOld(keepVersion = info.versionCode)
            install(apk)
        } catch (e: Exception) {
            Log.e(TAG, "Falha na verificação de atualização: ${e.message}")
        }
    }

    /** Até X min após ligar a TV, ou de madrugada (pela hora do servidor). */
    private fun installWindowOpen(): Boolean {
        if (SystemClock.elapsedRealtime() < AppConstants.UPDATE_BOOT_WINDOW_MS) return true
        val hour = com.impulsolab.impulsotv.manager.ServerClock.localHour()
        return hour >= AppConstants.UPDATE_NIGHT_START_HOUR && hour < AppConstants.UPDATE_NIGHT_END_HOUR
    }

    private fun fetchInfo(base: String): UpdateInfo? {
        val url = "${base}versao.json?t=${System.currentTimeMillis()}"   // evita cache da CDN
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "versao.json: HTTP ${resp.code}")
                return null
            }
            val body = resp.body?.string().orEmpty()
            return try {
                gson.fromJson(body, UpdateInfo::class.java)?.takeIf { it.versionCode > 0 }
                    .also { if (it == null) Log.w(TAG, "versao.json inválido") }
            } catch (e: Exception) {
                Log.w(TAG, "versao.json ilegível: ${e.message}")
                null
            }
        }
    }

    /** Baixa (ou reaproveita) o APK da versão. */
    private fun download(base: String, info: UpdateInfo): File? {
        val dest = File(updateDir, "impulsotv-${info.versionCode}.apk")
        if (dest.exists() && info.sha256 != null && sha256(dest).equals(info.sha256, ignoreCase = true)) {
            Log.i(TAG, "APK já baixado anteriormente: ${dest.name}")
            return dest
        }
        val rawUrl = info.url?.takeIf { it.isNotBlank() } ?: "impulsotv.apk"
        val url = if (rawUrl.startsWith("http")) rawUrl else base + rawUrl.trimStart('/')

        val tmp = File(dest.absolutePath + ".tmp")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Download do APK: HTTP ${resp.code} ($url)")
                    return null
                }
                val body = resp.body ?: return null
                val needed = body.contentLength().coerceAtLeast(0) + AppConstants.CACHE_MIN_FREE_BYTES
                if (updateDir.usableSpace < needed) {
                    Log.w(TAG, "Sem espaço para baixar a atualização")
                    return null
                }
                body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            dest.delete()
            if (!tmp.renameTo(dest)) return null
            Log.i(TAG, "APK baixado: ${dest.name} (${dest.length() / 1024} KB)")
            return dest
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao baixar APK: ${e.message}")
            return null
        } finally {
            tmp.delete()
        }
    }

    private fun verify(apk: File, info: UpdateInfo): Boolean {
        val expected = info.sha256
        if (expected.isNullOrBlank()) {
            Log.e(TAG, "versao.json sem sha256 — instalação recusada")
            return false
        }
        val actual = sha256(apk)
        if (!actual.equals(expected, ignoreCase = true)) {
            Log.e(TAG, "SHA-256 não confere (esperado $expected, obtido $actual) — arquivo descartado")
            return false
        }
        @Suppress("DEPRECATION")
        val pkgInfo = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        if (pkgInfo == null) {
            Log.e(TAG, "Arquivo não é um APK válido")
            return false
        }
        if (pkgInfo.packageName != ctx.packageName) {
            Log.e(TAG, "APK é de outro pacote (${pkgInfo.packageName}); este app é ${ctx.packageName}")
            return false
        }
        @Suppress("DEPRECATION")
        val apkVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode
                         else pkgInfo.versionCode.toLong()
        if (apkVersion <= BuildConfig.VERSION_CODE) {
            Log.e(TAG, "APK não é mais novo ($apkVersion <= ${BuildConfig.VERSION_CODE})")
            return false
        }
        Log.i(TAG, "APK verificado (SHA-256, pacote e versão $apkVersion)")
        return true
    }

    private fun install(apk: File) {
        val silent = DeviceOwner.isActive(ctx)

        // Opção C: sem modo administrador, instala pelo adb da própria TV
        if (!silent && LocalAdb.isAvailable()) {
            Log.i(TAG, "Instalando ${apk.name} (silenciosa via adb local)")
            try {
                if (LocalAdb.install(apk)) {
                    Log.i(TAG, "Atualização instalada via adb local")
                    return
                }
                Log.w(TAG, "adb local não conseguiu instalar — usando a confirmação do Android")
            } catch (e: Exception) {
                // Em caso de sucesso o sistema pode encerrar o processo no meio da leitura
                Log.w(TAG, "adb local interrompido (${e.message}) — usando a confirmação do Android")
            }
        }
        if (!silent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()) {
            Log.w(TAG, "Sem permissão 'Instalar apps desconhecidos' — o Android vai pedir para liberar")
        }
        Log.i(TAG, "Instalando ${apk.name} (${if (silent) "silenciosa" else "com confirmação"})")

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                ctx, sessionId,
                Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT),
                flags
            )
            session.commit(pending.intentSender)
        }
    }

    private fun cleanupOld(keepVersion: Int) {
        updateDir.listFiles()?.forEach { f ->
            if (f.name != "impulsotv-$keepVersion.apk") f.delete()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TAG = "ImpulsoTV.Update"
        const val ACTION_INSTALL_RESULT = "com.impulsolab.impulsotv.INSTALL_RESULT"

        @Volatile private var instance: UpdateManager? = null
        fun get(context: Context): UpdateManager =
            instance ?: synchronized(this) {
                instance ?: UpdateManager(context).also { instance = it }
            }
    }
}

/** Resultado da instalação enviado pelo PackageInstaller. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Sem modo administrador: o Android pede confirmação na tela
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    Log.i(UpdateManager.TAG, "Aguardando confirmação 'Instalar' na tela")
                    // O watchdog não pode jogar o app por cima da tela de confirmação
                    PlayerService.suppressBringToFrontUntil =
                        System.currentTimeMillis() + AppConstants.SYSTEM_SCREEN_GRACE_MS
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(confirm) } catch (e: Exception) {
                        Log.e(UpdateManager.TAG, "Não foi possível abrir a confirmação: ${e.message}")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(UpdateManager.TAG, "Atualização instalada")
            else ->
                Log.e(UpdateManager.TAG, "Instalação falhou (status=$status): $message")
        }
    }
}
