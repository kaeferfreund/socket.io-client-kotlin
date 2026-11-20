package io.github.kaeferfreund.socketio.android

import android.app.Activity
import android.content.Context
import android.security.KeyChain
import android.security.KeyChainException
import io.github.kaeferfreund.socketio.okhttp.TlsPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import kotlin.coroutines.resume

/**
 * Presents the client certificate stored in the Android KeyChain under
 * [alias] for mutual TLS. The user grants the app access to the alias once
 * with [chooseKeyChainAlias].
 *
 * KeyChain calls block, so they happen on OkHttp's connection thread during
 * the TLS handshake, never on the main thread.
 *
 * The certificate is offered whenever a server requests one, regardless of the
 * certificate authorities it lists: a server may accept certificates from
 * authorities it does not advertise, and filtering by that list would break
 * such setups. The manager's connections stay within the origin of its URL:
 * while a client certificate is configured, polling refuses redirects to another origin.
 */
public class KeyChainKeyManager internal constructor(
    context: Context,
    private val alias: String,
    private val readChain: (Context, String) -> Array<X509Certificate>?,
    private val readKey: (Context, String) -> PrivateKey?,
) : X509ExtendedKeyManager() {
    public constructor(context: Context, alias: String) : this(context, alias, KeyChain::getCertificateChain, KeyChain::getPrivateKey)

    private val context = context.applicationContext ?: context

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String = alias

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String = alias

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == this.alias) fromKeyChain { readChain(context, alias) } else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == this.alias) fromKeyChain { readKey(context, alias) } else null

    /**
     * A KeyChain failure (an invalidated key, an unavailable KeyChain service) ends the
     * handshake without a client certificate, which the server reports as a TLS error:
     * a `connect_error`. Thrown out of the key manager, it would kill OkHttp's thread.
     */
    private inline fun <T> fromKeyChain(read: () -> T?): T? =
        try {
            read()
        } catch (e: KeyChainException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String> = arrayOf(alias)

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null
}

/** A copy of this policy that presents the KeyChain certificate [alias]. */
public fun TlsPolicy.withKeyChainAlias(
    context: Context,
    alias: String,
): TlsPolicy = withClientCertificate(KeyChainKeyManager(context, alias))

/**
 * Lets the user pick (and grant access to) a client certificate with the
 * system dialog (`KeyChain.choosePrivateKeyAlias`). Returns `null` when the
 * user cancels. Store the alias; the grant survives app restarts.
 */
public suspend fun chooseKeyChainAlias(
    activity: Activity,
    host: String? = null,
    port: Int = -1,
    preselectedAlias: String? = null,
): String? =
    suspendCancellableCoroutine { continuation ->
        KeyChain.choosePrivateKeyAlias(activity, { alias -> if (continuation.isActive) continuation.resume(alias) }, null, null, host, port, preselectedAlias)
    }
