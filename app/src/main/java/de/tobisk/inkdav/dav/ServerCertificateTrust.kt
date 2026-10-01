package de.tobisk.inkdav.dav

import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** What a server presented during an unauthenticated TLS handshake, shown to the user before trusting it. */
data class ServerCertificate(
    val host: String,
    val subject: String,
    val issuer: String,
    val notAfter: Instant,
    val sha256: String,
    val systemTrusted: Boolean
)

internal fun certificateSha256(certificate: Certificate): String = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

/**
 * Accepts the exact leaf certificate the user trusted, and otherwise falls back to the platform trust store,
 * so a later switch to a publicly trusted certificate keeps working.
 */
internal class PinnedCertificateTrustManager(
    private val pinnedSha256: String,
    private val platform: X509TrustManager = platformTrustManager()
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = platform.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.firstOrNull()?.let(::certificateSha256) == pinnedSha256) return
        platform.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}

/** Self-signed homelab certificates often lack a matching SAN, so the trusted leaf also satisfies hostname checks. */
internal class PinnedHostnameVerifier(private val pinnedSha256: String, private val fallback: HostnameVerifier) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        val leaf = runCatching { session.peerCertificates.firstOrNull() }.getOrNull()
        return leaf?.let(::certificateSha256) == pinnedSha256 || fallback.verify(hostname, session)
    }
}

internal fun OkHttpClient.trustingCertificate(sha256: String): OkHttpClient {
    val trustManager = PinnedCertificateTrustManager(sha256)
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    return newBuilder()
        .sslSocketFactory(context.socketFactory, trustManager)
        .hostnameVerifier(PinnedHostnameVerifier(sha256, hostnameVerifier))
        .build()
}

/**
 * Opens a TLS connection without sending any HTTP request or credentials and reports the leaf certificate.
 * Must run off the main thread.
 */
fun inspectServerCertificate(url: String, timeoutMillis: Int = 15_000): ServerCertificate {
    val uri = URI(url.trim())
    require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS servers can be trusted." }
    val host = requireNotNull(uri.host) { "The URL has no host name." }
    val port = if (uri.port > 0) uri.port else 443
    val capture = CapturingTrustManager()
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
    (context.socketFactory.createSocket() as SSLSocket).use { socket ->
        socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
        socket.soTimeout = timeoutMillis
        socket.startHandshake()
    }
    val chain = capture.chain ?: throw CertificateException("The server did not present a certificate.")
    val leaf = chain.first()
    val systemTrusted = runCatching { platformTrustManager().checkServerTrusted(chain, capture.authType) }.isSuccess
    return ServerCertificate(
        host = host,
        subject = leaf.subjectX500Principal.name,
        issuer = leaf.issuerX500Principal.name,
        notAfter = leaf.notAfter.toInstant(),
        sha256 = certificateSha256(leaf),
        systemTrusted = systemTrusted
    )
}

private class CapturingTrustManager : X509TrustManager {
    var chain: Array<X509Certificate>? = null
    var authType: String = "RSA"

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("The server did not present a certificate.")
        @Suppress("UNCHECKED_CAST")
        this.chain = chain as Array<X509Certificate>
        this.authType = authType
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun platformTrustManager(): X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    .apply { init(null as KeyStore?) }
    .trustManagers
    .filterIsInstance<X509TrustManager>()
    .first()

/** Whether a stored pin still applies after an account's URL changes. */
internal fun sameTlsEndpoint(first: String, second: String): Boolean = runCatching {
    val a = URI(first.trim())
    val b = URI(second.trim())
    a.host.equals(b.host, ignoreCase = true) && (a.port.takeIf { it > 0 } ?: 443) == (b.port.takeIf { it > 0 } ?: 443)
}.getOrDefault(false)

internal const val UNTRUSTED_CERTIFICATE_ERROR = "The server certificate"

/** Turns TLS trust failures into guidance; other errors keep their own message. */
internal fun describeSyncError(error: Throwable): String = when {
    error is SSLHandshakeException && generateSequence(error as Throwable) { it.cause }.any { it is CertificateException } ->
        "$UNTRUSTED_CERTIFICATE_ERROR is not trusted. If it is self-signed, trust it in the server settings."
    error is SSLPeerUnverifiedException ->
        "$UNTRUSTED_CERTIFICATE_ERROR does not match its host name. If it is self-signed, trust it in the server settings."
    else -> error.message ?: error::class.simpleName ?: "Unknown error"
}
