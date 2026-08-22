package com.localaux.soundshare

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.localaux.soundshare.audio.NativeEngine
import com.localaux.soundshare.net.ControlClient
import com.localaux.soundshare.net.ControlServer
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private val engine = NativeEngine()
    private var server: ControlServer? = null
    private var client: ControlClient? = null

    private lateinit var btnStartSender: Button
    private lateinit var btnConnectReceiver: Button
    private lateinit var tvStatus: TextView

    private enum class Mode { IDLE, SENDER, RECEIVER }
    private var mode = Mode.IDLE

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginSenderMode() else toast("Mic permission needed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvLocalIp = findViewById<TextView>(R.id.tvLocalIp)
        val etTargetIp = findViewById<EditText>(R.id.etTargetIp)
        btnStartSender = findViewById(R.id.btnStartSender)
        btnConnectReceiver = findViewById(R.id.btnConnectReceiver)
        tvStatus = findViewById(R.id.tvStatus)

        tvLocalIp.text = "Your IP: ${getLocalIpAddress()}"

        btnStartSender.setOnClickListener {
            when {
                mode == Mode.SENDER -> stopAll()
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> beginSenderMode()
                else -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        btnConnectReceiver.setOnClickListener {
            if (mode == Mode.RECEIVER) { stopAll(); return@setOnClickListener }
            val ip = etTargetIp.text.toString().trim()
            if (ip.isEmpty()) toast("Enter your friend's IP first") else beginReceiverMode(ip)
        }
    }

    private fun beginSenderMode() {
        mode = Mode.SENDER
        server = ControlServer(
            onReceiverConnected = { ip, port ->
                engine.startSender(ip, port)
                runOnUiThread { setStatus("Streaming to $ip 🎙️") }
            },
            onReceiverDisconnected = {
                engine.stop()
                runOnUiThread { if (mode == Mode.SENDER) setStatus("Waiting for a receiver…") }
            }
        ).also { it.start() }
        btnStartSender.text = "Stop"
        btnConnectReceiver.isEnabled = false
        setStatus("Waiting for a receiver on port 50000…")
    }

    private fun beginReceiverMode(ip: String) {
        mode = Mode.RECEIVER
        client = ControlClient(
            onStarted = {
                engine.startReceiver(50001)
                runOnUiThread { setStatus("Playing audio from $ip 🎧") }
            },
            onStopped = {
                engine.stop()
                runOnUiThread { resetUi() }
            }
        ).also { it.connect(ip) }
        btnConnectReceiver.text = "Disconnect"
        btnStartSender.isEnabled = false
        setStatus("Connecting to $ip…")
    }

    private fun stopAll() {
        server?.stop(); server = null
        client?.disconnect(); client = null
        engine.stop()
        resetUi()
    }

    private fun resetUi() {
        mode = Mode.IDLE
        btnStartSender.text = "Start Broadcasting"
        btnConnectReceiver.text = "Connect & Listen"
        btnStartSender.isEnabled = true
        btnConnectReceiver.isEnabled = true
        setStatus("Idle")
    }

    private fun setStatus(s: String) { tvStatus.text = "Status: $s" }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { stopAll(); super.onDestroy() }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    val host = addr.hostAddress ?: continue
                    if (!addr.isLoopbackAddress && !host.contains(':')) return host
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return "unknown (turn on Wi-Fi)"
    }
}