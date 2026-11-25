package com.mokasas.wikall

import io.ktor.server.application.install
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.netty.handler.ssl.SslHandler
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal enum class SignalType {
    PAIR, REJOIN, READY, PING, PONG, INVITE, ACCEPT, REJECT, BUSY, TIMEOUT, END, OFFER, ANSWER, ICE
}

internal data class Signal(val type: SignalType, val id: UUID, val payload: String? = null)
internal val probeId: UUID = UUID(0, 0)
private const val MAX_SIGNAL_SIZE = 16 * 1024
private const val SIGNAL_PATH = "/signals"

private object SignalCodec {
    fun encode(signal: Signal): String {
        val payload = signal.payload?.let { Wire.encode(it.encodeToByteArray()) }.orEmpty()
        val text = "2|${signal.type.name}|${signal.id}|$payload"
        require(text.length <= MAX_SIGNAL_SIZE)
        return text
    }

    fun decode(text: String): Signal {
        require(text.length in 1..MAX_SIGNAL_SIZE)
        val parts = text.split('|', limit = 5)
        require(parts.size == 4 && parts[0] == "2")
        val type = SignalType.valueOf(parts[1])
        val id = UUID.fromString(parts[2])
        require(id.toString() == parts[2])
        val payload = parts[3].takeIf { it.isNotEmpty() }
            ?.let { Wire.decode(it).decodeToString(throwOnInvalidSequence = true) }
        when (type) {
            SignalType.PAIR -> {
                require(id == probeId)
                val credentials = requireNotNull(payload).split('.')
                require(credentials.size == 2 && credentials.all(::validCredential))
            }
            SignalType.REJOIN -> require(id == probeId && payload != null && validCredential(payload))
            SignalType.OFFER, SignalType.ANSWER, SignalType.ICE -> require(id != probeId && payload != null)
            else -> require(payload == null)
        }
        return Signal(type, id, payload)
    }

    private fun validCredential(value: String): Boolean = value.length == 32 && Wire.decode(value).size == 24
}

internal fun credentialDigest(credential: String): String =
    Wire.encode(MessageDigest.getInstance("SHA-256").digest(credential.encodeToByteArray()))

internal interface PairedDevice {
    fun digest(): String?
    fun remember(digest: String)
    fun revoke()
}

internal interface SignalChannel : AutoCloseable {
    fun send(signal: Signal)
}

internal class LocalHost(
    context: SSLContext,
    address: String,
    listenPort: Int = PORT,
    private val pairedDevice: PairedDevice,
) : AutoCloseable {
    private class Peer(private val session: WebSocketSession) : SignalChannel {
        val outbound = Channel<Signal>(capacity = 48)

        override fun send(signal: Signal) {
            if (!outbound.trySend(signal).isSuccess) close()
        }

        override fun close() {
            outbound.cancel()
            session.cancel()
        }
    }

    private class Callbacks(
        val onReady: (SignalChannel) -> Unit,
        val onSignal: (Signal) -> Unit,
        val onEnded: () -> Unit,
    )

    private val leaseLock = Any()
    private val attempts = Semaphore(3)
    private val closed = AtomicBoolean()
    @Volatile private var callbacks: Callbacks? = null
    private var invitation: Pair<String, Long>? = null
    private var active: Peer? = null

    private val listener = embeddedServer(Netty, configure = {
        connector {
            host = address
            port = listenPort
        }
        connectionGroupSize = 1
        workerGroupSize = 2
        callGroupSize = 2
        requestReadTimeoutSeconds = 30
        // Use the platform TLS engine so the Android Keystore key remains non-exportable.
        channelPipelineConfig = {
            val engine = context.createSSLEngine().apply {
                useClientMode = false
                enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            }
            addFirst("wi-call-tls", SslHandler(engine).apply { handshakeTimeoutMillis = 5_000 })
        }
    }) {
        install(WebSockets) {
            maxFrameSize = MAX_SIGNAL_SIZE.toLong()
            pingPeriodMillis = 12_000
            timeoutMillis = 20_000
        }
        routing {
            webSocket(SIGNAL_PATH) {
                val handler = callbacks ?: return@webSocket
                if (!attempts.tryAcquire()) return@webSocket
                val owner = Peer(this)
                try {
                    servePeer(owner, handler, this)
                } finally {
                    owner.outbound.cancel()
                    attempts.release()
                }
            }
        }
    }.start(wait = false)

    val localPort: Int get() = runBlocking { listener.engine.resolvedConnectors().single().port }

    fun setInvitation(token: String, expires: Long) = synchronized(leaseLock) {
        check(!closed.get())
        invitation = token to expires
    }

    fun revokeDevice() = synchronized(leaseLock) {
        pairedDevice.revoke()
        invitation = null
        active?.let { peer ->
            active = null
            peer.close()
            callbacks?.onEnded?.invoke()
        }
    }

    fun serve(onReady: (SignalChannel) -> Unit, onSignal: (Signal) -> Unit, onEnded: () -> Unit) {
        check(callbacks == null)
        callbacks = Callbacks(onReady, onSignal, onEnded)
    }

    private suspend fun servePeer(owner: Peer, handler: Callbacks, session: WebSocketSession) {
        var authorized = false
        try {
            val hello = withTimeout(5_000) { session.readSignal() }
            synchronized(leaseLock) {
                check(!closed.get())
                authorize(hello)
                active?.let { previous ->
                    previous.close()
                    handler.onEnded()
                }
                active = owner
                authorized = true
                handler.onReady(owner)
            }
            session.send(Frame.Text(SignalCodec.encode(Signal(SignalType.READY, probeId))))
            coroutineScope {
                val writer = launch {
                    for (message in owner.outbound) session.send(Frame.Text(SignalCodec.encode(message)))
                }
                try {
                    while (true) {
                        val message = withTimeout(35_000) { session.readSignal() }
                        require(message.type !in setOf(SignalType.PAIR, SignalType.REJOIN, SignalType.READY))
                        if (message.type == SignalType.PING) owner.send(Signal(SignalType.PONG, message.id))
                        else synchronized(leaseLock) {
                            if (active === owner) handler.onSignal(message)
                        }
                    }
                } finally {
                    writer.cancel()
                }
            }
        } catch (_: Exception) {
            // Exiting the route closes unauthenticated or inactive connections.
        } finally {
            if (authorized) synchronized(leaseLock) {
                if (active === owner) {
                    active = null
                    handler.onEnded()
                }
            }
        }
    }

    private fun authorize(hello: Signal) {
        val payload = requireNotNull(hello.payload)
        when (hello.type) {
            SignalType.PAIR -> {
                val (token, expiration) = requireNotNull(invitation)
                val (submitted, credential) = payload.split('.')
                require(System.currentTimeMillis() < expiration && Wire.equal(token, submitted))
                pairedDevice.remember(credentialDigest(credential))
                invitation = null
            }
            SignalType.REJOIN -> {
                val expected = requireNotNull(pairedDevice.digest())
                require(Wire.equal(expected, credentialDigest(payload)))
            }
            else -> error("Expected pairing or resume")
        }
    }

    private suspend fun WebSocketSession.readSignal(): Signal =
        SignalCodec.decode((incoming.receive() as Frame.Text).readText())

    override fun close() {
        synchronized(leaseLock) {
            if (!closed.compareAndSet(false, true)) return
            invitation = null
            active?.close()
        }
        listener.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }
}

internal class LocalClient(private val host: String, pin: String, private val port: Int = PORT) : SignalChannel {
    private val trust = pinnedTrust(pin)
    private val http = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .sslSocketFactory(pinnedTls(trust).socketFactory, trust)
        .hostnameVerifier { hostname, _ -> hostname == host }
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .build()
    private val messages = ArrayBlockingQueue<Any>(48)
    private val ready = CountDownLatch(1)
    private val closed = AtomicBoolean()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var upgraded = false
    private object Disconnected

    fun connect(token: String?, credential: String): LocalClient {
        val request = Request.Builder().url("wss://$host:$port$SIGNAL_PATH").build()
        try {
            check(!closed.get())
            val transport = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    upgraded = true
                    ready.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val signal = runCatching { SignalCodec.decode(text) }.getOrNull()
                    if (signal == null || !messages.offer(signal)) webSocket.cancel()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    disconnected()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = disconnected()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = disconnected()
            })
            socket = transport
            if (closed.get()) transport.cancel()
            check(!closed.get() && ready.await(5, TimeUnit.SECONDS) && upgraded)
            val hello = if (token == null) Signal(SignalType.REJOIN, probeId, credential)
                else Signal(SignalType.PAIR, probeId, "$token.$credential")
            send(hello)
            check(receive(5) == Signal(SignalType.READY, probeId))
            return this
        } catch (error: Exception) {
            close()
            throw IOException("تعذّر الاتصال المشفّر بالخادم المحلي. تحقق من الشبكة والاقتران.", error)
        }
    }

    override fun send(signal: Signal) {
        check(!closed.get() && socket?.send(SignalCodec.encode(signal)) == true)
    }

    fun receive(): Signal = receive(25)

    private fun receive(timeout: Long): Signal =
        messages.poll(timeout, TimeUnit.SECONDS) as? Signal ?: throw IOException("Local WebSocket disconnected")

    private fun disconnected() {
        messages.clear()
        messages.offer(Disconnected)
        ready.countDown()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.cancel()
        disconnected()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
