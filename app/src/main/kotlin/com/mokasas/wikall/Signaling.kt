package com.mokasas.wikall

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

internal enum class SignalType {
    CHALLENGE, AUTH, READY, PING, PONG, INVITE, ACCEPT, REJECT, BUSY, TIMEOUT, END, OFFER, ANSWER, ICE
}

internal data class Signal(val type: SignalType, val id: UUID, val payload: String? = null)
internal val probeId: UUID = UUID(0, 0)
private const val MAX_SIGNAL_SIZE = 16 * 1024

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

private class FramedSocket(private val socket: SSLSocket) : SignalChannel {
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)

    @Synchronized
    override fun send(signal: Signal) {
        val bytes = SignalCodec.encode(signal).encodeToByteArray()
        output.writeShort(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun receive(): Signal {
        val size = input.readUnsignedShort()
        require(size in 1..MAX_SIGNAL_SIZE)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return SignalCodec.decode(bytes.decodeToString(throwOnInvalidSequence = true))
    }

    override fun close() = socket.close()
}

internal class LocalHost(
    context: SSLContext,
    address: String,
    listenPort: Int = PORT,
    private val registry: EnrollmentRegistry,
    private val serverPin: String,
    private val onHandshakeFailure: ((Throwable) -> Unit)? = null,
) : AutoCloseable {
    private class Callbacks(
        val onReady: (String, SignalChannel) -> Unit,
        val onSignal: (String, Signal) -> Unit,
        val onEnded: (String) -> Unit,
    )

    private val leaseLock = Any()
    private val attempts = Semaphore(3)
    private val closed = AtomicBoolean()
    private val serving = AtomicBoolean()
    private val active = mutableMapOf<String, FramedSocket>()
    private val accepted = mutableSetOf<SSLSocket>()
    @Volatile private var callbacks: Callbacks? = null
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "wi-call-handshake-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val workers = ThreadPoolExecutor(
        0, USER_LIMIT + 4, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "wi-call-peer").apply { isDaemon = true } },
    )

    private val listener = (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
        try {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            reuseAddress = true
            bind(InetSocketAddress(address, listenPort), 8)
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    val localPort: Int get() = listener.localPort

    fun revokeDevice(number: String) {
        val peer = synchronized(leaseLock) {
            registry.revoke(number)
            active.remove(number)?.also { callbacks?.onEnded?.invoke(number) }
        }
        runCatching { peer?.close() }
    }

    fun serve(
        onReady: (String, SignalChannel) -> Unit,
        onSignal: (String, Signal) -> Unit,
        onEnded: (String) -> Unit,
    ) {
        check(serving.compareAndSet(false, true))
        val handler = Callbacks(onReady, onSignal, onEnded)
        callbacks = handler
        thread(name = "wi-call-listener", isDaemon = true) {
            while (!closed.get()) {
                val socket = try {
                    listener.accept() as SSLSocket
                } catch (_: IOException) {
                    return@thread
                }
                if (!attempts.tryAcquire()) {
                    runCatching(socket::close)
                    continue
                }
                synchronized(leaseLock) {
                    if (closed.get()) {
                        attempts.release()
                        runCatching(socket::close)
                        return@thread
                    }
                    accepted.add(socket)
                }
                try {
                    workers.execute { servePeer(socket, handler) }
                } catch (_: RejectedExecutionException) {
                    synchronized(leaseLock) { accepted.remove(socket) }
                    attempts.release()
                    runCatching(socket::close)
                }
            }
        }
    }

    private fun servePeer(socket: SSLSocket, handler: Callbacks) {
        var handshaken = false
        var hasPermit = true
        var deadline: java.util.concurrent.ScheduledFuture<*>? = null
        var owner: FramedSocket? = null
        var number: String? = null
        try {
            deadline = deadlines.schedule({ runCatching(socket::close) }, 10, TimeUnit.SECONDS)
            socket.soTimeout = 8_000
            socket.startHandshake()
            handshaken = true
            val channel = FramedSocket(socket)
            owner = channel
            val challenge = Wire.token()
            channel.send(Signal(SignalType.CHALLENGE, probeId, challenge))
            val hello = channel.receive()
            val user = synchronized(leaseLock) {
                check(!closed.get())
                val assigned = authorize(hello, challenge)
                number = assigned
                channel.send(Signal(SignalType.READY, probeId))
                active.put(assigned, channel)?.let { previous ->
                    runCatching(previous::close)
                    handler.onEnded(assigned)
                }
                handler.onReady(assigned, channel)
                assigned
            }
            deadline.cancel(false)
            attempts.release()
            hasPermit = false
            socket.soTimeout = 35_000
            while (!closed.get()) {
                val message = channel.receive()
                require(message.type != SignalType.AUTH && message.type != SignalType.CHALLENGE && message.type != SignalType.READY)
                if (message.type == SignalType.PING) channel.send(Signal(SignalType.PONG, message.id))
                else synchronized(leaseLock) {
                    if (active[user] === channel) handler.onSignal(user, message)
                }
            }
        } catch (failure: Exception) {
            if (!handshaken) onHandshakeFailure?.invoke(failure)
        } catch (failure: Error) {
            if (!handshaken) onHandshakeFailure?.invoke(failure)
            throw failure
        } finally {
            deadline?.cancel(false)
            if (hasPermit) attempts.release()
            runCatching(socket::close)
            synchronized(leaseLock) {
                accepted.remove(socket)
                val user = number
                if (user != null && owner != null && active[user] === owner) {
                    active.remove(user)
                    handler.onEnded(user)
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

    override fun close() {
        val sockets = synchronized(leaseLock) {
            if (!closed.compareAndSet(false, true)) return
            active.clear()
            accepted.toList()
        }
        runCatching(listener::close)
        sockets.forEach { runCatching(it::close) }
        workers.shutdown()
        deadlines.shutdownNow()
    }
}

internal class LocalClient(
    private val host: String,
    pin: String,
    private val port: Int = PORT,
    localNetwork: SocketFactory? = null,
) : SignalChannel {
    private val context = pinnedTls(pinnedTrust(pin))
    private val raw: Socket = localNetwork?.createSocket() ?: Socket()
    private val closed = AtomicBoolean()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var channel: FramedSocket? = null

    fun connect(activation: Activation, signing: PrivateKey): LocalClient {
        var handshaken = false
        try {
            check(!closed.get())
            raw.connect(InetSocketAddress(host, port), 4_000)
            val encrypted = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket = encrypted
            check(!closed.get())
            encrypted.soTimeout = 8_000
            encrypted.enabledProtocols = encrypted.supportedProtocols
                .filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            encrypted.startHandshake()
            handshaken = true
            val framed = FramedSocket(encrypted)
            channel = framed
            val challenge = framed.receive()
            require(challenge.type == SignalType.CHALLENGE)
            val signature = Wire.encode(Wire.signature(signing, activation.proof(checkNotNull(challenge.payload))))
            framed.send(Signal(SignalType.AUTH, probeId, "${activation.number}.${activation.id}.$signature"))
            check(framed.receive() == Signal(SignalType.READY, probeId))
            encrypted.soTimeout = 25_000
            return this
        } catch (error: Exception) {
            close()
            val status = when {
                handshaken -> "رفض الخادم تفعيل هذا الجهاز. راجع رقم المستخدم وحالة تفعيله."
                error is SSLException -> "تعذّر التحقق من هوية الخادم. استخدم ملف تفعيل صادرًا من هذا الخادم."
                else -> "تعذّر الوصول إلى $host. تحقق من تشغيل الخادم وإمكانية الوصول بين الهاتفين."
            }
            throw IOException(status, error)
        }
    }

    override fun send(signal: Signal) = checkNotNull(channel).send(signal)

    fun receive(): Signal = checkNotNull(channel).receive()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        runCatching(raw::close)
    }
}
