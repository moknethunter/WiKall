package com.mokasas.wikall

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

@Suppress("DEPRECATION")
internal class LanDiscovery(context: Context) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun advertise(pin: String) {
        close()
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            override fun onServiceRegistered(info: NsdServiceInfo) {
                main.post {
                    if (registration !== this) runCatching { manager.unregisterService(this) }
                }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        }
        registration = listener
        val info = NsdServiceInfo().apply {
            serviceName = "Wi-Call ${discoveryId(pin).take(12)}"
            serviceType = TYPE
            port = PORT
            setAttribute("id", discoveryId(pin))
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun find(pin: String, result: (String?) -> Unit) {
        close()
        val listener = object : NsdManager.DiscoveryListener {
            var resolving = false

            fun finish(address: String?) {
                if (discovery !== this) return
                close()
                result(address)
            }

            override fun onStartDiscoveryFailed(type: String, code: Int) {
                main.post { finish(null) }
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onDiscoveryStarted(type: String) {
                main.post { if (discovery !== this) runCatching { manager.stopServiceDiscovery(this) } }
            }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (discovery !== this || resolving || !info.serviceName.startsWith("Wi-Call ${discoveryId(pin).take(12)}")) {
                        return@post
                    }
                    resolving = true
                    runCatching {
                        manager.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(service: NsdServiceInfo, code: Int) {
                                main.post { resolving = false }
                            }
                            override fun onServiceResolved(service: NsdServiceInfo) {
                                main.post {
                                    resolving = false
                                    if (service.port == PORT &&
                                        service.attributes["id"]?.decodeToString() == discoveryId(pin)) {
                                        service.host?.hostAddress?.takeIf(::privateIpv4)?.let(::finish)
                                    }
                                }
                            }
                        })
                    }.onFailure { resolving = false }
                }
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { listener.finish(null) }
        main.postDelayed({ listener.finish(null) }, 3_000)
    }

    fun close() {
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration?.let { runCatching { manager.unregisterService(it) } }
        discovery = null
        registration = null
    }

    private companion object {
        const val TYPE = "_mokasas._tcp."
    }
}
