package com.mokasas.wikall

import java.io.DataInputStream
import java.security.MessageDigest
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

internal enum class SignalType {
    PAIR, REJOIN, READY, PING, PONG, INVITE, ACCEPT, REJECT, BUSY, TIMEOUT, END, OFFER, ANSWER, ICE
}
internal data class Signal(val type: SignalType, val id: UUID, val payload: String? = null)
internal val probeId: UUID = UUID(0, 0)

internal class SignalChannel(private val socket: SSLSocket) : AutoCloseable {
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)
    private val writeLock = Any()

    fun send(signal: Signal) = synchronized(writeLock) {
        val payload = signal.payload?.let { Wire.encode(it.encodeToByteArray()) }.orEmpty()
        val text = "2|${signal.type.name}|${signal.id}|$payload"
        val bytes = text.toByteArray()
        require(bytes.size <= MAX_SIGNAL_SIZE)
        output.writeShort(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun receive(): Signal {
        val size = input.readUnsignedShort()
        require(size in 1..MAX_SIGNAL_SIZE)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val parts = bytes.decodeToString(throwOnInvalidSequence = true).split('|')
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

    private companion object {
        const val MAX_SIGNAL_SIZE = 16 * 1024
    }

    override fun close() = socket.close()
}

private fun validCredential(value: String): Boolean =
    value.length == 32 && Wire.decode(value).size == 24

internal fun credentialDigest(credential: String): String =
    Wire.encode(MessageDigest.getInstance("SHA-256").digest(credential.encodeToByteArray()))

internal interface PairedDevice {
    fun digest(): String?
    fun remember(digest: String)
    fun revoke()
}

internal class LocalHost(
    context: SSLContext,
    address: String,
    port: Int = PORT,
    private val pairedDevice: PairedDevice,
) : AutoCloseable {
    private val listener = (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        bind(InetSocketAddress(address, port), 2)
    }
    private val leaseLock = Any()
    private val closed = AtomicBoolean()
    private val running = AtomicBoolean()
    private var invitation: Pair<String, Long>? = null
    private var activeSocket: SSLSocket? = null
    val localPort: Int get() = listener.localPort

    fun setInvitation(token: String, expires: Long) = synchronized(leaseLock) {
        check(!closed.get())
        invitation = token to expires
    }

    fun revokeDevice() = synchronized(leaseLock) {
        pairedDevice.revoke()
        invitation = null
        runCatching { activeSocket?.close() }
    }

    fun serve(onReady: (SignalChannel) -> Unit, onSignal: (Signal) -> Unit, onEnded: () -> Unit) {
        check(running.compareAndSet(false, true))
        thread(name = "wikall-listener", isDaemon = true) {
            while (!closed.get()) {
                val socket = try { listener.accept() as SSLSocket } catch (_: java.io.IOException) { break }
                synchronized(leaseLock) {
                    if (closed.get()) {
                        socket.close()
                        return@thread
                    }
                    activeSocket = socket
                }
                var authenticated = false
                try {
                    socket.use {
                        socket.soTimeout = 5000
                        socket.startHandshake()
                        val channel = SignalChannel(socket)
                        val hello = channel.receive()
                        synchronized(leaseLock) {
                            check(!closed.get())
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
                            channel.send(Signal(SignalType.READY, probeId))
                        }
                        authenticated = true
                        onReady(channel)
                        socket.soTimeout = 35_000
                        while (!closed.get()) {
                            val message = channel.receive()
                            require(message.type !in setOf(SignalType.PAIR, SignalType.REJOIN, SignalType.READY))
                            if (message.type == SignalType.PING) channel.send(Signal(SignalType.PONG, message.id))
                            else onSignal(message)
                        }
                    }
                } catch (_: Exception) {
                    if (!closed.get() && !authenticated) {
                        try { Thread.sleep(350) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                } finally {
                    synchronized(leaseLock) { activeSocket = null }
                    if (authenticated) onEnded()
                }
            }
        }
    }

    override fun close() {
        synchronized(leaseLock) {
            if (!closed.compareAndSet(false, true)) return
            invitation = null
            runCatching { activeSocket?.close() }
            listener.close()
        }
    }
}

internal class LocalClient(private val host: String, private val pin: String, private val port: Int = PORT) : AutoCloseable {
    private lateinit var channel: SignalChannel
    private val socket = pinnedTls(pin).socketFactory.createSocket() as SSLSocket

    fun connect(token: String?, credential: String): SignalChannel {
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        var phase = "الوصول إلى عنوان الخادم"
        try {
            socket.connect(InetSocketAddress(host, port), 4000)
            socket.soTimeout = 5000
            phase = "التحقق من هوية الخادم"
            socket.startHandshake()
            phase = "الاقتران بالخادم"
            channel = SignalChannel(socket)
            val hello = if (token == null) Signal(SignalType.REJOIN, probeId, credential)
                else Signal(SignalType.PAIR, probeId, "$token.$credential")
            channel.send(hello)
            check(channel.receive() == Signal(SignalType.READY, probeId))
            socket.soTimeout = 25_000
            return channel
        } catch (error: Exception) {
            val detail = if (error is SocketTimeoutException) "انتهت المهلة في مرحلة" else "تعذّر إكمال مرحلة"
            throw java.io.IOException("$detail $phase. راجع عنوان الشبكة والبصمة وصلاحية الملف.", error)
        }
    }

    override fun close() = socket.close()
}
