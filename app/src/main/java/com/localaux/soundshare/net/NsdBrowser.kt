package com.localaux.soundshare.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** Receiver side: finds senders advertising _soundshare._tcp (§7 v2). */
class NsdBrowser(
    context: Context,
    private val onFound: (name: String, host: String) -> Unit,
    private val onLost: (name: String) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolving = mutableSetOf<String>()

    fun start() {
        if (discoveryListener != null) return
        val dl = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) { stop() }
            override fun onDiscoveryStopped(regType: String) {}
            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.startsWith(NsdAdvertiser.SERVICE_TYPE)) resolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                resolving.remove(info.serviceName)
                onLost(info.serviceName)
            }
        }
        discoveryListener = dl
        nsdManager.discoverServices(NsdAdvertiser.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, dl)
    }

    private fun resolve(info: NsdServiceInfo) {
        if (!resolving.add(info.serviceName)) return
        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                resolving.remove(i.serviceName)
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                resolving.remove(i.serviceName)
                val host = i.host?.hostAddress ?: return
                onFound(i.serviceName, host)
            }
        })
    }

    fun stop() {
        discoveryListener?.let { try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) { } }
        discoveryListener = null
    }
}