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
 * Polling through a redirect to another origin, against the upstream engine server: a front
 * server on `node1.example.test` answers every request with a 307. Polls (GET) go to the Node
 * fixture on `127.0.0.1`, another host; POSTs, whose body carries the packets, are repeated only
 * on the first host, so they go to the fixture's port on `node1.example.test`. The fixture
 * reports the headers of the POST that carried `sendHeaders`.
 */
class RedirectE2ETest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun redirector(
        getTarget: String,
        postTarget: String,
    ): HttpServer =
        HttpServer.create(InetSocketAddress(loopback, 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.use { it.readAllBytes() }
                val query = exchange.requestURI.rawQuery?.let { "?$it" } ?: ""
                val target = if (exchange.requestMethod == "POST") postTarget else getTarget
                exchange.responseHeaders.add("Location", target + exchange.requestURI.rawPath + query)
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
            }
            start()
        }

    @Test
    fun pollsFollowARedirectToAnotherOriginAndPostsStayOnTheFirstHost() =
        engineE2E {
            val fixturePort = java.net.URI(url).port
            val front = redirector(url.trimEnd('/'), "http://node1.example.test:$fixturePort")
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
                // The fixture set `1=1` and `2=2` for 127.0.0.1, where the polls went; the POST on
                // node1.example.test gets only the explicit header. Authorization stays with the
                // front server's origin, other headers follow.
                assertEquals("app=1", headers["cookie"]!!.string)
                assertEquals(null, headers["authorization"])
                assertEquals("key", headers["x-api-key"]!!.string)
            } finally {
                front.stop(0)
            }
        }
}
