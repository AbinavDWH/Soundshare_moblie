package com.localaux.soundshare.net

import org.json.JSONObject
import java.net.Socket

/** Receiver side: connects to sender TCP :50000. */
class ControlClient(
    private val onStarted: () -> Unit,
    private val onStopped: () -> Unit
) {
    @Volatile private var running = false
    private var socket: Socket? = null

    fun connect(ip: String) {
        if (running) return
        running = true
        Thread {
            try {
                val s = Socket(ip, ControlServer.PORT)
                socket = s
                val writer = s.getOutputStream().bufferedWriter()
                val reader = s.getInputStream().bufferedReader()

                writer.write(JSONObject().apply {
                    put("type", "hello")
                    put("udp_port", 50001)
                }.toString() + "\n"); writer.flush()

                val line = reader.readLine() ?: return@Thread
                val msg = JSONObject(line)
                if (msg.optString("type") != "start_audio") return@Thread

                onStarted()
                while (running) {                // listen for control messages
                    val l = reader.readLine() ?: break
                    if (JSONObject(l).optString("type") == "stop") break
                }
            } catch (_: Exception) {
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