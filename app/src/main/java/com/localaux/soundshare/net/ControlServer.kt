package com.localaux.soundshare.net

import com.localaux.soundshare.audio.QualityMode
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket

/** Sender side: TCP :50000, JSON handshake (§7/§8). */
class ControlServer(
    private val mode: QualityMode,
    private val bufferMs: Int,
    private val onReceiverConnected: (receiverIp: String, udpPort: Int) -> Unit,
    private val onReceiverDisconnected: () -> Unit
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                serverSocket = ServerSocket(PORT)
                while (running) {
                    val socket = serverSocket!!.accept()
                    clientSocket = socket
                    handle(socket)
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun handle(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()

            val hello = JSONObject(reader.readLine() ?: return)
            if (hello.optString("type") != "hello") return
            val udpPort = hello.optInt("udp_port", 50001)

            val startMsg = JSONObject().apply {
                put("type", "start_audio")
                put("version", 1)
                put("codec", if (mode.codec == 0) "opus" else "pcm")
                put("sample_rate", 48000)
                put("channels", 1)
                put("bit_depth", 16)
                put("bitrate_kbps", mode.bitrateKbps)
                put("udp_port", udpPort)
                put("buffer_ms", bufferMs)   // 👈 user-chosen buffer
            }
            writer.write(startMsg.toString() + "\n"); writer.flush()

            onReceiverConnected(socket.inetAddress.hostAddress ?: "", udpPort)

            while (running) {
                val line = reader.readLine() ?: break
                if (JSONObject(line).optString("type") == "stop") break
            }
        } catch (_: Exception) {
        } finally {
            onReceiverDisconnected()
            try { socket.close() } catch (_: Exception) { }
        }
    }

    fun stop() {
        running = false
        try { clientSocket?.close() } catch (_: Exception) { }
        try { serverSocket?.close() } catch (_: Exception) { }
    }

    companion object { const val PORT = 50000 }
}