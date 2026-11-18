package com.impulsolab.impulsotv.manager

import android.content.Context
import android.util.Log
import com.impulsolab.impulsotv.constants.AppConstants
import com.impulsolab.impulsotv.data.model.PlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * MediaCacheManager
 * ════════════════════════════════════════════════════════════════
 * Gerencia o download e cache local dos arquivos de mídia.
 *
 * Por que cachear localmente?
 *   - Reprodução contínua mesmo se o WiFi cair momentaneamente
 *   - Zero latência ao iniciar cada item (arquivo já está no disco)
 *   - Reduz consumo de banda em dispositivos com plano de dados
 *
 * Estratégia:
 *   1. Cada URL é mapeada para um arquivo local via MD5(url)
 *   2. Se arquivo local existe e tamanho bate → usa o cache
 *   3. Se não → baixa em background e salva
 *   4. Player sempre usa URL local quando disponível
 *
 * Diretório: context.filesDir/media/
 * ════════════════════════════════════════════════════════════════
 */
class MediaCacheManager(context: Context) {

    private val cacheDir = File(context.filesDir, "media").also { it.mkdirs() }

    /** Serializa pré-caches concorrentes (sync inicial x atualização em segundo plano)
     *  para que dois downloads nunca escrevam no mesmo arquivo .tmp. */
    private val preCacheMutex = Mutex()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // arquivos grandes (vídeos até 4MB)
        .build()

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Retorna o caminho local do arquivo se estiver em cache e completo.
     * Agora usa normalização de URL para evitar duplicidade por parâmetros.
     */
    fun getPlayableUri(item: PlaylistItem): String {
        val url = resolveUrl(item.mediaFile.fileUrl)
        val normalizedUrl = url.substringBefore('?') // Normaliza: remove ?v=1 etc
        val localFile = localFileFor(normalizedUrl)
        val expectedSize = item.mediaFile.fileSize

        val isComplete = if (expectedSize > 0) {
            localFile.exists() && localFile.length() == expectedSize
        } else {
            localFile.exists() && localFile.length() > 0
        }

        return if (isComplete) {
            // Atualiza o tempo de acesso para o LRU Cache (30 dias)
            localFile.setLastModified(System.currentTimeMillis())
            localFile.absolutePath.also {
                Log.v(AppConstants.TAG_CACHE, "[CACHE-HIT] ${item.mediaFile.originalName}")
            }
        } else {
            url.also {
                Log.d(AppConstants.TAG_CACHE, "[CACHE-MISS] Usando remota: ${item.mediaFile.originalName}")
            }
        }
    }

    /**
     * Pré-baixa todos os itens de forma inteligente.
     * Se o arquivo já existe no tamanho certo, NÃO faz requisição de download.
     */
    suspend fun preCachePlaylist(
        items: List<PlaylistItem>,
        onProgress: (Int) -> Unit = {}
    ) = preCacheMutex.withLock { withContext(Dispatchers.IO) {
        Log.i(AppConstants.TAG_CACHE, "Iniciando verificação de cache para ${items.size} itens")
        var processed = 0
        
        if (items.isEmpty()) {
            onProgress(100)
            return@withContext
        }

        // Arquivos da playlist recebida nunca são apagados pela limpeza
        val keep = items.map { localFileFor(resolveUrl(it.mediaFile.fileUrl).substringBefore('?')).name }.toSet()
        cleanupCache(keep)   // libera espaço ANTES de baixar

        for (item in items) {
            val url = resolveUrl(item.mediaFile.fileUrl)
            val normalizedUrl = url.substringBefore('?')
            val localFile = localFileFor(normalizedUrl)
            val expectedSize = item.mediaFile.fileSize

            val alreadyExists = localFile.exists() && (expectedSize <= 0 || localFile.length() == expectedSize)

            if (!alreadyExists) {
                if (!ensureFreeSpace(expectedSize, keep)) {
                    Log.w(AppConstants.TAG_CACHE, "[DISK-FULL] Sem espaço para ${item.mediaFile.originalName} (${expectedSize / 1024 / 1024} MB); será tocado via rede")
                } else {
                    Log.i(AppConstants.TAG_CACHE, "[DOWNLOAD-START] Motivo: Cache inválido/ausente | Arquivo: ${item.mediaFile.originalName}")
                    downloadFile(url, localFile)
                }
            } else {
                Log.v(AppConstants.TAG_CACHE, "[DOWNLOAD-SKIP] Já em cache: ${item.mediaFile.originalName}")
            }
            
            processed++
            onProgress((processed * 100) / items.size)
        }

    } }

    /**
     * Política de limpeza (4.3):
     *   - arquivos FORA da playlist atual sem uso há CACHE_UNUSED_RETENTION_MS → apagados
     *   - .tmp de downloads interrompidos há mais de 1 h → apagados
     *   - arquivos da playlist atual (keep) → nunca apagados
     * O "uso" é o lastModified, renovado a cada reprodução em getPlayableUri().
     */
    private fun cleanupCache(keep: Set<String>) {
        val now = System.currentTimeMillis()
        var deletedCount = 0
        var deletedBytes = 0L

        cacheDir.listFiles()?.forEach { file ->
            if (!file.isFile || file.name in keep) return@forEach
            val age = now - file.lastModified()
            val expired = if (file.name.endsWith(".tmp")) age > 60 * 60 * 1000L
                          else age > AppConstants.CACHE_UNUSED_RETENTION_MS
            if (expired) {
                deletedBytes += file.length()
                if (file.delete()) deletedCount++
            }
        }

        if (deletedCount > 0) {
            Log.i(AppConstants.TAG_CACHE, "[CLEANUP] Removidos $deletedCount arquivos fora da playlist (${deletedBytes / 1024 / 1024} MB)")
        }
    }

    /**
     * Garante espaço para baixar `bytesNeeded` mantendo CACHE_MIN_FREE_BYTES livres.
     * Se faltar, apaga arquivos fora da playlist (os menos usados primeiro).
     * @return false se mesmo assim não couber — o item toca via rede.
     */
    private fun ensureFreeSpace(bytesNeeded: Long, keep: Set<String>): Boolean {
        val needed = bytesNeeded.coerceAtLeast(0L) + AppConstants.CACHE_MIN_FREE_BYTES
        if (cacheDir.usableSpace >= needed) return true

        val candidates = cacheDir.listFiles()
            ?.filter { it.isFile && it.name !in keep }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        for (file in candidates) {
            val size = file.length()
            if (file.delete()) {
                Log.i(AppConstants.TAG_CACHE, "[DISK-FULL] Liberado ${size / 1024 / 1024} MB: ${file.name}")
            }
            if (cacheDir.usableSpace >= needed) return true
        }
        return cacheDir.usableSpace >= needed
    }

    /** Limpa todo o cache local (apenas em caso de desvinculação total) */
    fun clearAll() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        Log.i(AppConstants.TAG_CACHE, "[CLEANUP-TOTAL] Cache completamente limpo")
    }

    // ─── Private ─────────────────────────────────────────────────────────

    private fun downloadFile(url: String, dest: File): Boolean {
        val tempFile = File(dest.absolutePath + ".tmp")
        var recorded = false   // um evento de coleta por download, mesmo se falhar no meio
        return try {
            val finalUrl = resolveUrl(url)
            val request = Request.Builder().url(finalUrl)
                .addHeader("apikey", AppConstants.SUPABASE_ANON_KEY)
                .build()

            http.newCall(request).execute().use { response ->
                MediaTelemetry.recordDownload(response.body?.contentLength() ?: -1L, response.code)
                recorded = true
                if (!response.isSuccessful) {
                    Log.e(AppConstants.TAG_CACHE, "Download falhou ${response.code}: $finalUrl")
                    return false
                }
                
                val body = response.body ?: return false
                
                // Download usando streams para maior segurança e menor consumo de RAM
                body.source().use { source ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (source.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                    }
                }

                // Troca o arquivo temporário pelo definitivo (atômico)
                if (tempFile.exists()) {
                    dest.delete()
                    if (tempFile.renameTo(dest)) {
                        Log.d(AppConstants.TAG_CACHE, "Baixado com sucesso: ${dest.name}")
                        true
                    } else {
                        Log.e(AppConstants.TAG_CACHE, "Erro ao renomear arquivo temporário: ${dest.name}")
                        false
                    }
                } else false
            }
        } catch (e: Exception) {
            Log.e(AppConstants.TAG_CACHE, "Erro ao baixar $url: ${e.message}")
            if (!recorded) MediaTelemetry.recordDownload(-1L, null)   // sem resposta do servidor
            tempFile.delete()
            false
        } finally {
            tempFile.delete()
        }
    }

    /** Resolve URL relativa para absoluta se necessário */
    private fun resolveUrl(url: String): String {
        if (url.startsWith("http")) return url
        // Se a URL no banco for apenas o nome do arquivo, monta a URL pública do bucket
        return "${AppConstants.STORAGE_URL}/object/public/${AppConstants.STORAGE_BUCKET}/${url.trim('/')}"
    }

    /** Mapeia uma URL para um arquivo local via MD5 do caminho */
    private fun localFileFor(url: String): File {
        val hash = md5(url)
        val ext  = url.substringAfterLast('.').substringBefore('?').lowercase()
        return File(cacheDir, "$hash.$ext")
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
