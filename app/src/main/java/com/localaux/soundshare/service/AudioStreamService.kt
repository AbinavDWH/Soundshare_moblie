package com.localaux.soundshare.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.localaux.soundshare.audio.MediaAudioCapture
import com.localaux.soundshare.audio.NativeEngine
import com.localaux.soundshare.audio.QualityMode
import com.localaux.soundshare.net.ControlClient
import com.localaux.soundshare.net.ControlServer
import com.localaux.soundshare.net.NsdAdvertiser

class AudioStreamService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var server: ControlServer? = null
    private var client: ControlClient? = null
    private var capture: MediaAudioCapture? = null
    private var advertiser: NsdAdvertiser? = null
    private val engine = NativeEngine()

    companion object {
        const val ACTION_START_SENDER = "com.localaux.soundshare.START_SENDER"
        const val ACTION_START_RECEIVER = "com.localaux.soundshare.START_RECEIVER"
        const val ACTION_STOP = "com.localaux.soundshare.STOP"

        const val EXTRA_QUALITY_ORDINAL = "quality_ordinal"
        const val EXTRA_TARGET_IP = "target_ip"
        const val EXTRA_USE_MEDIA = "use_media"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_BUFFER_MS = "buffer_ms_extra"

        const val BROADCAST_STATUS = "com.localaux.soundshare.STATUS_UPDATE"
        const val EXTRA_STATUS_MSG = "status_msg"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "soundshare_channel")
            .setContentTitle("SoundShare")
            .setContentText("Streaming active...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = 0
            when (intent?.action) {
                ACTION_START_SENDER -> {
                    fgsType = if (intent.getBooleanExtra(EXTRA_USE_MEDIA, false))
                        fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    else
                        fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                ACTION_START_RECEIVER ->
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            startForeground(1, notification, fgsType)
        } else {
            startForeground(1, notification)
        }

        when (intent?.action) {
            ACTION_START_SENDER -> startSender(intent)
            ACTION_START_RECEIVER -> startReceiver(intent)
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun startSender(intent: Intent) {
        val qm = QualityMode.entries[intent.getIntExtra(EXTRA_QUALITY_ORDINAL, 1)]
        val bufferMs = intent.getIntExtra(EXTRA_BUFFER_MS, 300)

        //  NEW: advertise ourselves so friends can tap-connect (§7 v2)
        advertiser = NsdAdvertiser(this, ControlServer.PORT).also { it.start() }

        var mediaActive = false
        if (intent.getBooleanExtra(EXTRA_USE_MEDIA, false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJECTION_DATA)

            if (data != null) {
                capture = MediaAudioCapture(this, data) { buf, n -> engine.feedAudio(buf, n) }
                mediaActive = capture!!.start()
                if (!mediaActive) broadcastStatus("Media capture unavailable — using mic")
            }
        }
        val useMic = !mediaActive

        server = ControlServer(
            mode = qm,
            bufferMs = bufferMs,
            onReceiverConnected = { ip, port ->
                engine.startSender(ip, port, qm.codec, qm.bitrateKbps, useMic)
                broadcastStatus(if (useMic) "Streaming mic → $ip 🎙️" else "Streaming media → $ip 🎵")
            },
            onReceiverDisconnected = {
                engine.stop()
                broadcastStatus("Waiting for a receiver…")
            }
        ).also { it.start() }
        broadcastStatus("Waiting for a receiver on port 50000… (buffer ${bufferMs}ms)")
    }

    private fun startReceiver(intent: Intent) {
        val ip = intent.getStringExtra(EXTRA_TARGET_IP) ?: return
        client = ControlClient(
            onStarted = { bufferMs ->
                engine.startReceiver(50001, bufferMs)
                broadcastStatus("Playing audio from $ip 🎧")
            },
            onStopped = {
                engine.stop()
                broadcastStatus("Disconnected")
                stopSelf()
            }
        ).also { it.connect(ip) }
        broadcastStatus("Connecting to $ip…")
    }

    private fun stopStreaming() {
        server?.stop(); server = null
        client?.disconnect(); client = null
        capture?.stop(); capture = null
        advertiser?.stop(); advertiser = null
        engine.stop()
        broadcastStatus("Idle")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SoundShareWifiLock").apply { acquire() }
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundShareWakeLock").apply { acquire() }
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun broadcastStatus(msg: String) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS_MSG, msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopStreaming()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "soundshare_channel", "SoundShare Streaming", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}