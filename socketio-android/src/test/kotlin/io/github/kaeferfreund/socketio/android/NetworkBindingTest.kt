package io.github.kaeferfreund.socketio.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Issue #1: behind a VPN (Tailscale) a lookup bound to the VPN network failed with
 * EAI_NODATA without sending a query, while the system resolver answered.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkBindingTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun connectivity(): ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private fun capabilities(vararg transports: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { caps -> transports.forEach { shadowOf(caps).addTransportType(it) } }

    private fun attach(binding: NetworkBinding): Pair<SocketManager, io.github.kaeferfreund.socketio.engineio.Cancellable> {
        val manager = SocketManager("http://fake.test", SocketManagerOptions { autoConnect = false })
        val handle = NetworkMonitor(context, AndroidSocketOptions(), binding, OkHttpClient()).attach(manager)
        return manager to handle
    }

    @Test
    fun aVpnDefaultNetworkIsNotBoundSoTheSystemRoutesThroughTheVpn() {
        val vpn = connectivity().activeNetwork!!
        shadowOf(connectivity()).setNetworkCapabilities(vpn, capabilities(NetworkCapabilities.TRANSPORT_VPN))
        val binding = NetworkBinding(null)
        val (manager, handle) = attach(binding)
        assertNull(binding.network)
        handle.cancel()
        manager.close()
    }

    @Test
    fun aRegularDefaultNetworkIsBound() {
        val wifi = connectivity().activeNetwork!!
        shadowOf(connectivity()).setNetworkCapabilities(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        val binding = NetworkBinding(null)
        val (manager, handle) = attach(binding)
        assertSame(wifi, binding.network)
        handle.cancel()
        manager.close()
    }

    @Test
    fun switchingToAndFromAVpnFollowsTheCapabilities() {
        val wifi = connectivity().activeNetwork!!
        shadowOf(connectivity()).setNetworkCapabilities(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        val binding = NetworkBinding(null)
        val (manager, handle) = attach(binding)
        val callback = shadowOf(connectivity()).networkCallbacks.single()
        val vpn = ShadowNetwork.newInstance(109)
        val vpnCaps = capabilities(NetworkCapabilities.TRANSPORT_VPN)
        shadowOf(connectivity()).setNetworkCapabilities(vpn, vpnCaps)
        callback.onAvailable(vpn)
        callback.onCapabilitiesChanged(vpn, vpnCaps)
        assertNull(binding.network)
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        assertSame(wifi, binding.network)
        handle.cancel()
        manager.close()
    }

    @Test
    fun aFailedBoundLookupFallsBackToTheSystemResolver() {
        val system = Dns { host -> listOf(InetAddress.getByAddress(host, byteArrayOf(192.toByte(), 0, 2, 7))) }
        val binding =
            NetworkBinding(null, systemDns = system) { _: Network, host: String ->
                throw UnknownHostException("Unable to resolve host \"$host\": No address associated with hostname")
            }
        binding.network = ShadowNetwork.newInstance(109)
        assertEquals(
            listOf(InetAddress.getByAddress("remote-device.example.test", byteArrayOf(192.toByte(), 0, 2, 7))),
            binding.dns.lookup("remote-device.example.test"),
        )
    }

    @Test
    fun aHostUnknownEverywhereStillFails() {
        val system = Dns { host -> throw UnknownHostException(host) }
        val binding = NetworkBinding(null, systemDns = system) { _: Network, host: String -> throw UnknownHostException(host) }
        binding.network = ShadowNetwork.newInstance(7)
        assertThrows(UnknownHostException::class.java) { binding.dns.lookup("nowhere.invalid") }
    }
}
