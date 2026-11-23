package com.mokasas.wikall

import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.KeyManagerFactory
import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    private val password = "fixture-only".toCharArray()
    private val store = KeyStore.getInstance("PKCS12").apply {
        File("build/jvm/fixture.p12").inputStream().use { load(it, password) }
    }
    private val entry = store.getEntry("fixture", KeyStore.PasswordProtection(password)) as KeyStore.PrivateKeyEntry

    @Test fun signedProfileRejectsTamperingAndExpiration() {
        val now = System.currentTimeMillis()
        val profile = Profile("192.168.10.1", Wire.token(), now + 20_000, entry.certificate.publicKey)
        val encoded = profile.export(entry.privateKey)
        assertEquals(profile.pin, Profile.read(encoded, now).pin)
        assertThrows(IllegalArgumentException::class.java) { Profile.read(encoded, now + 21_000) }
        val modified = encoded.copyOf()
        val firstSignatureByte = encoded.decodeToString().indexOf("\"signature\":\"") + 13
        modified[firstSignatureByte] = if (modified[firstSignatureByte] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { Profile.read(modified, now) }
        assertFalse(privateIpv4("1.1.1.1"))
        assertFalse(privateIpv4("192.168.0.01"))
    }

    @Test fun consentAndTerminalSignalsAreIdempotent() {
        val caller = TestCall()
        val receiver = TestCall()
        val invitation = requireNotNull(caller.dial())
        receiver.remote(invitation)
        assertEquals(CallState.RINGING, receiver.state)
        assertEquals(CallState.CALLING, caller.state)
        val other = UUID.randomUUID()
        assertEquals(Signal(SignalType.BUSY, other), receiver.remote(Signal(SignalType.INVITE, other)))
        val accept = requireNotNull(receiver.decide(SignalType.ACCEPT))
        assertNull(receiver.decide(SignalType.ACCEPT))
        caller.remote(accept)
        caller.remote(accept)
        assertEquals(CallState.ACCEPTED, caller.state)
        val end = requireNotNull(receiver.decide(SignalType.END))
        assertNull(receiver.decide(SignalType.END))
        caller.remote(end)
        caller.remote(end)
        receiver.remote(invitation)
        assertEquals(CallState.ENDED, receiver.state)
        assertEquals(CallState.ENDED, caller.state)
    }

    @Test fun rejectsAndTimesOut() {
        for (decision in listOf(SignalType.REJECT, SignalType.TIMEOUT)) {
            val caller = TestCall()
            val receiver = TestCall()
            receiver.remote(requireNotNull(caller.dial()))
            val signal = requireNotNull(receiver.decide(decision))
            assertNull(receiver.decide(decision))
            caller.remote(signal)
            assertEquals(if (decision == SignalType.REJECT) CallState.REJECTED else CallState.MISSED, caller.state)
        }
    }

    @Test fun tlsChecksServerKeyAndConsumesPairingToken() {
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        manager.init(store, password)
        val context = SSLContext.getInstance("TLS")
        context.init(manager.keyManagers, null, null)
        LocalHost(context, "127.0.0.1", 0).use { host ->
            val token = Wire.token()
            val pin = Wire.pin(entry.certificate.publicKey)
            host.setInvitation(token, System.currentTimeMillis() + 30_000)
            host.serve({ _, _ -> }, {})
            LocalClient("127.0.0.1", "F".repeat(64), host.localPort).use { client ->
                assertThrows(java.io.IOException::class.java) { client.connect(token) }
            }
            LocalClient("127.0.0.1", pin, host.localPort).use { client ->
                assertThrows(java.io.IOException::class.java) { client.connect(Wire.token()) }
            }
            LocalClient("127.0.0.1", pin, host.localPort).use { client ->
                val channel = client.connect(token)
                val id = UUID.randomUUID()
                channel.send(Signal(SignalType.PING, id))
                assertEquals(Signal(SignalType.PONG, id), channel.receive())
            }
            LocalClient("127.0.0.1", pin, host.localPort).use { client ->
                assertThrows(java.io.IOException::class.java) { client.connect(token) }
            }
        }
    }
}
