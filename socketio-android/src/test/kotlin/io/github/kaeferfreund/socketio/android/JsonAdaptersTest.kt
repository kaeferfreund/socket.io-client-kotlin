package io.github.kaeferfreund.socketio.android

import io.github.kaeferfreund.socketio.parser.SocketIODecoder
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The `org.json` adapters with binary data: one copy per attachment, however often it is referenced. */
@RunWith(RobolectricTestRunner::class)
class JsonAdaptersTest {
    @Test
    fun repeatedPlaceholdersShareOneCopyInToJson() {
        val references = 1_000
        val decoder = SocketIODecoder()
        decoder.add("51-[\"e\"" + ",{\"_placeholder\":true,\"num\":0}".repeat(references) + "]")
        val packet = decoder.add(ByteArray(64 * 1024) { it.toByte() })!!
        val array = (packet.data as SocketIOValue.Array).toJSONArray()
        val first = array.get(1) as ByteArray
        // JavaScript hands out the same Buffer for every reference; a copy per reference would multiply the attachment.
        for (i in 1..references) assertSame(first, array.get(i))
        assertArrayEquals(ByteArray(64 * 1024) { it.toByte() }, first)
    }

    @Test
    fun toJsonStillCopiesTheValueAndKeepsDistinctBinariesDistinct() {
        val shared = SocketIOValue.Binary(byteArrayOf(1))
        val value = SocketIOValue.arrayOf(shared, SocketIOValue.Binary(byteArrayOf(1)), mapOf("x" to shared))
        val first = value.toJSONArray()
        val second = value.toJSONArray()
        assertNotSame(first.get(0), second.get(0))
        assertNotSame(first.get(0), first.get(1))
        assertSame(first.get(0), (first.get(2) as JSONObject).get("x"))
        (first.get(0) as ByteArray)[0] = 7
        assertArrayEquals(byteArrayOf(1), shared.bytes)
    }
}
