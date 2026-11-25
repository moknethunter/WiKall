package com.mokasas.wikall

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class Mode { HOME, SERVER, CLIENT }

internal data class SessionState(
    val mode: Mode = Mode.HOME,
    val status: String = "",
    val address: String? = null,
    val pin: String? = null,
    val peer: String? = null,
    val latency: Long? = null,
    val busy: Boolean = false,
    val callId: UUID? = null,
    val callState: CallState = CallState.IDLE,
    val muted: Boolean = false,
)

@Singleton
internal class ConnectionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: CallStore,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newCachedThreadPool()
    private val sender = Executors.newSingleThreadExecutor()
    private val discovery = LanDiscovery(context)
    private var generation = 0
    private var host: LocalHost? = null
    private var client: LocalClient? = null
    private var channel: SignalChannel? = null
    private var identity: ServerIdentity? = null
    private var hostAddress: String? = null
    private var waiting: Pair<UUID, Long>? = null
    private var timer: Runnable? = null
    private var heartbeat: Runnable? = null
    private var retry: Runnable? = null
    private var retryCount = 0
    private var call = LocalCall()
    private var voice: VoiceMedia? = null
    private val pendingMedia = ArrayDeque<Signal>()
    private var trackedId: UUID? = null
    private var trackedState = CallState.IDLE
    private val mutable = MutableStateFlow(SessionState())
    val state = mutable.asStateFlow()
    var onUpdate: ((SessionState) -> Unit)? = null

    fun listAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && LAN_INTERFACES.any(it.name::startsWith) }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .filter(::privateIpv4)
        .distinct()

    fun start(address: String) {
        require(privateIpv4(address))
        stop()
        publish(SessionState(mode = Mode.SERVER, busy = true, status = "جارٍ تشغيل الخادم…"))
        val current = generation
        worker.execute {
            val result = runCatching {
                val keys = ServerIdentity.load()
                keys to LocalHost(keys.tls(), address, pairedDevice = store)
            }
            main.post {
                if (current != generation) {
                    result.getOrNull()?.second?.close()
                    return@post
                }
                result.onSuccess { (keys, server) ->
                    host = server
                    identity = keys
                    hostAddress = address
                    val pin = Wire.pin(keys.entry.certificate.publicKey)
                    discovery.advertise(pin)
                    publish(state.value.copy(address = address, pin = pin, busy = false, status = "الخادم يعمل على الشبكة المحلية."))
                    server.serve(
                        { connection -> onMain(current) {
                            channel = connection
                            publish(state.value.copy(peer = "1001", status = "المستخدم 1001 متصل."))
                        } },
                        { message -> onMain(current) { received(message) } },
                        { onMain(current) { disconnect("المستخدم 1001 غير متصل.") } },
                    )
                }.onFailure {
                    publish(state.value.copy(busy = false, status = "تعذّر تشغيل الخادم. تحقق من عنوان الشبكة والمنفذ."))
                }
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

    fun revoke() {
        val server = host ?: return
        sender.execute {
            val result = runCatching { server.revokeDevice() }
            main.post {
                if (host === server) disconnect(
                    if (result.isSuccess) "أُلغي اقتران المستخدم 1001." else "تعذّر إلغاء الاقتران."
                )
            }
        }
    }

    fun connect(profile: Profile, manual: String?) {
        require(manual == null || privateIpv4(manual))
        stop()
        if (profile.expires <= System.currentTimeMillis()) {
            publish(SessionState(mode = Mode.CLIENT, status = "انتهت صلاحية الملف. أنشئ ملفًا جديدًا."))
            return
        }
        locate(SavedConnection(manual ?: profile.host, profile.pin, Wire.token()), profile.token, manual != null)
    }

    fun reconnect(manual: String? = null) {
        require(manual == null || privateIpv4(manual))
        stop()
        publish(SessionState(mode = Mode.CLIENT, busy = true, status = "جارٍ قراءة الاتصال المحفوظ…"))
        val current = generation
        worker.execute {
            val saved = runCatching(store::saved).getOrNull()
            onMain(current) {
                if (saved == null) publish(SessionState(mode = Mode.CLIENT, status = "استورد ملف اقتران للاتصال."))
                else locate(saved.copy(address = manual ?: saved.address), null, manual != null)
            }
        }
    }

    fun forget() {
        stop()
        sender.execute { runCatching(store::forget) }
        publish(SessionState(mode = Mode.CLIENT, status = "حُذف الاتصال المحفوظ من هذا الهاتف."))
    }

    private fun locate(saved: SavedConnection, token: String?, manual: Boolean) {
        publish(SessionState(mode = Mode.CLIENT, busy = true, address = saved.address, status = "جارٍ البحث عن الخادم المحلي…"))
        if (manual) open(saved, token)
        else {
            val current = generation
            discovery.find(saved.pin) { address ->
                if (current == generation) open(saved.copy(address = address ?: saved.address), token)
            }
        }
    }

    private fun open(saved: SavedConnection, token: String?) {
        val current = generation
        publish(state.value.copy(busy = true, address = saved.address, status = "جارٍ التحقق من هوية الخادم…"))
        worker.execute {
            var online = false
            var endpoint: LocalClient? = null
            try {
                endpoint = LocalClient(saved.address, saved.pin)
                val candidate = endpoint
                main.post {
                    if (current == generation) client = candidate else candidate.close()
                }
                val active = endpoint.connect(token, saved.credential)
                // Persist only after the pinned server accepts this device.
                val savedOk = runCatching { store.save(saved) }.isSuccess
                online = true
                onMain(current) {
                    retryCount = 0
                    channel = active
                    publish(state.value.copy(
                        peer = "1000", busy = false,
                        status = if (savedOk) "متصل بالخادم المحلي." else "متصل. تعذّر حفظ الاقتران على الهاتف.",
                    ))
                    probe()
                    heartbeat = object : Runnable {
                        override fun run() {
                            if (current != generation || channel !== active) return
                            send(Signal(SignalType.PING, UUID.randomUUID()))
                            main.postDelayed(this, 10_000)
                        }
                    }.also { main.postDelayed(it, 10_000) }
                }
                while (true) {
                    val message = active.receive()
                    onMain(current) { received(message) }
                }
            } catch (_: Exception) {
                val retryConnection = token == null || online
                onMain(current) {
                    disconnect(if (online) "انقطع اتصال الخادم." else "تعذّر الاتصال. تحقق من الشبكة والاقتران.")
                    if (retryConnection && retryCount < RETRY_DELAYS.size) {
                        val delay = RETRY_DELAYS[retryCount++]
                        retry = Runnable { locate(saved, null, false) }.also { main.postDelayed(it, delay) }
                    }
                }
            } finally {
                runCatching { endpoint?.close() }
            }
        }
    }

    fun dial(peer: String) {
        if (channel == null || state.value.peer != peer) return
        call.dial()?.let(::send)
        changed()
    }

    fun decide(type: SignalType) {
        call.decide(type)?.let(::send)
        changed()
    }

    fun startMedia(id: UUID) {
        if (call.id != id || !call.accepted || voice != null) return
        val media = VoiceMedia(
            context,
            { type, payload -> if (call.id == id && call.accepted) send(Signal(type, id, payload)) },
            { status -> if (call.id == id) { call.media(status); changed() } },
            { if (call.id == id) failMedia() },
        )
        voice = media
        call.media(CallState.CONNECTING)
        changed()
        try {
            media.start(call.outgoing)
            while (pendingMedia.isNotEmpty()) {
                val signal = pendingMedia.removeFirst()
                media.receive(signal.type, checkNotNull(signal.payload))
            }
        } catch (_: Exception) {
            failMedia()
        } catch (_: LinkageError) {
            failMedia()
        }
    }

    fun failMedia() {
        if (!call.accepted) return
        call.failed()?.let(::send)
        changed()
    }

    fun audioServiceEnded(id: UUID) {
        if (call.id == id && voice != null) failMedia()
    }

    fun toggleMute() {
        val active = voice ?: return
        val mute = !state.value.muted
        active.mute(mute)
        publish(state.value.copy(muted = mute))
    }

    fun probe() {
        if (channel == null) return
        val id = UUID.randomUUID()
        waiting = id to SystemClock.elapsedRealtime()
        send(Signal(SignalType.PING, id))
        main.postDelayed({
            if (waiting?.first == id) {
                waiting = null
                publish(state.value.copy(latency = null, status = "انتهت مهلة فحص الاتصال."))
            }
        }, 5_000L)
    }

    private fun received(message: Signal) {
        when (message.type) {
            SignalType.PING -> send(Signal(SignalType.PONG, message.id))
            SignalType.PONG -> waiting?.takeIf { it.first == message.id }?.let {
                waiting = null
                publish(state.value.copy(latency = SystemClock.elapsedRealtime() - it.second))
            }
            SignalType.OFFER, SignalType.ANSWER, SignalType.ICE -> {
                if (message.id != call.id || !call.accepted) return
                if (message.type == SignalType.OFFER && call.outgoing || message.type == SignalType.ANSWER && !call.outgoing) return
                val media = voice
                if (media != null) media.receive(message.type, checkNotNull(message.payload))
                else if (pendingMedia.size < 40) pendingMedia.add(message)
            }
            else -> {
                call.remote(message)?.let(::send)
                changed()
            }
        }
    }

    private fun changed() {
        val id = call.id
        val next = call.state
        if (id == null) {
            voice?.close()
            voice = null
            pendingMedia.clear()
            val completed = trackedId
            val peer = state.value.peer
            if (completed != null && peer != null) {
                val item = CallHistory(completed.toString(), peer, next.name, System.currentTimeMillis())
                sender.execute { runCatching { store.record(item) } }
            }
        }
        if (id != trackedId || next != trackedState) {
            timer?.let(main::removeCallbacks)
            timer = null
            if (id != null) {
                val delay = when (next) {
                    CallState.CALLING -> 32_000L
                    CallState.RINGING -> 30_000L
                    CallState.ACCEPTED, CallState.CONNECTING -> 30_000L
                    CallState.RECONNECTING -> 8_000L
                    else -> null
                }
                if (delay != null) timer = Runnable {
                    if (call.id != id || call.state != next) return@Runnable
                    when (next) {
                        CallState.RINGING -> decide(SignalType.TIMEOUT)
                        CallState.CALLING -> {
                            call.remote(Signal(SignalType.TIMEOUT, id))
                            changed()
                        }
                        else -> failMedia()
                    }
                }.also { main.postDelayed(it, delay) }
            }
            trackedId = id
            trackedState = next
        }
        publish(state.value.copy(callId = id, callState = next, muted = id != null && state.value.muted))
    }

    private fun send(message: Signal) {
        val active = channel ?: return
        sender.execute {
            try {
                active.send(message)
            } catch (_: Exception) {
                runCatching { active.close() }
            }
        }
    }

    private fun disconnect(reason: String) {
        channel = null
        waiting = null
        heartbeat?.let(main::removeCallbacks)
        heartbeat = null
        call.disconnected()
        changed()
        publish(state.value.copy(peer = null, latency = null, busy = false, status = reason))
    }

    fun stop() {
        generation++
        retry?.let(main::removeCallbacks)
        retry = null
        retryCount = 0
        discovery.close()
        val oldClient = client
        val oldHost = host
        client = null
        host = null
        identity = null
        hostAddress = null
        disconnect("")
        call = LocalCall()
        publish(SessionState())
        if (oldClient != null || oldHost != null) {
            runCatching { oldClient?.close() }
            runCatching { oldHost?.close() }
        }
    }

    private fun publish(snapshot: SessionState) {
        mutable.value = snapshot
        onUpdate?.invoke(snapshot)
    }

    private fun onMain(current: Int, action: () -> Unit) {
        main.post { if (current == generation) action() }
    }

    private companion object {
        val LAN_INTERFACES = listOf("wlan", "swlan", "ap", "softap", "eth", "en", "p2p")
        val RETRY_DELAYS = listOf(2_000L, 4_000L, 8_000L)
    }
}
