package com.localaux.soundshare.net

import android.util.Log
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/** Receiver side: connects to sender TCP :50000. */
class ControlClient(
    private val onStarted: (bufferMs: Int) -> Unit,
    private val onStopped: () -> Unit
) {
    @Volatile private var running = false
    private var socket: Socket? = null

    fun connect(ip: String) {
        if (running) return
        running = true
        Thread {
            try {
                Log.d("ControlClient", "Attempting TCP connection to $ip:50000...")
                val s = Socket()
                // FIX: Add a 5-second timeout so it doesn't hang forever
                s.connect(InetSocketAddress(ip, ControlServer.PORT), 5000)
                socket = s
                Log.d("ControlClient", "TCP connected! Sending handshake...")

                val writer = s.getOutputStream().bufferedWriter()
                val reader = s.getInputStream().bufferedReader()

                writer.write(JSONObject().apply {
                    put("type", "hello")
                    put("udp_port", 50001)
                }.toString() + "\n"); writer.flush()

                val line = reader.readLine() ?: return@Thread
                Log.d("ControlClient", "Received handshake: $line")
                val msg = JSONObject(line)
                if (msg.optString("type") != "start_audio") return@Thread

                onStarted(msg.optInt("buffer_ms", 100))
                while (running) {
                    val l = reader.readLine() ?: break
                    if (JSONObject(l).optString("type") == "stop") break
                }
            } catch (e: Exception) {
                Log.e("ControlClient", "Connection failed: ${e.javaClass.simpleName} - ${e.message}")
            } finally {
                onStopped()
            }
        }.start()
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) { }
    }
}