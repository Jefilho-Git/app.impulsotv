package com.impulsolab.impulsotv.manager

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * ServerClock — hora confiável sem depender do relógio da TV Box.
 * ════════════════════════════════════════════════════════════════
 * As PROSB3000 não têm bateria de relógio: sem energia ou sem internet por
 * muito tempo o relógio atrasa, e o heartbeat chegava ao painel com horas
 * de atraso. Toda resposta HTTP do Supabase traz a hora do servidor no
 * cabeçalho "Date"; daqui sai a diferença (offset) entre o servidor e a TV.
 *
 *   ServerClock.nowMillis()   → hora atual corrigida (ms desde 1970, UTC)
 *   ServerClock.localHour()   → hora do dia corrigida, no fuso da TV
 *
 * Enquanto nenhuma resposta chegou, usa o relógio da TV.
 * ════════════════════════════════════════════════════════════════
 */
object ServerClock {

    private const val TAG = "ImpulsoTV.Clock"
    /** Diferenças menores que isso são ignoradas (latência/arredondamento do Date) */
    private const val LOG_THRESHOLD_MS = 60_000L

    @Volatile var offsetMs: Long = 0L
        private set
    @Volatile var synced: Boolean = false
        private set

    // ThreadLocal.withInitial exige API 26; o app ainda declara minSdk 21
    private val httpDate = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
    }

    fun nowMillis(): Long = System.currentTimeMillis() + offsetMs

    fun localHour(): Int = Calendar.getInstance().apply { timeInMillis = nowMillis() }
        .get(Calendar.HOUR_OF_DAY)

    /** Atualiza o offset a partir do cabeçalho Date de uma resposta. */
    fun update(dateHeader: String?, receivedAtMs: Long = System.currentTimeMillis()) {
        if (dateHeader.isNullOrBlank()) return
        val serverMs = try { httpDate.get()!!.parse(dateHeader)?.time } catch (_: Exception) { null } ?: return
        val newOffset = serverMs - receivedAtMs
        val changed = !synced || abs(newOffset - offsetMs) > LOG_THRESHOLD_MS
        offsetMs = newOffset
        synced = true
        if (changed && abs(newOffset) > LOG_THRESHOLD_MS) {
            Log.w(TAG, "Relógio da TV difere do servidor em ${newOffset / 60_000} min — usando a hora do servidor")
        }
    }

    /** Interceptor OkHttp: alimenta o offset com toda resposta recebida. */
    val interceptor = Interceptor { chain ->
        val response: Response = chain.proceed(chain.request())
        update(response.header("Date"), response.receivedResponseAtMillis)
        response
    }
}
