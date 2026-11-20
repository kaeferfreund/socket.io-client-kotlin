package io.github.kaeferfreund.socketio.okhttp

import io.github.kaeferfreund.socketio.engineio.EngineHttpCallback
import io.github.kaeferfreund.socketio.engineio.EngineHttpRequest
import io.github.kaeferfreund.socketio.engineio.EngineHttpResponse
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** [TlsPolicy] against a TLS MockWebServer with a private CA. */
class TlsPolicyTest {
    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("test CA").build()
    private val leaf = HeldCertificate.Builder().signedBy(ca).addSubjectAlternativeName("localhost").commonName("localhost").build()
    private val otherCa = HeldCertificate.Builder().certificateAuthority(0).commonName("other CA").build()
    private val server = MockWebServer()
    private val loopback = InetAddress.getByName("127.0.0.1")

    @AfterEach
    fun stop() = server.close()

    private fun startHttps(requireClientAuth: Boolean = false) {
        val certificates =
            HandshakeCertificates.Builder()
                .heldCertificate(leaf, ca.certificate)
                .addTrustedCertificate(ca.certificate)
                .build()
        server.useHttps(certificates.sslSocketFactory())
        if (requireClientAuth) server.requireClientAuth()
        server.start(loopback, 0)
    }

    private fun call(
        policy: TlsPolicy,
        base: OkHttpClient.Builder = OkHttpClient.Builder(),
    ): Result<EngineHttpResponse> {
        server.enqueue(MockResponse.Builder().body("ok").build())
        // "localhost" must reach the server on its only address. Where it also resolves to ::1,
        // OkHttp's fast fallback would report the refused IPv6 route instead of the TLS failure.
        val clients = OkHttpEngineClients(policy.applyTo(base).dns { listOf(loopback) }.build())
        val future = CompletableFuture<Result<EngineHttpResponse>>()
        val url = server.url("/socket.io/").newBuilder().host("localhost").build().toString()
        clients.execute(
            EngineHttpRequest("GET", url, emptyList(), null, null),
            object : EngineHttpCallback {
                override fun onResponse(response: EngineHttpResponse) {
                    future.complete(Result.success(response))
                }

                override fun onFailure(error: Throwable) {
                    future.complete(Result.failure(error))
                }
            },
        )
        return future.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun trustsAPrivateCa() {
        startHttps()
        assertEquals("ok", call(TlsPolicy.customTrust(listOf(ca.certificate))).getOrThrow().body)
    }

    @Test
    fun rejectsAServerOutsideTheCustomTrust() {
        startHttps()
        assertTrue(call(TlsPolicy.customTrust(listOf(otherCa.certificate))).exceptionOrNull() is SSLException)
    }

    @Test
    fun theSystemTrustStoreDoesNotTrustAPrivateCa() {
        startHttps()
        assertTrue(call(TlsPolicy.systemDefault()).exceptionOrNull() is SSLException)
    }

    @Test
    fun acceptsAMatchingPinAndRejectsAMismatch() {
        startHttps()
        val trust = TlsPolicy.customTrust(listOf(ca.certificate))
        assertEquals("ok", call(trust.withPinnedCertificates("localhost", listOf(leaf.certificate))).getOrThrow().body)
        val wrong = trust.withPins("localhost", CertificatePinner.pin(otherCa.certificate))
        assertTrue(call(wrong).exceptionOrNull() is SSLException)
    }

    @Test
    fun presentsAClientCertificateForMutualTls() {
        startHttps(requireClientAuth = true)
        val client = HeldCertificate.Builder().signedBy(ca).commonName("client").build()
        val trust = TlsPolicy.customTrust(listOf(ca.certificate))
        assertTrue(call(trust).isFailure)
        val keyManager = HandshakeCertificates.Builder().heldCertificate(client, ca.certificate).build().keyManager
        assertEquals("ok", call(trust.withClientCertificate(keyManager)).getOrThrow().body)
    }

    // Adding a client certificate must not replace the trust store: without customTrust the
    // system store still decides, so a server from a private CA stays untrusted.
    @Test
    fun aClientCertificateAloneKeepsTheSystemTrust() {
        startHttps()
        val client = HeldCertificate.Builder().signedBy(ca).commonName("client").build()
        val keyManager = HandshakeCertificates.Builder().heldCertificate(client, ca.certificate).build().keyManager
        assertTrue(call(TlsPolicy.systemDefault().withClientCertificate(keyManager)).exceptionOrNull() is SSLException)
    }

    /** A base client that trusts only [anchor], as an application configures it before handing it over. */
    private fun baseTrusting(anchor: HeldCertificate): OkHttpClient.Builder {
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(anchor.certificate).build()
        return OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
    }

    private fun clientKeyManager() =
        HandshakeCertificates.Builder()
            .heldCertificate(HeldCertificate.Builder().signedBy(ca).commonName("client").build(), ca.certificate)
            .build()
            .keyManager

    // A client certificate without customTrust keeps the trust of the client it is applied to:
    // the base client's private CA, not the system store.
    @Test
    fun aClientCertificateKeepsTheTrustOfTheBaseClient() {
        startHttps(requireClientAuth = true)
        assertEquals("ok", call(TlsPolicy.systemDefault().withClientCertificate(clientKeyManager()), baseTrusting(ca)).getOrThrow().body)
    }

    @Test
    fun aClientCertificateDoesNotWidenTheTrustOfTheBaseClient() {
        startHttps(requireClientAuth = true)
        val result = call(TlsPolicy.systemDefault().withClientCertificate(clientKeyManager()), baseTrusting(otherCa))
        assertTrue(result.exceptionOrNull() is SSLException)
    }

    // Policy pins for one host must not drop the base client's pins for another.
    @Test
    fun policyPinsKeepThePinsOfTheBaseClient() {
        startHttps()
        val base =
            baseTrusting(ca).certificatePinner(
                CertificatePinner.Builder().add("localhost", CertificatePinner.pin(otherCa.certificate)).build(),
            )
        val result = call(TlsPolicy.pinned("api.example.com", CertificatePinner.pin(leaf.certificate)), base)
        assertTrue(result.exceptionOrNull() is SSLPeerUnverifiedException)
    }

    @Test
    fun policyPinsAreAddedToThePinnerOfTheBaseClient() {
        val basePin = CertificatePinner.pin(otherCa.certificate)
        val base = OkHttpClient.Builder().certificatePinner(CertificatePinner.Builder().add("**.example.com", basePin).build())
        val pins = TlsPolicy.pinned("api.example.com", CertificatePinner.pin(leaf.certificate)).applyTo(base).build().certificatePinner.pins
        assertEquals(
            setOf("**.example.com" to basePin, "api.example.com" to CertificatePinner.pin(leaf.certificate)),
            pins.map { it.pattern to it.toString() }.toSet(),
        )
    }

    // The default base client: nothing to keep, so the policy's own pinner and the platform trust apply as before.
    @Test
    fun aDefaultBaseClientGetsThePolicyAsBefore() {
        val pin = CertificatePinner.pin(leaf.certificate)
        val client = TlsPolicy.pinned("localhost", pin).withClientCertificate(clientKeyManager()).applyTo(OkHttpClient.Builder()).build()
        assertEquals(listOf("localhost" to pin), client.certificatePinner.pins.map { it.pattern to it.toString() })
        assertEquals(OkHttpClient().x509TrustManager!!.javaClass, client.x509TrustManager!!.javaClass)
    }

    @Test
    fun withPinsAddsBackupPinsToAHost() {
        val policy = TlsPolicy.pinned("api.example.com", "sha256/PRIMARY").withPins("api.example.com", "sha256/BACKUP", "sha256/PRIMARY")
        assertEquals(listOf("sha256/PRIMARY", "sha256/BACKUP"), policy.pins["api.example.com"])
        startHttps()
        val trust = TlsPolicy.customTrust(listOf(ca.certificate))
        // The live key first, then a backup for a later rotation: the live key must still be accepted.
        val withBackup = trust.withPinnedCertificates("localhost", listOf(leaf.certificate)).withPins("localhost", CertificatePinner.pin(otherCa.certificate))
        assertEquals("ok", call(withBackup).getOrThrow().body)
    }

    @Test
    fun validatesItsArguments() {
        assertThrows<IllegalArgumentException> { TlsPolicy.customTrust(emptyList()) }
        assertThrows<IllegalArgumentException> { TlsPolicy.pinned("example.com") }
        assertThrows<IllegalArgumentException> { TlsPolicy.pinned("example.com", "md5/abc") }
        assertEquals(1, TlsPolicy.certificates(ca.certificatePem().encodeToByteArray()).size)
    }
}
