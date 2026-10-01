package de.tobisk.inkdav.dav

import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerCertificateTrustTest {
    private val selfSigned = HeldCertificate.Builder().commonName("calendar.home.arpa").build()
    private val server = MockWebServer()

    @Before
    fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(selfSigned).build().sslSocketFactory(), false)
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    @Test
    fun `inspection reports the self-signed leaf fingerprint without trusting it`() {
        val certificate = inspectServerCertificate(server.url("/dav/").toString())

        assertEquals(certificateSha256(selfSigned.certificate), certificate.sha256)
        assertFalse(certificate.systemTrusted)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `pinned client accepts the trusted self-signed certificate despite a host name mismatch`() {
        server.enqueue(MockResponse().setResponseCode(207))
        val client = OkHttpClient().trustingCertificate(certificateSha256(selfSigned.certificate))

        client.newCall(Request.Builder().url(server.url("/dav/")).build()).execute().use { assertEquals(207, it.code) }
    }

    @Test
    fun `pinned client rejects a different certificate`() {
        val other = HeldCertificate.Builder().commonName("other").build()
        val client = OkHttpClient().trustingCertificate(certificateSha256(other.certificate))

        val error = runCatching { client.newCall(Request.Builder().url(server.url("/dav/")).build()).execute() }.exceptionOrNull()

        val related = generateSequence(error) { it.cause }.flatMap { sequenceOf(it) + it.suppressed.asSequence() }
        assertTrue(related.any { it is SSLHandshakeException })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `untrusted handshake failures explain how to trust the server`() {
        val error = SSLHandshakeException("handshake").apply { initCause(CertificateException("Trust anchor not found")) }

        assertTrue(describeSyncError(error).startsWith(UNTRUSTED_CERTIFICATE_ERROR))
        assertEquals("boom", describeSyncError(IllegalStateException("boom")))
    }

    @Test
    fun `pins survive only while the TLS endpoint stays the same`() {
        assertTrue(sameTlsEndpoint("https://Calendar.home.arpa/dav/", "https://calendar.home.arpa:443/tasks/"))
        assertFalse(sameTlsEndpoint("https://calendar.home.arpa/", "https://calendar.home.arpa:8443/"))
        assertFalse(sameTlsEndpoint("https://calendar.home.arpa/", "https://other.home.arpa/"))
    }
}
