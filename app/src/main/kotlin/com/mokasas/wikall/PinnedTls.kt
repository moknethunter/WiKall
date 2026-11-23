package com.mokasas.wikall

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal fun pinnedTls(pin: String): SSLContext = SSLContext.getInstance("TLS").apply {
    val verifier = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not used")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")
            if (!Wire.equal(pin, Wire.pin(leaf.publicKey))) throw CertificateException("MokAsas fingerprint mismatch")
            leaf.checkValidity()
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    init(null, arrayOf(verifier), null)
}
