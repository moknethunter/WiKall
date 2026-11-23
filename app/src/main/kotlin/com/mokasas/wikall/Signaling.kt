package com.mokasas.wikall

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

internal enum class SignalType { PAIR, READY, PING, PONG, INVITE, ACCEPT, REJECT, BUSY, TIMEOUT, END }
internal data class Signal(val type: SignalType, val id: UUID, val token: String? = null)
internal val probeId: UUID = UUID(0, 0)

internal class SignalChannel(private val socket: SSLSocket) : AutoCloseable {
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)
    private val writeLock = Any()

    fun send(signal: Signal) = synchronized(writeLock) {
        val text = "1|${signal.type.name}|${signal.id}|${signal.token.orEmpty()}"
        val bytes = text.toByteArray()
        require(bytes.size <= 256)
        output.writeShort(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun receive(): Signal {
        val size = input.readUnsignedShort()
        require(size in 1..256)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val parts = bytes.decodeToString(throwOnInvalidSequence = true).split('|')
        require(parts.size == 4 && parts[0] == "1")
        val type = SignalType.valueOf(parts[1])
        val id = UUID.fromString(parts[2])
        require(id.toString() == parts[2])
        val token = parts[3].ifEmpty { null }
        if (type == SignalType.PAIR) {
            require(id == probeId && token != null && Wire.decode(token).size == 24)
        } else {
            require(token == null)
        }
        return Signal(type, id, token)
    }

    override fun close() = socket.close()
}

internal class LocalHost(context: SSLContext, address: String, port: Int = PORT) : AutoCloseable {
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

    fun serve(onReady: (SignalChannel, Signal) -> Unit, onEnded: () -> Unit) {
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
                        require(hello.type == SignalType.PAIR)
                        synchronized(leaseLock) {
                            val (token, expiration) = requireNotNull(invitation)
                            require(System.currentTimeMillis() < expiration && Wire.equal(token, requireNotNull(hello.token)))
                            channel.send(Signal(SignalType.READY, probeId))
                            invitation = null
                        }
                        authenticated = true
                        socket.soTimeout = 35_000
                        while (!closed.get()) {
                            val message = channel.receive()
                            if (message.type == SignalType.PING) channel.send(Signal(SignalType.PONG, message.id))
                            onReady(channel, message)
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

    fun connect(token: String): SignalChannel {
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        var phase = "الوصول إلى عنوان الخادم"
        try {
            socket.connect(InetSocketAddress(host, port), 4000)
            socket.soTimeout = 5000
            phase = "التحقق من هوية الخادم"
            socket.startHandshake()
            phase = "الاقتران بالخادم"
            channel = SignalChannel(socket)
            channel.send(Signal(SignalType.PAIR, probeId, token))
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
