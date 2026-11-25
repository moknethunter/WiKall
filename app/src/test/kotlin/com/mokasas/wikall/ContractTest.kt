package com.mokasas.wikall

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.BeforeClass
import org.junit.AfterClass

class ContractTest {
    private val password = "fixture-only".toCharArray()
    private val store = KeyStore.getInstance("PKCS12").apply {
        fixture.inputStream().use { load(it, password) }
    }
    private val entry = store.getEntry("fixture", KeyStore.PasswordProtection(password)) as KeyStore.PrivateKeyEntry

    @Test fun encryptedActivationIsRestrictedToItsDevice() {
        val request = DeviceRequest(first.public, second.public)
        val submitted = DeviceRequest.read(request.export(first.private))
        assertEquals(request.serial, submitted.serial)
        assertThrows(IllegalArgumentException::class.java) {
            DeviceRequest.read(request.export(second.private))
        }
        val host = entry.certificate.publicKey
        val device = allocate(emptyList(), submitted)
        val issued = Activation("192.168.10.1", host, device.number, device.serial, device.activationId)
        val bytes = issued.export(entry.privateKey, submitted.encryption)
        assertFalse(bytes.decodeToString().contains(Wire.pin(host)))
        assertFalse(bytes.decodeToString().contains(device.serial))
        assertEquals(issued, Activation.read(bytes, second.private, device.serial))
        assertThrows(java.security.GeneralSecurityException::class.java) {
            Activation.read(bytes, first.private, device.serial)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Activation.read(bytes, second.private, Wire.pin(second.public))
        }
        val wrongHost = KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair().private
        assertThrows(IllegalArgumentException::class.java) {
            Activation.read(issued.export(wrongHost, submitted.encryption), second.private, device.serial)
        }
        val challenge = Wire.token()
        val response = Wire.signature(first.private, issued.proof(challenge))
        assertTrue(Wire.verified(first.public, issued.proof(challenge), response))
        assertFalse(Wire.verified(first.public, issued.proof(Wire.token()), response))
    }

    @Test fun sharedArchivesAcceptOnlyTheRequestedKindAndBoundedContents() {
        val bytes = DeviceRequest(first.public, second.public).export(first.private)
        val archive = packTransfer(Transfer.REQUEST, bytes)
        assertArrayEquals(bytes, unpackTransfer(Transfer.REQUEST, archive))
        assertThrows(IllegalArgumentException::class.java) { unpackTransfer(Transfer.ACTIVATION, archive) }
        assertThrows(IllegalArgumentException::class.java) { packTransfer(Transfer.REQUEST, ByteArray(FILE_LIMIT + 1)) }
    }

    @Test fun numbersAreStableAndLimitedToTenDistinctDevices() {
        val devices = mutableListOf<Enrollment>()
        for (index in 1..USER_LIMIT) {
            val request = DeviceRequest(keys().public, second.public)
            val issued = allocate(devices, request)
            assertEquals((99000 + index).toString(), issued.number)
            devices.add(issued)
            assertEquals(issued, allocate(devices, request))
        }
        assertThrows(CapacityReached::class.java) { allocate(devices, DeviceRequest(first.public, second.public)) }

        val request = DeviceRequest(first.public, second.public)
        val issued = allocate(emptyList(), request)
        val renewed = allocate(listOf(issued.copy(revoked = true)), request)
        assertEquals(issued.number, renewed.number)
        assertNotEquals(issued.activationId, renewed.activationId)
        assertFalse(renewed.revoked)
    }

    @Test fun consentAndTerminalSignalsAreIdempotent() {
        val caller = LocalCall()
        val receiver = LocalCall()
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
            val caller = LocalCall()
            val receiver = LocalCall()
            receiver.remote(requireNotNull(caller.dial()))
            val signal = requireNotNull(receiver.decide(decision))
            assertNull(receiver.decide(decision))
            caller.remote(signal)
            assertEquals(if (decision == SignalType.REJECT) CallState.REJECTED else CallState.MISSED, caller.state)
        }
    }

    @Test fun realSocketSupportsTwoIdentitiesAndRejectsCopiesAndRevokedNumbers() {
        val context = ServerIdentity(entry).tls()
        val pub = entry.certificate.publicKey
        val a = allocate(emptyList(), DeviceRequest(first.public, first.public))
        val b = allocate(listOf(a), DeviceRequest(second.public, second.public))
        val users = mutableMapOf(a.number to a, b.number to b)
        val registry = object : EnrollmentRegistry {
            override fun find(number: String) = users[number]
            override fun revoke(number: String) { users[number]?.let { users[number] = it.copy(revoked = true) } }
        }
        fun activation(user: Enrollment) = Activation("192.168.10.1", pub, user.number, user.serial, user.activationId)
        val seen = LinkedBlockingQueue<Pair<String, Signal>>()
        LocalHost(context, "127.0.0.1", 0, registry, Wire.pin(pub)).use { host ->
            host.serve({ _, _ -> }, { number, signal -> seen.add(number to signal) }, {})
            LocalClient("127.0.0.1", "F".repeat(64), host.localPort).use { client ->
                assertThrows(IOException::class.java) { client.connect(activation(a), first.private) }
            }
            LocalClient("127.0.0.1", Wire.pin(pub), host.localPort).use { client ->
                assertThrows(IOException::class.java) { client.connect(activation(a), second.private) }
            }
            LocalClient("127.0.0.1", Wire.pin(pub), host.localPort).use { firstClient ->
                LocalClient("127.0.0.1", Wire.pin(pub), host.localPort).use { secondClient ->
                    firstClient.connect(activation(a), first.private)
                    secondClient.connect(activation(b), second.private)
                    val call = Signal(SignalType.INVITE, UUID.randomUUID())
                    firstClient.send(call)
                    assertEquals(a.number to call, seen.poll(5, TimeUnit.SECONDS))
                    val ping = UUID.randomUUID()
                    secondClient.send(Signal(SignalType.PING, ping))
                    assertEquals(Signal(SignalType.PONG, ping), secondClient.receive())
                    host.revokeDevice(a.number)
                    val newPing = UUID.randomUUID()
                    secondClient.send(Signal(SignalType.PING, newPing))
                    assertEquals(Signal(SignalType.PONG, newPing), secondClient.receive())
                }
            }
            LocalClient("127.0.0.1", Wire.pin(pub), host.localPort).use { client ->
                assertThrows(IOException::class.java) { client.connect(activation(a), first.private) }
            }
        }
    }

    @Test fun mediaRequiresAnAcceptedInvitationAndOnlySupportsLanAudio() {
        val session = LocalCall()
        val invitation = requireNotNull(session.dial())
        session.media(CallState.CONNECTED)
        assertEquals(CallState.CALLING, session.state)
        session.remote(Signal(SignalType.ACCEPT, invitation.id))
        session.media(CallState.CONNECTING)
        assertEquals(CallState.CONNECTING, session.state)

        assertTrue(lanIce("candidate:1 1 udp 1 192.168.1.50 51500 typ host"))
        assertFalse(lanIce("candidate:1 1 udp 1 8.8.8.8 51500 typ host"))
        assertFalse(lanIce("candidate:1 1 udp 1 192.168.1.50 51500 typ relay"))
        assertTrue(audioOnly("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
        assertFalse(audioOnly("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96"))
    }

    companion object {
        private lateinit var directory: File
        private lateinit var fixture: File
        private lateinit var first: KeyPair
        private lateinit var second: KeyPair

        private fun keys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        @JvmStatic
        @BeforeClass
        fun createFixture() {
            directory = Files.createTempDirectory("wi-call-tests").toFile()
            fixture = File(directory, "fixture.p12")
            first = keys()
            second = keys()
            val keytool = File(System.getProperty("java.home"), "bin/keytool")
            val process = ProcessBuilder(
                keytool.path, "-genkeypair", "-noprompt", "-alias", "fixture", "-dname", "CN=Wi-Call Test",
                "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", fixture.path, "-storepass", "fixture-only",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { output }
        }

        @JvmStatic
        @AfterClass
        fun deleteFixture() {
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }
}
