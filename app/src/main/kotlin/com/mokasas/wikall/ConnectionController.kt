package com.mokasas.wikall

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
    val speaker: Boolean = false,
    val internetReady: Boolean = false,
    val ice: String = "",
    val publicKey: String? = null,
    val missed: List<CallHistory> = emptyList(),
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
    private var lanAttempt = 0
    private var savedClient: SavedConnection? = null
    private val preferences = context.getSharedPreferences("wi-call-directory", Context.MODE_PRIVATE)
    private var directory: DirectoryClient? = null
    private var directoryIdentity: Triple<String, String, java.security.PrivateKey>? = null
    private var directoryVersion = 0
    private var directoryRetry: Runnable? = null
    private var internetIce: List<InternetIce>? = null
    private var internetCall = false
    private var pendingInternet: Signal? = null
    private var monitoring = false
    private var topology: Pair<Network?, List<String>>? = null
    private var networkChange: Runnable? = null
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val defaultCallback = networkCallback()
    private val lanCallback = networkCallback()
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

    fun start(address: String?) {
        require(address == null || privateIpv4(address))
        stop()
        monitorNetwork()
        publish(SessionState(mode = Mode.SERVER, busy = true, status = "جارٍ تشغيل الخادم…"))
        bind(address)
    }

    private fun bind(address: String?) {
        val attempt = ++lanAttempt
        val current = generation
        hostControl.execute {
            val result = runCatching {
                check(current == generation)
                runCatching { boundHost?.close() }
                boundHost = null
                val keys = ServerIdentity.load()
                val server = address?.let {
                    runCatching { LocalHost(keys.tls(), it, registry = store, serverPin = Wire.pin(keys.entry.certificate.publicKey)) }.getOrNull()
                }
                boundHost = server
                keys to server
            }
            main.post {
                if (current != generation || attempt != lanAttempt) return@post
                result.onSuccess { (keys, server) ->
                    host = server
                    identity = keys
                    hostAddress = address.takeIf { server != null }
                    val pin = Wire.pin(keys.entry.certificate.publicKey)
                    if (server != null) discovery.advertise(pin)
                    publish(state.value.copy(address = address.takeIf { server != null }, number = HOST_NUMBER, busy = false,
                        publicKey = Wire.encode(keys.entry.certificate.publicKey.encoded),
                        status = if (server != null) "الخادم المحلي جاهز." else "الخادم المحلي غير متاح على هذه الشبكة."))
                    refreshUsers()
                    if (directoryIdentity == null) useDirectory(pin, HOST_NUMBER, keys.entry.privateKey)
                    server?.serve(
                        { number, connection -> onMain(current) {
                            if (attempt != lanAttempt) return@onMain
                            onlineChannels[number] = connection
                            publish(state.value.copy(onlineUsers = onlineChannels.keys.toSet(), status = "المستخدم $number متصل."))
                        } },
                        { number, message -> onMain(current) { if (attempt == lanAttempt && !internetCall) receivedFrom(number, message) } },
                        { number -> onMain(current) {
                            if (attempt != lanAttempt) return@onMain
                            onlineChannels.remove(number)
                            if (!internetCall && state.value.peer == number) disconnect("المستخدم $number غير متصل.")
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
        if (identity == null) return
        val server = host
        val current = generation
        sender.execute {
            val result = runCatching { if (server != null) server.revokeDevice(number) else store.revoke(number) }
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
            onMain(current) {
                publish(state.value.copy(devices = users))
                if (internetIce != null) directory?.roster(users)
            }
        }
    }

    fun connect(activation: Activation, file: ByteArray, manual: String? = null) {
        require(manual == null || privateIpv4(manual))
        stop()
        monitorNetwork()
        locate(SavedConnection(manual ?: activation.address, activation, file), manual != null)
    }

    fun reconnect(manual: String? = null) {
        require(manual == null || privateIpv4(manual))
        stop()
        monitorNetwork()
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
        savedClient = saved
        val attempt = ++lanAttempt
        publish(state.value.copy(mode = Mode.CLIENT, busy = true, address = saved.address, number = saved.activation.number, status = "جارٍ البحث عن الخادم المحلي…"))
        if (directoryIdentity == null) {
            val current = generation
            worker.execute {
                val device = runCatching { DeviceIdentity.load() }.getOrNull() ?: return@execute
                val previous = runCatching { store.saved(device) }.getOrNull()
                if (device.request.serial == saved.activation.serial && (previous == null || Wire.equal(previous.pin, saved.pin))) {
                    onMain(current) {
                        if (attempt == lanAttempt && directoryIdentity == null)
                            useDirectory(saved.pin, saved.activation.number, device.signing)
                    }
                }
            }
        }
        if (manual) open(saved)
        else {
            val current = generation
            discovery.find(saved.pin) { address ->
                if (current == generation && attempt == lanAttempt) {
                    val gateways = runCatching(::lanGateways).getOrDefault(emptyList())
                    val candidates = lanCandidates(address, gateways, saved.address, saved.activation.address)
                    open(saved.copy(address = candidates.first()), candidates.drop(1))
                }
            }
        }
    }

    private fun open(saved: SavedConnection, fallback: List<String> = emptyList()) {
        val attempt = lanAttempt
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
                endpoint = try {
                    LocalClient(saved.address, saved.pin, localNetwork = lanSocketFactory(saved.address))
                } catch (failure: java.io.IOException) {
                    throw EndpointUnavailable("تعذّر فتح اتصال على الشبكة الحالية.", failure)
                }
                val candidate = endpoint
                main.post {
                    if (current == generation && attempt == lanAttempt) client = candidate else candidate.close()
                }
                val active = endpoint.connect(saved.activation, device.signing)
                online = true
                onMain(current) {
                    if (attempt != lanAttempt) return@onMain
                    savedClient = saved
                    retryCount = 0
                    channel = active
                    publish(state.value.copy(
                        peer = if (call.id == null) HOST_NUMBER else state.value.peer, busy = false,
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
                    onMain(current) {
                        if (attempt == lanAttempt && (!internetCall || message.type in setOf(SignalType.PING, SignalType.PONG)))
                            received(message)
                    }
                }
            } catch (failure: Exception) {
                onMain(current) {
                    if (attempt != lanAttempt) return@onMain
                    val addressFailure = failure is EndpointUnavailable
                    if (!online && addressFailure && fallback.isNotEmpty()) {
                        open(saved.copy(address = fallback.first()), fallback.drop(1))
                        return@onMain
                    }
                    val reason = when {
                        online -> "انقطع اتصال الخادم."
                        addressFailure -> "تعذّر العثور على الخادم بهذا العنوان. شغّله على الشبكة الحالية أو أدخل عنوانه عبر «تغيير عنوان الخادم». التفعيل محفوظ إن سبق استخدامه."
                        else -> failure.message.orEmpty().ifBlank { "تعذّر الاتصال المحلي." }
                    }
                    disconnect(reason)
                    if ((online || addressFailure && retryCount > 0) && retryCount < RETRY_DELAYS.size) {
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
        val local = when (snapshot.mode) {
            Mode.SERVER -> host != null && peer in onlineChannels
            Mode.CLIENT -> channel != null
            Mode.HOME -> return
        }
        if (!local && directoryIdentity == null && host == null) return
        if (snapshot.mode == Mode.SERVER) selectPeer(peer, onlineChannels[peer])
        else selectPeer(peer, channel)
        val invite = call.dial()?.copy(number = peer) ?: return
        pendingInternet = invite
        if (local) send(invite) else fallback(invite)
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
            internetIce.takeIf { internetCall },
            { if (call.id == id) publish(state.value.copy(ice = it)) },
        )
        voice = media
        call.media(CallState.CONNECTING)
        changed()
        try {
            media.start(call.outgoing)
            while (pendingMedia.isNotEmpty()) {
                val signal = pendingMedia.removeFirst()
                if (signal.payload != null) media.receive(signal.type, signal.payload)
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

    fun toggleSpeaker() {
        val enabled = !state.value.speaker
        if (voice?.speaker(enabled) == true) publish(state.value.copy(speaker = enabled))
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

    private fun lanNetworks(): List<Pair<android.net.Network, android.net.LinkProperties>> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
            manager.getLinkProperties(network)?.let { network to it }
        }
    }

    private fun lanGateways(): List<String> = lanNetworks().flatMap { (_, link) ->
        link.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress }
    }

    private fun lanSocketFactory(address: String): javax.net.SocketFactory? {
        val target = InetAddress.getByName(address)
        return lanNetworks().firstOrNull { (_, properties) ->
            properties.linkAddresses.any { link ->
                val local = link.address.address
                val remote = target.address
                local.size == remote.size && local.indices.all { index ->
                    val bits = (link.prefixLength - index * 8).coerceIn(0, 8)
                    val mask = (0xff shl (8 - bits)) and 0xff
                    ((local[index].toInt() xor remote[index].toInt()) and mask) == 0
                }
            }
        }?.first?.socketFactory
    }

    private fun received(message: Signal) {
        if (state.value.mode == Mode.CLIENT && call.id == null && message.type == SignalType.INVITE) {
            val caller = message.number ?: return
            selectPeer(caller, channel)
        }
        when (message.type) {
            SignalType.UNAVAILABLE -> {
                val invite = pendingInternet?.takeIf { it.id == call.id && it.id == message.id }
                if (!internetCall && invite != null) fallback(invite)
                else {
                    call.remote(message)
                    changed()
                }
            }
            SignalType.RESTART, SignalType.REPAIR -> {
                if (message.id == call.id && call.accepted) {
                    if (message.type == SignalType.REPAIR && call.outgoing) requestRepair()
                    if (message.type == SignalType.RESTART && !call.outgoing) voice?.restart(false)
                }
            }
            SignalType.PING -> channel?.let { sendTo(it, Signal(SignalType.PONG, message.id)) }
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
            pendingInternet = null
            internetCall = false
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
                    CallState.RECONNECTING -> 20_000L
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
        publish(state.value.copy(callId = id, callState = next, muted = id != null && state.value.muted,
            speaker = id != null && state.value.speaker, ice = if (id != null) state.value.ice else ""))
    }

    private fun send(message: Signal) {
        if (internetCall && message.type != SignalType.PING && message.type != SignalType.PONG) {
            directory?.send(message)
            return
        }
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
        if (!internetCall) {
            val invite = pendingInternet?.takeIf { call.id == it.id && call.state == CallState.CALLING }
            if (invite != null) fallback(invite)
            else {
                call.disconnected()
                changed()
            }
        }
        publish(state.value.copy(peer = state.value.peer.takeIf { internetCall }, latency = null, busy = false, status = reason))
    }

    fun stop() {
        generation++
        lanAttempt++
        unmonitorNetwork()
        directoryVersion++
        directoryRetry?.let(main::removeCallbacks)
        directoryRetry = null
        directory?.close()
        directory = null
        directoryIdentity = null
        internetIce = null
        internetCall = false
        pendingInternet = null
        savedClient = null
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

    fun resumeMissed() {
        if (state.value.mode != Mode.HOME || internetEndpoint().isBlank()) return
        if (preferences.getBoolean("owner", false)) start(listAddresses().firstOrNull()) else reconnect()
    }

    fun internetEndpoint(): String = preferences.getString("endpoint", "").orEmpty()

    fun configureInternet(value: String) {
        require(value.isBlank() || directoryUrl(value) != null)
        if (value.trim() == internetEndpoint()) return
        preferences.edit().putString("endpoint", value.trim()).apply()
        if (internetCall) {
            call.decide(SignalType.END)?.let(::send)
            changed()
        }
        openDirectory()
    }

    private fun useDirectory(realm: String, number: String, key: java.security.PrivateKey) {
        directoryIdentity = Triple(realm, number, key)
        loadMissed()
        openDirectory()
    }

    private fun loadMissed() {
        val current = generation
        sender.execute {
            val items = runCatching { store.missed() }.getOrDefault(emptyList())
            onMain(current) { publish(state.value.copy(missed = items)) }
        }
    }

    private fun openDirectory() {
        val version = ++directoryVersion
        val current = generation
        directoryRetry?.let(main::removeCallbacks)
        directoryRetry = null
        directory?.close()
        directory = null
        internetIce = null
        publish(state.value.copy(internetReady = false))
        val (realm, number, key) = directoryIdentity ?: return
        val endpoint = internetEndpoint().takeIf { it.isNotBlank() } ?: return
        directory = DirectoryClient(endpoint, realm, number, key,
            ready = { ice ->
                onMain(current) {
                    if (version != directoryVersion) return@onMain
                    internetIce = ice
                    publish(state.value.copy(internetReady = true))
                    if (number == HOST_NUMBER) refreshUsers()
                    registerMissedPush(context) { token ->
                        onMain(current) { if (version == directoryVersion) directory?.pushToken(token) }
                    }
                    if (internetCall && call.accepted) requestRepair()
                    else pendingInternet?.takeIf { internetCall && it.id == call.id }?.let {
                        directory?.send(it)
                        pendingInternet = null
                    }
                    preferences.edit().putBoolean("owner", number == HOST_NUMBER).apply()
                    if (number != HOST_NUMBER) savedClient?.let { connection ->
                        sender.execute { if (current == generation) runCatching { store.save(connection) } }
                    }
                }
            },
            signal = { message ->
                onMain(current) {
                    if (version != directoryVersion) return@onMain
                    if (message.type == SignalType.INVITE) {
                        if (call.id == message.id) return@onMain
                        if (call.id != null) {
                            directory?.send(Signal(SignalType.BUSY, message.id))
                            return@onMain
                        }
                        val peer = message.number?.takeIf(::validNumber) ?: return@onMain
                        internetCall = true
                        selectPeer(peer, channel)
                    }
                    if (internetCall) received(message)
                }
            },
            missed = { item ->
                sender.execute {
                    if (current != generation) return@execute
                    val row = runCatching {
                        store.record(CallHistory(item.id.toString(), item.number, CallState.MISSED.name, item.at * 1000))
                    }.getOrNull() ?: return@execute
                    onMain(current) {
                        if (version == directoryVersion) directory?.acknowledge(item.id)
                        loadMissed()
                        if (row != -1L && !item.pushed) showMissed(item)
                    }
                }
            },
            closed = {
                onMain(current) {
                    if (version != directoryVersion) return@onMain
                    internetIce = null
                    publish(state.value.copy(internetReady = false))
                    directoryRetry = Runnable { if (version == directoryVersion) openDirectory() }
                        .also { main.postDelayed(it, 5_000) }
                }
            },
        )
    }

    private fun fallback(invite: Signal) {
        if (internetEndpoint().isBlank() || directoryIdentity == null) {
            call.remote(Signal(SignalType.UNAVAILABLE, invite.id))
            changed()
            return
        }
        internetCall = true
        if (internetIce != null && directory?.send(invite) == true) pendingInternet = null
        else pendingInternet = invite
    }

    private fun showMissed(item: MissedCall) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(NotificationChannel("wi-call-missed", "مكالمات Wi-Call الفائتة", NotificationManager.IMPORTANCE_DEFAULT))
        val action = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notifications.notify(item.id.toString(), 40, Notification.Builder(context, "wi-call-missed")
            .setSmallIcon(R.drawable.ic_wikall).setContentTitle("مكالمة فائتة من ${item.number}")
            .setContentIntent(action).setAutoCancel(true).build())
    }

    private fun networkCallback() = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkCheck()
        override fun onLost(network: Network) = scheduleNetworkCheck()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = scheduleNetworkCheck()
    }

    private fun monitorNetwork() {
        topology = currentTopology()
        connectivity.registerDefaultNetworkCallback(defaultCallback)
        runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), lanCallback)
        }
        monitoring = true
    }

    private fun unmonitorNetwork() {
        if (!monitoring) return
        monitoring = false
        runCatching { connectivity.unregisterNetworkCallback(defaultCallback) }
        runCatching { connectivity.unregisterNetworkCallback(lanCallback) }
        networkChange?.let(main::removeCallbacks)
        networkChange = null
    }

    private fun currentTopology() = connectivity.activeNetwork to
        (runCatching(::listAddresses).getOrDefault(emptyList()) + runCatching(::lanGateways).getOrDefault(emptyList())).sorted()

    private fun scheduleNetworkCheck() {
        main.post {
            if (!monitoring) return@post
            networkChange?.let(main::removeCallbacks)
            networkChange = Runnable {
                val previous = topology
                val updated = currentTopology()
                topology = updated
                if (updated == previous) return@Runnable
                if (updated.first != previous?.first && internetEndpoint().isNotBlank()) openDirectory()
                if (call.accepted && !internetCall) requestRepair()
                if (updated.second != previous?.second || channel == null) {
                    retry?.let(main::removeCallbacks)
                    retry = null
                    when (state.value.mode) {
                        Mode.SERVER -> {
                            val addresses = runCatching(::listAddresses).getOrDefault(emptyList())
                            val selected = hostAddress?.takeIf { it in addresses } ?: addresses.firstOrNull()
                            if (selected != hostAddress || (host == null && selected != null)) {
                                onlineChannels.clear()
                                discovery.close()
                                if (!internetCall) disconnect("تغيّرت الشبكة المحلية.")
                                bind(selected)
                            }
                        }
                        Mode.CLIENT -> savedClient?.let {
                            if (channel == null || !call.accepted || internetCall) {
                                discovery.close()
                                val old = client
                                client = null
                                channel = null
                                locate(it, false)
                                worker.execute { runCatching { old?.close() } }
                            }
                        }
                        Mode.HOME -> Unit
                    }
                }
            }.also { main.postDelayed(it, 800) }
        }
    }

    private fun requestRepair() {
        val id = call.id ?: return
        if (!call.accepted || voice == null) return
        if (call.outgoing) {
            send(Signal(SignalType.RESTART, id))
            voice?.restart(true)
        } else {
            send(Signal(SignalType.REPAIR, id))
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
