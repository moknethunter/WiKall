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
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import java.io.IOException
import java.net.Proxy
import java.security.PrivateKey
import javax.net.SocketFactory
import javax.net.ssl.SSLException
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
    CHALLENGE, AUTH, READY, PING, PONG, INVITE, ACCEPT, REJECT, BUSY, TIMEOUT, END, OFFER, ANSWER, ICE
}

internal data class Signal(val type: SignalType, val id: UUID, val payload: String? = null)
internal val probeId: UUID = UUID(0, 0)
private const val MAX_SIGNAL_SIZE = 16 * 1024
private const val SIGNAL_PATH = "/signals"

private object SignalCodec {
    fun encode(signal: Signal): String {
        val payload = signal.payload?.let { Wire.encode(it.encodeToByteArray()) }.orEmpty()
        val text = "3|${signal.type.name}|${signal.id}|$payload"
        require(text.length <= MAX_SIGNAL_SIZE)
        return text
    }

    fun decode(text: String): Signal {
        require(text.length in 1..MAX_SIGNAL_SIZE)
        val parts = text.split('|', limit = 5)
        require(parts.size == 4 && parts[0] == "3")
        val type = SignalType.valueOf(parts[1])
        val id = UUID.fromString(parts[2])
        require(id.toString() == parts[2])
        val payload = parts[3].takeIf { it.isNotEmpty() }
            ?.let { Wire.decode(it).decodeToString(throwOnInvalidSequence = true) }
        when (type) {
            SignalType.CHALLENGE -> require(id == probeId && payload != null && validNonce(payload))
            SignalType.AUTH -> require(id == probeId && payload != null && payload.length <= 420)
            SignalType.OFFER, SignalType.ANSWER, SignalType.ICE -> require(id != probeId && payload != null)
            else -> require(payload == null)
        }
        return Signal(type, id, payload)
    }

}

internal interface EnrollmentRegistry {
    fun find(number: String): Enrollment?
    fun revoke(number: String)
}

internal interface SignalChannel : AutoCloseable {
    fun send(signal: Signal)
}

internal class LocalHost(
    context: SSLContext,
    address: String,
    listenPort: Int = PORT,
    private val registry: EnrollmentRegistry,
    private val serverPin: String,
    private val onHandshakeFailure: ((Throwable) -> Unit)? = null,
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
        val onReady: (String, SignalChannel) -> Unit,
        val onSignal: (String, Signal) -> Unit,
        val onEnded: (String) -> Unit,
    )

    private val leaseLock = Any()
    private val attempts = Semaphore(3)
    private val closed = AtomicBoolean()
    @Volatile private var callbacks: Callbacks? = null
    private val active = mutableMapOf<String, Peer>()

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
                // Android 10's JSSE engine and non-exportable RSA server keys can fail TLS 1.3.
                // Keep a modern, pinned TLS 1.2 connection for that platform.
                val tls13 = context.provider.name != "AndroidOpenSSL" || android.os.Build.VERSION.SDK_INT > 29
                enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || tls13 && it == "TLSv1.3" }.toTypedArray()
                enabledCipherSuites = enabledCipherSuites.filter { suite ->
                    suite.startsWith("TLS_AES_") || suite == "TLS_CHACHA20_POLY1305_SHA256" ||
                        suite.startsWith("TLS_ECDHE_RSA_WITH_AES_") && "_GCM_" in suite
                }.toTypedArray()
            }
            addFirst("wi-call-tls", SslHandler(engine).apply { handshakeTimeoutMillis = 5_000 })
            onHandshakeFailure?.let { report ->
                addAfter("wi-call-tls", "wi-call-handshake-report", object : ChannelInboundHandlerAdapter() {
                    override fun userEventTriggered(ctx: ChannelHandlerContext, event: Any) {
                        if (event is SslHandshakeCompletionEvent && !event.isSuccess) event.cause()?.let(report)
                        super.userEventTriggered(ctx, event)
                    }

                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                        report(cause)
                        super.exceptionCaught(ctx, cause)
                    }
                })
            }
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
                val owner = Peer(this)
                try {
                    servePeer(owner, handler, this)
                } finally {
                    owner.outbound.cancel()
                }
            }
        }
    }.start(wait = false)

    val localPort: Int get() = runBlocking { listener.engine.resolvedConnectors().single().port }

    fun revokeDevice(number: String) = synchronized(leaseLock) {
        registry.revoke(number)
        active.remove(number)?.let { peer ->
            peer.close()
            callbacks?.onEnded?.invoke(number)
        }
    }

    fun serve(
        onReady: (String, SignalChannel) -> Unit,
        onSignal: (String, Signal) -> Unit,
        onEnded: (String) -> Unit,
    ) {
        check(callbacks == null)
        callbacks = Callbacks(onReady, onSignal, onEnded)
    }

    private suspend fun servePeer(owner: Peer, handler: Callbacks, session: WebSocketSession) {
        if (!attempts.tryAcquire()) return
        val number = try {
            val challenge = Wire.token()
            session.send(Frame.Text(SignalCodec.encode(Signal(SignalType.CHALLENGE, probeId, challenge))))
            val hello = withTimeout(8_000) { session.readSignal() }
            synchronized(leaseLock) {
                check(!closed.get())
                val user = authorize(hello, challenge)
                active.put(user, owner)?.let { previous ->
                    previous.close()
                    handler.onEnded(user)
                }
                handler.onReady(user, owner)
                user
            }
        } catch (_: Exception) {
            return
        } finally {
            attempts.release()
        }
        try {
            session.send(Frame.Text(SignalCodec.encode(Signal(SignalType.READY, probeId))))
            coroutineScope {
                val writer = launch {
                    for (message in owner.outbound) session.send(Frame.Text(SignalCodec.encode(message)))
                }
                try {
                    while (true) {
                        val message = withTimeout(35_000) { session.readSignal() }
                        require(message.type != SignalType.AUTH && message.type != SignalType.CHALLENGE && message.type != SignalType.READY)
                        if (message.type == SignalType.PING) owner.send(Signal(SignalType.PONG, message.id))
                        else synchronized(leaseLock) {
                            if (active[number] === owner) handler.onSignal(number, message)
                        }
                    }
                } finally {
                    writer.cancel()
                }
            }
        } catch (_: Exception) {
            // Exiting the route closes inactive connections.
        } finally {
            synchronized(leaseLock) {
                if (active[number] === owner) {
                    active.remove(number)
                    handler.onEnded(number)
                }
            }
        }
    }

    private fun authorize(hello: Signal, challenge: String): String {
        require(hello.type == SignalType.AUTH)
        val parts = checkNotNull(hello.payload).split('.')
        require(parts.size == 3)
        val (number, id, signature) = parts
        require(number in userNumbers)
        val device = requireNotNull(registry.find(number))
        require(!device.revoked && Wire.equal(device.activationId, id))
        require(Wire.verified(publicKey(device.signing), activationProof(serverPin, number, id, challenge), Wire.decode(signature)))
        return number
    }

    private suspend fun WebSocketSession.readSignal(): Signal =
        SignalCodec.decode((incoming.receive() as Frame.Text).readText())

    override fun close() {
        synchronized(leaseLock) {
            if (!closed.compareAndSet(false, true)) return
            active.values.forEach { it.close() }
            active.clear()
        }
        listener.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }
}

internal class LocalClient(
    private val host: String, pin: String, private val port: Int = PORT, localNetwork: SocketFactory? = null,
) : SignalChannel {
    private val trust = pinnedTrust(pin)
    private val http = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .apply { if (localNetwork != null) socketFactory(localNetwork) }
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
    @Volatile private var failure: Throwable? = null
    private object Disconnected

    fun connect(activation: Activation, signing: PrivateKey): LocalClient {
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
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure = t
                    disconnected()
                }
            })
            socket = transport
            if (closed.get()) transport.cancel()
            check(!closed.get() && ready.await(5, TimeUnit.SECONDS) && upgraded)
            val challenge = receive(5)
            require(challenge.type == SignalType.CHALLENGE)
            val signature = Wire.encode(Wire.signature(signing, activation.proof(checkNotNull(challenge.payload))))
            send(Signal(SignalType.AUTH, probeId, "${activation.number}.${activation.id}.$signature"))
            check(receive(5) == Signal(SignalType.READY, probeId))
            return this
        } catch (error: Exception) {
            close()
            val status = when {
                upgraded -> "رفض الخادم تفعيل هذا الجهاز. راجع رقم المستخدم وحالة تفعيله."
                failure is SSLException -> "تعذّر التحقق من هوية الخادم. استخدم ملف تفعيل صادرًا من هذا الخادم."
                else -> "تعذّر الوصول إلى $host. تحقق من تشغيل الخادم وإمكانية الوصول بين الهاتفين."
            }
            throw IOException(status, failure ?: error)
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
