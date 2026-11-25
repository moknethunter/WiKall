package com.mokasas.wikall

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class Mode { HOME, SERVER, CLIENT }

internal data class SessionState(
    val mode: Mode = Mode.HOME,
    val status: String = "",
    val address: String? = null,
    val number: String? = null,
    val devices: List<Enrollment> = emptyList(),
    val onlineUsers: Set<String> = emptySet(),
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
    private val hostControl = Executors.newSingleThreadExecutor()
    private val sender = Executors.newSingleThreadExecutor()
    // Accessed only on hostControl, including while the listener is still starting.
    private var boundHost: LocalHost? = null
    private val discovery = LanDiscovery(context)
    @Volatile private var generation = 0
    private var host: LocalHost? = null
    private var client: LocalClient? = null
    private var channel: SignalChannel? = null
    private val onlineChannels = mutableMapOf<String, SignalChannel>()
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
        hostControl.execute {
            val result = runCatching {
                check(current == generation)
                val keys = ServerIdentity.load()
                val server = LocalHost(keys.tls(), address, registry = store, serverPin = Wire.pin(keys.entry.certificate.publicKey))
                boundHost = server
                keys to server
            }
            main.post {
                if (current != generation) return@post
                result.onSuccess { (keys, server) ->
                    host = server
                    identity = keys
                    hostAddress = address
                    val pin = Wire.pin(keys.entry.certificate.publicKey)
                    discovery.advertise(pin)
                    publish(state.value.copy(address = address, number = HOST_NUMBER, busy = false, status = "الخادم جاهز. ينتظر اتصال هاتف مفعّل."))
                    refreshUsers()
                    server.serve(
                        { number, connection -> onMain(current) {
                            onlineChannels[number] = connection
                            publish(state.value.copy(onlineUsers = onlineChannels.keys.toSet(), status = "المستخدم $number متصل."))
                        } },
                        { number, message -> onMain(current) { receivedFrom(number, message) } },
                        { number -> onMain(current) {
                            onlineChannels.remove(number)
                            if (state.value.peer == number) disconnect("المستخدم $number غير متصل.")
                            publish(state.value.copy(onlineUsers = onlineChannels.keys.toSet()))
                        } },
                    )
                }.onFailure {
                    publish(state.value.copy(busy = false, status = "تعذّر تشغيل الخادم. تحقق من عنوان الشبكة والمنفذ."))
                }
            }
        }
    }

    suspend fun issue(request: DeviceRequest): Pair<String, ByteArray> {
        val key = checkNotNull(identity).entry
        val address = checkNotNull(hostAddress)
        val issued = withContext(Dispatchers.IO) {
            val device = store.issue(request)
            val activation = Activation(address, key.certificate.publicKey, device.number, device.serial, device.activationId)
            device.number to activation.export(key.privateKey, request.encryption)
        }
        refreshUsers()
        return issued
    }

    fun revoke(number: String) {
        val server = host ?: return
        val current = generation
        sender.execute {
            val result = runCatching { server.revokeDevice(number) }
            onMain(current) {
                val message = if (result.isSuccess) "أُلغي تفعيل المستخدم $number." else "تعذّر إلغاء تفعيل المستخدم."
                publish(state.value.copy(status = message))
                refreshUsers()
            }
        }
    }

    private fun refreshUsers() {
        val current = generation
        sender.execute {
            val users = runCatching(store::devices).getOrNull() ?: return@execute
            onMain(current) { publish(state.value.copy(devices = users)) }
        }
    }

    fun connect(activation: Activation, file: ByteArray, manual: String? = null) {
        require(manual == null || privateIpv4(manual))
        stop()
        locate(SavedConnection(manual ?: activation.address, activation, file), manual != null)
    }

    fun reconnect(manual: String? = null) {
        require(manual == null || privateIpv4(manual))
        stop()
        publish(SessionState(mode = Mode.CLIENT, busy = true, status = "جارٍ قراءة الاتصال المحفوظ…"))
        val current = generation
        worker.execute {
            val saved = runCatching { store.saved(DeviceIdentity.load()) }.getOrNull()
            onMain(current) {
                if (saved == null) publish(SessionState(mode = Mode.CLIENT, status = "استورد ملف تفعيل صادرًا لهذا الهاتف."))
                else locate(saved.copy(address = manual ?: saved.address), manual != null)
            }
        }
    }

    fun forget() {
        stop()
        sender.execute { runCatching(store::forget) }
        publish(SessionState(mode = Mode.CLIENT, status = "حُذف الاتصال المحفوظ من هذا الهاتف."))
    }

    private fun locate(saved: SavedConnection, manual: Boolean) {
        publish(SessionState(mode = Mode.CLIENT, busy = true, address = saved.address, number = saved.activation.number, status = "جارٍ البحث عن الخادم المحلي…"))
        if (manual) open(saved)
        else {
            val current = generation
            discovery.find(saved.pin) { address ->
                if (current == generation) {
                    val discovered = address?.takeIf { it != saved.address }
                    open(saved.copy(address = discovered ?: saved.address), saved.takeIf { discovered != null })
                }
            }
        }
    }

    private fun open(saved: SavedConnection, fallback: SavedConnection? = null) {
        val current = generation
        publish(state.value.copy(busy = true, address = saved.address, status = "جارٍ التحقق من هوية الخادم…"))
        worker.execute {
            var online = false
            var endpoint: LocalClient? = null
            try {
                val device = DeviceIdentity.load()
                require(device.request.serial == saved.activation.serial)
                val trusted = store.saved(device)
                require(trusted == null || Wire.equal(trusted.pin, saved.pin)) {
                    "هذا الهاتف موثّق بخادم آخر. انس التفعيل المحفوظ قبل الانتقال إلى خادم جديد."
                }
                endpoint = LocalClient(saved.address, saved.pin, localNetwork = lanSocketFactory(saved.address))
                val candidate = endpoint
                main.post {
                    if (current == generation) client = candidate else candidate.close()
                }
                val active = endpoint.connect(saved.activation, device.signing)
                online = true
                onMain(current) {
                    retryCount = 0
                    channel = active
                    publish(state.value.copy(
                        peer = HOST_NUMBER, busy = false,
                        status = "متصل بالخادم المحلي.",
                    ))
                    sender.execute {
                        if (current == generation && runCatching { store.save(saved) }.isFailure) {
                            onMain(current) {
                                if (channel === active) publish(
                                    state.value.copy(status = "متصل. تعذّر حفظ الاقتران على الهاتف.")
                                )
                            }
                        }
                    }
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
            } catch (failure: Exception) {
                onMain(current) {
                    if (!online && fallback != null) {
                        open(fallback)
                        return@onMain
                    }
                    disconnect(if (online) "انقطع اتصال الخادم." else failure.message.orEmpty().ifBlank { "تعذّر الاتصال المحلي." })
                    if ((online || retryCount > 0) && retryCount < RETRY_DELAYS.size) {
                        val delay = RETRY_DELAYS[retryCount++]
                        retry = Runnable { locate(saved, false) }.also { main.postDelayed(it, delay) }
                    }
                }
            } finally {
                runCatching { endpoint?.close() }
            }
        }
    }

    fun dial(peer: String) {
        val snapshot = state.value
        if (call.id != null || !validNumber(peer) || peer == snapshot.number) return
        if (snapshot.mode == Mode.SERVER) {
            if (host == null) return
            selectPeer(peer, onlineChannels[peer])
        } else {
            if (snapshot.mode != Mode.CLIENT || channel == null) return
            selectPeer(peer, channel)
        }
        call.dial()?.copy(number = peer)?.let(::send)
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

    private fun selectPeer(number: String, target: SignalChannel?) {
        if (channel !== target || state.value.peer != number) {
            waiting = null
            channel = target
            publish(state.value.copy(peer = number, latency = null))
        }
    }

    private fun receivedFrom(number: String, message: Signal) {
        if (call.id == null && message.type == SignalType.INVITE) {
            val source = onlineChannels[number] ?: return
            selectPeer(number, source)
        }
        if (state.value.peer == number) received(message)
    }

    private fun lanSocketFactory(address: String): javax.net.SocketFactory? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val target = InetAddress.getByName(address)
        return manager.allNetworks.firstOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@firstOrNull false
            manager.getLinkProperties(network)?.linkAddresses?.any { link ->
                val local = link.address.address
                val remote = target.address
                local.size == remote.size && local.indices.all { index ->
                    val bits = (link.prefixLength - index * 8).coerceIn(0, 8)
                    val mask = (0xff shl (8 - bits)) and 0xff
                    ((local[index].toInt() xor remote[index].toInt()) and mask) == 0
                }
            } == true
        }?.socketFactory
    }

    private fun received(message: Signal) {
        if (state.value.mode == Mode.CLIENT && call.id == null && message.type == SignalType.INVITE) {
            val caller = message.number ?: return
            selectPeer(caller, channel)
        }
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
                            send(Signal(SignalType.END, id))
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
        val server = host
        if (server != null && message.type != SignalType.PING && message.type != SignalType.PONG) {
            sender.execute { server.sendAsHost(message) }
        } else channel?.let { sendTo(it, message) }
    }

    private fun sendTo(active: SignalChannel, message: Signal) {
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
        client = null
        host = null
        identity = null
        hostAddress = null
        onlineChannels.clear()
        disconnect("")
        call = LocalCall()
        publish(SessionState())
        runCatching { oldClient?.close() }
        hostControl.execute {
            runCatching { boundHost?.close() }
            boundHost = null
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
