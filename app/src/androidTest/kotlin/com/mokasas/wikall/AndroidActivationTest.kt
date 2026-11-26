package com.mokasas.wikall

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidActivationTest {
    @Test fun androidKeystoreStoresDeviceIdentityAndAuthenticatesATlsSocket() {
        val server = ServerIdentity.load()
        val device = DeviceIdentity.load()
        val request = DeviceRequest.read(device.request.export(device.signing))
        val store = CallStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val assigned = store.issue(request)
        assertEquals(assigned.number, store.issue(request).number)

        val hostKey = server.entry.certificate.publicKey
        val address = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }.firstOrNull(::privateIpv4)
        assertNotNull("The emulator must expose a private IPv4 address", address)
        val lan = checkNotNull(address)
        val oldAddress = if (lan == "192.168.10.1") "192.168.11.1" else "192.168.10.1"
        val issued = Activation(oldAddress, hostKey, assigned.number, assigned.serial, assigned.activationId)
        val bytes = issued.export(server.entry.privateKey, request.encryption)
        val unlocked = Activation.read(bytes, DeviceIdentity.load().decryption, request.serial)
        assertEquals(issued.number, unlocked.number)
        assertEquals(issued.pin, unlocked.pin)
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertTrue(runCatching { Activation.read(bytes, otherKey.private, request.serial) }.isFailure)

        val handshakeFailure = AtomicReference<Throwable>()
        val reported = CountDownLatch(1)
        LocalHost(server.tls(), lan, 0, store, issued.pin, onHandshakeFailure = { error ->
            handshakeFailure.compareAndSet(null, error)
            reported.countDown()
        }).use { host ->
            host.serve({ _, _ -> }, { _, _ -> }, {})
            LocalClient(lan, unlocked.pin, host.localPort).use { client ->
                try {
                    client.connect(unlocked, device.signing)
                } catch (error: java.io.IOException) {
                    reported.await(2, TimeUnit.SECONDS)
                    val serverError = handshakeFailure.get()?.stackTraceToString().orEmpty()
                    throw AssertionError("Android TLS server outcome:\n$serverError", error)
                }
                val id = UUID.randomUUID()
                client.send(Signal(SignalType.PING, id))
                assertEquals(Signal(SignalType.PONG, id), client.receive())
            }
        }
        store.save(SavedConnection(lan, unlocked, bytes))
        val saved = checkNotNull(store.saved(DeviceIdentity.load()))
        assertEquals(unlocked.number, saved.activation.number)
        assertEquals(lan, saved.address)
        assertEquals(oldAddress, saved.activation.address)
    }
    @Test fun androidWebRtcNegotiatesAnAudioSessionOnLocalUdp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)

        val connected = CountDownLatch(2)
        val failed = AtomicBoolean()
        val callerReady = AtomicBoolean()
        val receiverReady = AtomicBoolean()
        lateinit var caller: VoiceMedia
        lateinit var receiver: VoiceMedia
        fun observeOnce(state: CallState, ready: AtomicBoolean) {
            if (state == CallState.CONNECTED && ready.compareAndSet(false, true)) connected.countDown()
        }
        fun fail() {
            failed.set(true)
            while (connected.count > 0) connected.countDown()
        }
        try {
            instrumentation.runOnMainSync {
                caller = VoiceMedia(context, { type, data -> receiver.receive(type, data) },
                    { state -> observeOnce(state, callerReady) }, ::fail)
                receiver = VoiceMedia(context, { type, data -> caller.receive(type, data) },
                    { state -> observeOnce(state, receiverReady) }, ::fail)
                receiver.start(outgoing = false)
                caller.start(outgoing = true)
            }

            assertTrue("Both WebRTC audio peers must negotiate local UDP", connected.await(25, TimeUnit.SECONDS))
            assertTrue("WebRTC peer setup failed", !failed.get())
        } finally {
            instrumentation.runOnMainSync {
                runCatching { caller.close() }
                runCatching { receiver.close() }
            }
        }
    }
}
