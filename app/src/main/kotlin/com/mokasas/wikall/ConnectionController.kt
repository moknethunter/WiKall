package com.mokasas.wikall

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors

internal class ConnectionController(private val update: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var host: LocalHost? = null
    private var client: LocalClient? = null
    private var channel: SignalChannel? = null
    private var identity: ServerIdentity? = null
    private var hostAddress: String? = null
    private var waiting: Pair<UUID, Long>? = null
    private var timer: Runnable? = null
    private var heartbeat: Runnable? = null
    private val call = TestCall()
    val callState: CallState get() = call.state
    val hasCall: Boolean get() = call.id != null
    val connected: Boolean get() = channel != null
    val hosting: Boolean get() = host != null
    var busy = false
        private set
    var status = ""
        private set
    var latency: Long? = null
        private set
    var pin: String? = null
        private set

    fun listAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && it.name.startsWithAny("wlan", "swlan", "ap", "softap", "eth", "en", "p2p") }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .filter(::privateIpv4)
        .distinct()

    fun start(address: String) {
        require(privateIpv4(address))
        stop()
        busy = true
        status = "جارٍ تشغيل الخادم…"
        update()
        val current = generation
        worker.execute {
            val result = runCatching {
                val keys = ServerIdentity.load()
                keys to LocalHost(keys.tls(), address)
            }
            main.post {
                if (current != generation) {
                    result.getOrNull()?.second?.close()
                    return@post
                }
                busy = false
                result.onSuccess { (keys, server) ->
                    host = server
                    identity = keys
                    hostAddress = address
                    pin = Wire.pin(keys.entry.certificate.publicKey)
                    status = "الخادم يستمع على $address:$PORT"
                    server.serve({ connection, message ->
                        main.post {
                            if (generation == current) {
                                if (channel == null) {
                                    channel = connection
                                    status = "اقترن المستخدم 1001"
                                }
                                received(message)
                            }
                        }
                    }, {
                        main.post {
                            if (generation == current) resetConnection("انقطع اتصال المستخدم 1001. أنشئ ملفًا جديدًا لإعادة الاقتران.")
                        }
                    })
                }.onFailure { status = "تعذّر تشغيل الخادم. تحقق من عنوان Wi‑Fi وأن المنفذ $PORT متاح." }
                update()
            }
        }
    }

    fun export(): ByteArray {
        val server = checkNotNull(host)
        val key = checkNotNull(identity).entry
        val profile = Profile(checkNotNull(hostAddress), Wire.token(), System.currentTimeMillis() + Profile.LIFETIME, key.certificate.publicKey)
        val bytes = profile.export(key.privateKey)
        server.setInvitation(profile.token, profile.expires)
        return bytes
    }

    fun connect(profile: Profile, address: String) {
        require(privateIpv4(address))
        if (profile.expires <= System.currentTimeMillis()) {
            status = "انتهت صلاحية الملف. أنشئ ملفًا جديدًا من الهاتف الأول."
            update()
            return
        }
        stop()
        busy = true
        status = "جارٍ الاتصال والتحقق من البصمة…"
        update()
        val current = generation
        worker.execute {
            val result = runCatching { LocalClient(address, profile.pin) }
            val endpoint = result.getOrNull()
            if (endpoint == null) {
                main.post {
                    if (current == generation) {
                        busy = false
                        resetConnection("تعذّر إعداد الاتصال المشفّر.")
                    }
                }
                return@execute
            }
            main.post {
                if (current == generation) client = endpoint else endpoint.close()
            }
            try {
                val active = endpoint.connect(profile.token)
                main.post {
                    if (current == generation) {
                        busy = false
                        channel = active
                        status = "متصل بالخادم · المستخدم 1001"
                        probe()
                        heartbeat = object : Runnable {
                            override fun run() {
                                if (current != generation || channel == null) return
                                send(Signal(SignalType.PING, UUID.randomUUID()))
                                main.postDelayed(this, 10_000)
                            }
                        }.also { main.postDelayed(it, 10_000) }
                        update()
                    }
                }
                while (true) {
                    val message = active.receive()
                    main.post { if (current == generation) received(message) }
                }
            } catch (failure: Exception) {
                main.post {
                    if (current == generation) {
                        val reason = if (busy) failure.message.orEmpty() else "انقطع اتصال الخادم."
                        busy = false
                        resetConnection(reason)
                    }
                }
            } finally {
                endpoint.close()
            }
        }
    }

    fun dial() {
        if (!connected) return
        call.dial()?.let(::send)
        changed()
    }

    fun decide(type: SignalType) {
        call.decide(type)?.let(::send)
        changed()
    }

    fun probe() {
        if (!connected) return
        val id = UUID.randomUUID()
        waiting = id to SystemClock.elapsedRealtime()
        send(Signal(SignalType.PING, id))
        main.postDelayed({
            if (waiting?.first == id) {
                waiting = null
                status = "انتهت مهلة فحص الاتصال."
                update()
            }
        }, 5000L)
    }

    private fun received(message: Signal) {
        when (message.type) {
            SignalType.PING -> if (!hosting) send(Signal(SignalType.PONG, message.id))
            SignalType.PONG -> waiting?.takeIf { it.first == message.id }?.let {
                latency = SystemClock.elapsedRealtime() - it.second
                waiting = null
            }
            else -> call.remote(message)?.let(::send)
        }
        changed()
    }

    private fun changed() {
        timer?.let(main::removeCallbacks)
        timer = null
        update()
        if (call.state in setOf(CallState.CALLING, CallState.RINGING)) {
            val id = call.id
            val incoming = call.state == CallState.RINGING
            val deadline = deadlines.getOrPut(id) { SystemClock.elapsedRealtime() + if (incoming) 30_000 else 32_000 }
            timer = Runnable {
                if (call.id != id) return@Runnable
                if (incoming) decide(SignalType.TIMEOUT)
                else {
                    call.remote(Signal(SignalType.TIMEOUT, checkNotNull(id)))
                    changed()
                }
            }.also { main.postDelayed(it, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)) }
        }
        deadlines.keys.retainAll(setOf(call.id).takeIf { call.state in setOf(CallState.CALLING, CallState.RINGING) }.orEmpty())
    }

    private val deadlines = mutableMapOf<UUID?, Long>()
    private val sender = Executors.newSingleThreadExecutor()

    private fun send(message: Signal) {
        val active = channel ?: return
        val current = generation
        sender.execute {
            try {
                active.send(message)
            } catch (_: Exception) {
                main.post {
                    if (generation == current && channel === active) {
                        runCatching { active.close() }
                        resetConnection("تعذّر إرسال الرسالة. أعد الاقتران.")
                    }
                }
            }
        }
    }

    private fun resetConnection(reason: String) {
        channel = null
        waiting = null
        latency = null
        heartbeat?.let(main::removeCallbacks)
        heartbeat = null
        call.disconnected()
        status = reason
        changed()
    }

    fun stop() {
        generation++
        val oldClient = client
        val oldHost = host
        client = null
        host = null
        identity = null
        pin = null
        busy = false
        resetConnection("")
        if (oldClient != null || oldHost != null) {
            sender.execute {
                runCatching { oldClient?.close() }
                runCatching { oldHost?.close() }
            }
        }
    }

    fun close() {
        stop()
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        sender.shutdown()
    }

    private fun String.startsWithAny(vararg prefixes: String): Boolean = prefixes.any { startsWith(it) }
}
