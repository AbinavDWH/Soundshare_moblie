package com.localaux.soundshare.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/** Sender side: advertises _soundshare._tcp so receivers can find us (§7 v2). */
class NsdAdvertiser(context: Context, private val port: Int) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start() {
        if (listener != null) return
        
        val info = NsdServiceInfo()
        info.serviceName = "${Build.MODEL} · SoundShare"
        info.serviceType = SERVICE_TYPE
        info.port = port  // Explicit property assignment (maps to setPort)

        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: IllegalArgumentException) {
            // Catching the OEM bug! App won't crash, audio will still stream.
            Log.w("NsdAdvertiser", "NSD registration failed (OEM bug?): ${e.message}")
        } catch (e: Exception) {
            Log.w("NsdAdvertiser", "NSD registration failed: ${e.message}")
        }
    }

    fun stop() {
        listener?.let { try { nsdManager.unregisterService(it) } catch (_: Exception) { } }
        listener = null
    }

    companion object { const val SERVICE_TYPE = "_soundshare._tcp." }
}