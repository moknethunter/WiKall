package com.mokasas.wikall

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidActivationTest {
    @Test fun androidKeystoreStoresDeviceIdentityAndAuthenticatesAWebSocket() {
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
        val issued = Activation(lan, hostKey, assigned.number, assigned.serial, assigned.activationId)
        val bytes = issued.export(server.entry.privateKey, request.encryption)
        val unlocked = Activation.read(bytes, DeviceIdentity.load().decryption, request.serial)
        assertEquals(issued.number, unlocked.number)
        assertEquals(issued.pin, unlocked.pin)
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertTrue(runCatching { Activation.read(bytes, otherKey.private, request.serial) }.isFailure)

        LocalHost(server.tls(), lan, 0, store, issued.pin).use { host ->
            host.serve({ _, _ -> }, { _, _ -> }, {})
            LocalClient(lan, unlocked.pin, host.localPort).use { client ->
                client.connect(unlocked, device.signing)
                val id = UUID.randomUUID()
                client.send(Signal(SignalType.PING, id))
                assertEquals(Signal(SignalType.PONG, id), client.receive())
            }
        }
        store.save(SavedConnection(lan, unlocked, bytes))
        assertEquals(unlocked.number, store.saved(DeviceIdentity.load())?.activation?.number)
    }
}
