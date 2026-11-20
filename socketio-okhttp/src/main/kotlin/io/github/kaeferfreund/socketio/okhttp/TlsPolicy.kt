package io.github.kaeferfreund.socketio.okhttp

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * How the client authenticates the server, and optionally itself: the
 * counterpart of Node's `ca`/`cert`/`key`/`pfx`/`rejectUnauthorized` options
 * and of the Swift fork's `SocketTLSConfiguration`.
 *
 * There is deliberately no "trust everything" mode. For development servers,
 * trust their private CA with [customTrust] (or, on Android, a debug-only
 * `network_security_config.xml`).
 *
 * On Android the default ([systemDefault]) keeps the platform trust manager,
 * so `network_security_config.xml` (pins, user CAs, cleartext rules) applies
 * unchanged. [customTrust] replaces it for this client.
 *
 * The policy adds to the OkHttp client it is applied to (see [applyTo]): a
 * client certificate keeps that client's trust manager, and pins are added to
 * its `CertificatePinner`.
 */
public class TlsPolicy private constructor(
    /** Trust anchors replacing the trust manager; `null` keeps the base client's (by default the system or Network Security Config trust). */
    public val trustAnchors: List<X509Certificate>?,
    /** Public-key pins by host pattern, in OkHttp's `sha256/…` form. */
    public val pins: Map<String, List<String>>,
    /** Key manager presenting a client certificate (mutual TLS). */
    public val clientKeyManager: X509KeyManager?,
) {
    init {
        require(trustAnchors == null || trustAnchors.isNotEmpty()) { "customTrust needs at least one anchor" }
        for ((host, values) in pins) {
            require(host.isNotBlank()) { "a pin needs a host pattern" }
            require(values.isNotEmpty()) { "pins for $host must not be empty" }
            require(values.all { it.startsWith("sha256/") }) { "pins must be \"sha256/<base64>\" values" }
        }
    }

    /**
     * A copy that adds [pins] for [hostPattern] (`example.com`, `*.example.com` or
     * `**.example.com`). Pins already set for the pattern are kept, so a backup pin
     * can be added to a primary one, as `CertificatePinner.Builder.add` does.
     */
    public fun withPins(
        hostPattern: String,
        vararg pins: String,
    ): TlsPolicy = TlsPolicy(trustAnchors, this.pins + (hostPattern to (this.pins[hostPattern].orEmpty() + pins).distinct()), clientKeyManager)

    /** A copy that pins [hostPattern] to the public keys of [certificates] (leaf or any chain certificate). */
    public fun withPinnedCertificates(
        hostPattern: String,
        certificates: List<X509Certificate>,
    ): TlsPolicy = withPins(hostPattern, *certificates.map { CertificatePinner.pin(it) }.toTypedArray())

    /** A copy that presents a client certificate from [keyManager]. */
    public fun withClientCertificate(keyManager: X509KeyManager): TlsPolicy = TlsPolicy(trustAnchors, pins, keyManager)

    /** A copy that presents the first key entry of a PKCS#12 file. */
    public fun withClientCertificate(
        pkcs12: ByteArray,
        password: CharArray,
    ): TlsPolicy = withClientCertificate(keyManagerFromPkcs12(pkcs12, password))

    /**
     * Applies the policy to an OkHttp client builder, on top of what the builder already
     * configures:
     * - without [trustAnchors], the builder's trust manager stays in charge, also when a
     *   [clientKeyManager] is added (a default builder has the platform trust manager, on
     *   Android with the app's Network Security Config); [customTrust] replaces it;
     * - [pins] are added to the builder's `CertificatePinner`, whose pins for every host are
     *   kept. As in OkHttp, a host matched by several patterns accepts any of their pins.
     */
    public fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        // What the builder configures so far, read from a client built from it (building has no side effects).
        val current = if (clientKeyManager != null || pins.isNotEmpty()) builder.build() else null
        if (trustAnchors != null || clientKeyManager != null) {
            val trustManager = trustAnchors?.let(::trustManagerFor) ?: current?.x509TrustManager ?: systemTrustManager()
            val context = SSLContext.getInstance("TLS")
            context.init(clientKeyManager?.let { arrayOf<KeyManager>(it) }, arrayOf(trustManager), null)
            builder.sslSocketFactory(context.socketFactory, trustManager)
        }
        if (pins.isNotEmpty()) {
            val pinner = CertificatePinner.Builder()
            current?.certificatePinner?.pins?.forEach { pinner.add(it.pattern, it.toString()) }
            for ((host, values) in pins) pinner.add(host, *values.toTypedArray())
            builder.certificatePinner(pinner.build())
        }
        return builder
    }

    override fun toString(): String =
        "TlsPolicy(trust=${if (trustAnchors == null) "system" else "${trustAnchors.size} anchors"}, pinnedHosts=${pins.keys}, clientCertificate=${clientKeyManager != null})"

    public companion object {
        /** The platform trust store (and on Android the app's Network Security Config). */
        @JvmStatic
        public fun systemDefault(): TlsPolicy = TlsPolicy(null, emptyMap(), null)

        /** System trust plus public-key pins for [hostPattern]. */
        @JvmStatic
        public fun pinned(
            hostPattern: String,
            vararg pins: String,
        ): TlsPolicy = systemDefault().withPins(hostPattern, *pins)

        /** Trust only [anchors] (private CA or self-signed leaf certificates) instead of the system store. */
        @JvmStatic
        public fun customTrust(anchors: List<X509Certificate>): TlsPolicy = TlsPolicy(anchors, emptyMap(), null)

        /** Parses one or more PEM or DER certificates. */
        @JvmStatic
        public fun certificates(encoded: ByteArray): List<X509Certificate> =
            CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(encoded)).map { it as X509Certificate }

        /** A key manager for the first key entry of a PKCS#12 file. */
        @JvmStatic
        public fun keyManagerFromPkcs12(
            pkcs12: ByteArray,
            password: CharArray,
        ): X509KeyManager {
            val store = KeyStore.getInstance("PKCS12")
            store.load(ByteArrayInputStream(pkcs12), password)
            val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            factory.init(store, password)
            return factory.keyManagers.filterIsInstance<X509KeyManager>().first()
        }

        private fun trustManagerFor(anchors: List<X509Certificate>): X509TrustManager {
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null, null)
            anchors.forEachIndexed { index, certificate -> store.setCertificateEntry("anchor$index", certificate) }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        private fun systemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
