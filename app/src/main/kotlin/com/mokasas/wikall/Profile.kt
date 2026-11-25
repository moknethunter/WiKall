package com.mokasas.wikall

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val PORT = 8787

internal object Wire {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
    fun token(): String = encode(ByteArray(24).also(SecureRandom()::nextBytes))
    fun pin(key: PublicKey): String = MessageDigest.getInstance("SHA-256")
        .digest(key.encoded).joinToString("") { "%02X".format(it.toInt() and 255) }
    fun equal(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    fun signature(key: PrivateKey, body: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run {
        initSign(key)
        update(body)
        sign()
    }
    fun verified(key: PublicKey, body: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update(body)
            verify(signature)
        }
}

internal fun privateIpv4(address: String): Boolean {
    val parts = address.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { part ->
        part.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == part } ?: return false
    }
    val (first, second) = octets
    return first == 10 || first == 172 && second in 16..31 ||
        first == 192 && second == 168 || first == 169 && second == 254
}

internal data class Profile(val host: String, val token: String, val expires: Long, val key: PublicKey) {
    val pin: String get() = Wire.pin(key)
    fun export(privateKey: PrivateKey): ByteArray {
        val body = buildJsonObject {
            put("format", "mokasas-profile")
            put("protocolVersion", 2)
            put("serverId", pin)
            put("host", host)
            put("knownAddresses", buildJsonArray { add(JsonPrimitive(host)) })
            put("port", PORT)
            put("userNumber", "1001")
            put("pairingToken", token)
            put("validFromEpochMillis", expires - LIFETIME)
            put("expiresAtEpochMillis", expires)
            put("serverPublicKey", Wire.encode(key.encoded))
        }.toString().toByteArray()
        return buildJsonObject {
            put("profile", Wire.encode(body))
            put("signature", Wire.encode(Wire.signature(privateKey, body)))
        }.toString().toByteArray()
    }

    companion object {
        const val MAX_SIZE = 8192
        const val LIFETIME = 5 * 60_000L

        fun read(bytes: ByteArray, now: Long = System.currentTimeMillis()): Profile {
            require(bytes.size in 1..MAX_SIZE)
            val envelope = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            require(envelope.keys == setOf("profile", "signature"))
            val body = Wire.decode(envelope.value("profile"))
            val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
            val key = KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(Wire.decode(json.value("serverPublicKey")))
            )
            require(Wire.verified(key, body, Wire.decode(envelope.value("signature"))))
            val version = json.value("protocolVersion")
            require(json.value("format") == "mokasas-profile" && version in setOf("1", "2"))
            require(json.value("port") == PORT.toString() && json.value("userNumber") == "1001")
            val host = json.value("host")
            val token = json.value("pairingToken")
            val expires = json.value("expiresAtEpochMillis").toLong()
            if (version == "2") {
                val knownAddresses = json.getValue("knownAddresses").jsonArray
                val validFrom = json.value("validFromEpochMillis").toLong()
                require(json.value("serverId") == Wire.pin(key))
                require(knownAddresses.map { it.jsonPrimitive.content } == listOf(host))
                require(validFrom == expires - LIFETIME && validFrom <= now + 30_000)
            }
            require(privateIpv4(host) && Wire.decode(token).size == 24)
            require(expires > now && expires <= now + LIFETIME + 30_000) { "انتهت صلاحية الملف أو ساعة الهاتف تحتاج إلى ضبط" }
            return Profile(host, token, expires, key)
        }

        private fun JsonObject.value(name: String): String = requireNotNull(get(name)).jsonPrimitive.content
    }
}
