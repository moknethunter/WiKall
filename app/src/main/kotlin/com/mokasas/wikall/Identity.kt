package com.mokasas.wikall

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager

internal class ServerIdentity(val entry: KeyStore.PrivateKeyEntry) {
    fun tls(): SSLContext = SSLContext.getInstance("TLS").apply {
        val manager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
                if (keyType == "RSA") arrayOf(ALIAS) else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?) =
                if (keyType == "RSA") ALIAS else null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == ALIAS) entry.certificateChain.map { it as X509Certificate }.toTypedArray() else null
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) entry.privateKey else null
        }
        init(arrayOf<KeyManager>(manager), null, null)
    }

    companion object {
        private const val ALIAS = "wikall-server-v1"
        fun load(): ServerIdentity {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                        .build())
                }.generateKeyPair()
            }
            return ServerIdentity(store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry)
        }
    }
}
