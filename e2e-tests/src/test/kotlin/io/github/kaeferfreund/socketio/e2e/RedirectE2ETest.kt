package io.github.kaeferfreund.socketio.e2e

import com.sun.net.httpserver.HttpServer
import io.github.kaeferfreund.socketio.engineio.EngineOptions
import io.github.kaeferfreund.socketio.okhttp.OkHttpEngineClients
import io.github.kaeferfreund.socketio.parser.SocketIOJson
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Polling through a redirect to another origin, against the upstream engine server: a
 * front server on `node1.example.test` answers every request with a 307 to the Node fixture
 * on `127.0.0.1`. The handshake, the polls and the POSTs (307 repeats them with their body)
 * reach the fixture, which reports the headers of the POST that carried `sendHeaders`.
 */
class RedirectE2ETest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun redirector(target: String): HttpServer =
        HttpServer.create(InetSocketAddress(loopback, 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.use { it.readAllBytes() }
                val query = exchange.requestURI.rawQuery?.let { "?$it" } ?: ""
                exchange.responseHeaders.add("Location", target + exchange.requestURI.rawPath + query)
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
            }
            start()
        }

    @Test
    fun aRedirectToAnotherOriginGetsTheJarCookiesOfThatOriginAndNoCredentials() =
        engineE2E {
            val front = redirector(url.trimEnd('/'))
            try {
                val clients = OkHttpEngineClients(OkHttpClient.Builder().dns { listOf(loopback) }.build()).clients
                val client =
                    engine(
                        EngineOptions(
                            transports = listOf("polling"),
                            withCredentials = true,
                            extraHeaders = mapOf("Cookie" to "app=1", "Authorization" to "Bearer token", "X-Api-Key" to "key"),
                            clients = clients,
                        ),
                        uri = "http://node1.example.test:${front.address.port}",
                    )
                client.awaitOpen()
                assertEquals("hi", client.nextText())
                client.onExecutor { send("sendHeaders") }
                val headers = SocketIOJson.parse(client.nextText()).obj!!
                // The fixture set `1=1` and the `Secure` `2=2` (loopback counts as secure) for
                // 127.0.0.1; the explicit header and Authorization stay with node1.example.test.
                assertEquals("1=1; 2=2", headers["cookie"]!!.string)
                assertEquals(null, headers["authorization"])
                assertEquals("key", headers["x-api-key"]!!.string)
            } finally {
                front.stop(0)
            }
        }
}
