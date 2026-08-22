package com.localaux.soundshare

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.localaux.soundshare.audio.QualityMode
import com.localaux.soundshare.net.NsdBrowser
import com.localaux.soundshare.service.AudioStreamService
import com.localaux.soundshare.ui.FpsPolicy
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartSender: Button
    private lateinit var btnConnectReceiver: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvBufferLabel: TextView
    private lateinit var spnQuality: Spinner
    private lateinit var seekBuffer: SeekBar
    private lateinit var swHighFps: SwitchCompat
    private lateinit var swMediaSource: SwitchCompat
    private lateinit var etTargetIp: EditText
    private lateinit var lvFriends: ListView

    private val friends = LinkedHashMap<String, String>()   // display name -> host IP
    private val friendNames = ArrayList<String>()
    private lateinit var friendsAdapter: ArrayAdapter<String>
    private var browser: NsdBrowser? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private enum class Mode { IDLE, SENDER, RECEIVER }
    private var mode = Mode.IDLE

    private val prefs by lazy { getSharedPreferences("soundshare", MODE_PRIVATE) }
    private var highFpsEnabled: Boolean
        get() = prefs.getBoolean("high_fps", false)
        set(v) { prefs.edit().putBoolean("high_fps", v).apply() }

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (swMediaSource.isChecked) launchProjectionConsent()
            else beginSenderMode(useMedia = false, projectionData = null)
        } else toast("Mic permission needed")
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* service runs silently if denied */ }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            beginSenderMode(useMedia = true, projectionData = result.data)
        } else {
            toast("Consent denied — using mic")
            beginSenderMode(useMedia = false, projectionData = null)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(AudioStreamService.EXTRA_STATUS_MSG) ?: "Idle"
            tvStatus.text = "Status: $msg"
            if (msg == "Disconnected" || msg == "Idle") resetUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val tvLocalIp = findViewById<TextView>(R.id.tvLocalIp)
        etTargetIp = findViewById(R.id.etTargetIp)
        btnStartSender = findViewById(R.id.btnStartSender)
        btnConnectReceiver = findViewById(R.id.btnConnectReceiver)
        tvStatus = findViewById(R.id.tvStatus)
        tvBufferLabel = findViewById(R.id.tvBufferLabel)
        spnQuality = findViewById(R.id.spnQuality)
        seekBuffer = findViewById(R.id.seekBuffer)
        swHighFps = findViewById(R.id.swHighFps)
        swMediaSource = findViewById(R.id.swMediaSource)
        lvFriends = findViewById(R.id.lvFriends)

        FpsPolicy.apply(this, highFpsEnabled)
        swHighFps.isChecked = highFpsEnabled
        swHighFps.setOnCheckedChangeListener { _, checked ->
            highFpsEnabled = checked
            FpsPolicy.apply(this, checked)
        }

        swMediaSource.setOnCheckedChangeListener { _, checked ->
            if (checked && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                toast("Media source needs Android 10+ — using mic")
                swMediaSource.isChecked = false
            }
        }

        seekBuffer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) = updateBufferLabel()
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        spnQuality.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, QualityMode.entries.map { it.label }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spnQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                seekBuffer.progress = (QualityMode.entries[position].bufferMs / 50).coerceIn(2, 20)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spnQuality.setSelection(QualityMode.defaultFor(FpsPolicy.isLowEnd(this)).ordinal)

        // ===== NSD friends list (§7 v2) =====
        friendsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, friendNames)
        lvFriends.adapter = friendsAdapter
        lvFriends.setOnItemClickListener { _, _, pos, _ ->
            val host = friends[friendNames[pos]] ?: return@setOnItemClickListener
            if (mode == Mode.RECEIVER) stopAll()
            etTargetIp.setText(host)
            beginReceiverMode(host)
        }

        @Suppress("DEPRECATION")
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("SoundShareNsd").apply { setReferenceCounted(false); acquire() }

        browser = NsdBrowser(
            this,
            onFound = { name, host ->
                if (host != getLocalIpAddress() && !friends.containsKey(name)) {
                    friends[name] = host
                    runOnUiThread { refreshFriends() } // 👈 FIX: Force UI update on Main Thread
                }
            },
            onLost = { name ->
                if (friends.remove(name) != null) {
                    runOnUiThread { refreshFriends() } // 👈 FIX: Force UI update on Main Thread
                }
            }
        ).also { it.start() }

        tvLocalIp.text = "Your IP: ${getLocalIpAddress()}"

        btnStartSender.setOnClickListener {
            when {
                mode == Mode.SENDER -> stopAll()
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED ->
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                swMediaSource.isChecked -> launchProjectionConsent()
                else -> beginSenderMode(useMedia = false, projectionData = null)
            }
        }

        btnConnectReceiver.setOnClickListener {
            if (mode == Mode.RECEIVER) { stopAll(); return@setOnClickListener }
            val ip = etTargetIp.text.toString().trim()
            if (ip.isEmpty()) toast("Enter your friend's IP first") else beginReceiverMode(ip)
        }
    }

    private fun refreshFriends() {
        friendNames.clear()
        friendNames.addAll(friends.keys)
        friendsAdapter.notifyDataSetChanged()
    }

    private fun updateBufferLabel() {
        tvBufferLabel.text = "Buffer: ${seekBuffer.progress * 50} ms (more = smoother, extra delay)"
    }

    private fun launchProjectionConsent() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AudioStreamService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun beginSenderMode(useMedia: Boolean, projectionData: Intent?) {
        mode = Mode.SENDER
        val qm = QualityMode.entries[spnQuality.selectedItemPosition]
        val intent = Intent(this, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_START_SENDER
            putExtra(AudioStreamService.EXTRA_QUALITY_ORDINAL, qm.ordinal)
            putExtra(AudioStreamService.EXTRA_BUFFER_MS, seekBuffer.progress * 50)
            putExtra(AudioStreamService.EXTRA_USE_MEDIA, useMedia)
            if (projectionData != null) putExtra(AudioStreamService.EXTRA_PROJECTION_DATA, projectionData)
        }
        ContextCompat.startForegroundService(this, intent)

        btnStartSender.text = "Stop"
        btnConnectReceiver.isEnabled = false
        spnQuality.isEnabled = false
        seekBuffer.isEnabled = false
        swMediaSource.isEnabled = false
    }

    private fun beginReceiverMode(ip: String) {
        mode = Mode.RECEIVER
        val intent = Intent(this, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_START_RECEIVER
            putExtra(AudioStreamService.EXTRA_TARGET_IP, ip)
        }
        ContextCompat.startForegroundService(this, intent)

        btnConnectReceiver.text = "Disconnect"
        btnStartSender.isEnabled = false
    }

    private fun stopAll() {
        val intent = Intent(this, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_STOP
        }
        startService(intent)
        resetUi()
    }

    private fun resetUi() {
        mode = Mode.IDLE
        btnStartSender.text = "Start Broadcasting"
        btnConnectReceiver.text = "Connect & Listen"
        btnStartSender.isEnabled = true
        btnConnectReceiver.isEnabled = true
        spnQuality.isEnabled = true
        seekBuffer.isEnabled = true
        swMediaSource.isEnabled = true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        browser?.stop(); browser = null
        multicastLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

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