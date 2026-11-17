package com.impulsolab.impulsotv.adb

import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LocalAdb — Opção C da Fase 6
 * ════════════════════════════════════════════════════════════════
 * Cliente mínimo do protocolo ADB que conversa com o adbd da PRÓPRIA TV
 * Box (127.0.0.1:5555). Usado onde o firmware não suporta modo
 * administrador (ex.: Proeletronic PROSB3000), mas deixa o "ADB pela rede"
 * ligado SEM autenticação (ro.adb.secure=0).
 *
 * Com ele o app executa, como o usuário "shell" do Android:
 *   - a instalação silenciosa das próprias atualizações (install)
 *   - comandos fixos de manutenção (ex.: "svc wifi enable")
 *
 * Só conecta em 127.0.0.1 — funciona mesmo com a rede das TVs isolada.
 * Se o adbd exigir autorização (AUTH) ou não estiver ligado, falha de
 * forma limpa e o chamador usa outro caminho (confirmação na tela).
 *
 * Protocolo: mensagens com cabeçalho de 24 bytes little-endian
 *   command, arg0, arg1, data_length, data_crc32 (soma), magic (~command)
 * ════════════════════════════════════════════════════════════════
 */
object LocalAdb {

    private const val TAG = "ImpulsoTV.Adb"
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 120_000

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257

    private const val A_VERSION = 0x01000001          // pula checksum (Android 9+)
    private const val MAX_PAYLOAD = 256 * 1024

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /** O adbd local aceita conexão sem autorização? (rápido; sem efeitos) */
    fun isAvailable(): Boolean = try {
        Connection.open().use { true }
    } catch (e: Exception) {
        Log.d(TAG, "adb local indisponível: ${e.message}")
        false
    }

    /** Executa um comando de shell e devolve a saída (stdout+stderr). */
    fun exec(command: String): String =
        Connection.open().use { it.runService("exec:$command", input = null) }

    /**
     * Instala/atualiza um APK pelo mesmo caminho do "adb install" (streamed):
     *   exec:cmd package install -r -S <tamanho>  ← bytes do APK no stdin
     * O Android continua exigindo a MESMA assinatura e versão maior.
     * Atenção: em caso de sucesso o processo do app é encerrado pelo sistema
     * logo após a instalação — o BootReceiver (MY_PACKAGE_REPLACED) reabre.
     */
    fun install(apk: File): Boolean {
        val output = Connection.open().use { conn ->
            apk.inputStream().use { input ->
                conn.runService("exec:cmd package install -r -S ${apk.length()}", input)
            }
        }
        Log.i(TAG, "install: ${output.trim().take(200)}")
        return output.contains("Success")
    }

    // ─── Conexão ──────────────────────────────────────────────────────────

    private class Connection private constructor(private val socket: Socket) : AutoCloseable {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output: OutputStream = socket.getOutputStream().buffered()
        private var maxData = 4096
        private var nextLocalId = 1

        companion object {
            fun open(): Connection {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    socket.tcpNoDelay = true
                    return Connection(socket).also { it.handshake() }
                } catch (e: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                    throw e
                }
            }
        }

        private fun handshake() {
            send(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())
            val reply = read()
            when (reply.command) {
                A_CNXN -> maxData = reply.arg1.coerceIn(1024, MAX_PAYLOAD)
                A_AUTH -> throw IOException("adbd exige autorização (ro.adb.secure=1)")
                else -> throw IOException("resposta inesperada no handshake: ${reply.command}")
            }
        }

        /**
         * Abre um serviço (ex.: "exec:…"), envia `input` (se houver) respeitando
         * o controle de fluxo (um WRTE por vez, aguardando OKAY) e devolve
         * toda a saída até o CLSE.
         */
        fun runService(service: String, input: InputStream?): String {
            val localId = nextLocalId++
            send(A_OPEN, localId, 0, "$service\u0000".toByteArray())

            var remoteId = 0
            val out = StringBuilder()
            // Aguarda o OKAY de abertura (ou CLSE = serviço recusado)
            while (remoteId == 0) {
                val m = read()
                when (m.command) {
                    A_OKAY -> remoteId = m.arg0
                    A_CLSE -> throw IOException("serviço recusado: $service")
                    A_WRTE -> { out.append(String(m.data)); send(A_OKAY, localId, m.arg0, ByteArray(0)) }
                }
            }

            // Envia o stdin em blocos
            if (input != null) {
                val buf = ByteArray(maxData)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    send(A_WRTE, localId, remoteId, buf.copyOf(n))
                    // Espera o OKAY deste bloco (pode chegar saída no meio)
                    var acked = false
                    while (!acked) {
                        val m = read()
                        when (m.command) {
                            A_OKAY -> acked = true
                            A_WRTE -> { out.append(String(m.data)); send(A_OKAY, localId, remoteId, ByteArray(0)) }
                            A_CLSE -> return out.toString()   // terminou antes (erro na instalação)
                        }
                    }
                }
            }

            // Lê a saída até o serviço fechar
            while (true) {
                val m = read()
                when (m.command) {
                    A_WRTE -> { out.append(String(m.data)); send(A_OKAY, localId, remoteId, ByteArray(0)) }
                    A_CLSE -> {
                        try { send(A_CLSE, localId, remoteId, ByteArray(0)) } catch (_: Exception) {}
                        return out.toString()
                    }
                }
            }
        }

        private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1)
                .putInt(data.size).putInt(checksum(data)).putInt(command.inv())
                .array()
            output.write(header)
            if (data.isNotEmpty()) output.write(data)
            output.flush()
        }

        private fun read(): Message {
            val header = ByteArray(24)
            input.readFully(header)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = bb.int
            val arg0 = bb.int
            val arg1 = bb.int
            val length = bb.int
            bb.int                                   // checksum (ignorado)
            val magic = bb.int
            if (magic != command.inv()) throw IOException("mensagem adb inválida")
            if (length < 0 || length > MAX_PAYLOAD * 4) throw IOException("tamanho inválido: $length")
            val data = ByteArray(length)
            if (length > 0) input.readFully(data)
            return Message(command, arg0, arg1, data)
        }

        private fun checksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) sum += (b.toInt() and 0xff)
            return sum
        }

        override fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
