package io.github.kaeferfreund.socketio.parser

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.management.ManagementFactory

/**
 * Peer input must not cost more memory than the configured limits admit:
 * rejected attachments are not copied, and attachments referenced from
 * several placeholders are not multiplied by conversions.
 */
class SocketIOResourceBoundsTest {
    @Test
    fun anAttachmentOverTheByteLimitIsRejectedWithoutBeingCopied() {
        val input = ByteArray(BIG)
        for (frame in listOf<(SocketIODecoder) -> Unit>({ it.add(input) }, { it.add(EngineIOData.Binary.wrap(input)) })) {
            val decoder = SocketIODecoder(SocketParserOptions(maxBinaryPacketBytes = 1))
            decoder.add("51-[\"e\",{\"_placeholder\":true,\"num\":0}]")
            val allocated =
                allocatedBytesDuring {
                    val error = assertThrows<SocketIOParseException> { frame(decoder) }
                    assertEquals("binary packet exceeds maxBinaryPacketBytes (1)", error.message)
                }
            assertTrue(allocated < BIG / 4, "rejecting allocated $allocated bytes")
        }
    }

    @Test
    fun unexpectedBinaryDataIsRejectedWithoutBeingCopied() {
        val input = ByteArray(BIG)
        for (frame in listOf<(SocketIODecoder) -> Unit>({ it.add(input) }, { it.add(EngineIOData.Binary.wrap(input)) })) {
            val decoder = SocketIODecoder()
            val allocated =
                allocatedBytesDuring {
                    val error = assertThrows<SocketIOParseException> { frame(decoder) }
                    assertEquals("got binary data when not reconstructing a packet", error.message)
                }
            assertTrue(allocated < BIG / 4, "rejecting allocated $allocated bytes")
        }
    }

    @Test
    fun anAcceptedAttachmentIsStillCopiedFromTheCallersArray() {
        val input = bytesOf(1, 2, 3)
        val decoder = SocketIODecoder()
        decoder.add("51-[\"e\",{\"_placeholder\":true,\"num\":0}]")
        val packet = decoder.add(input)!!
        input[0] = 9
        assertArrayEquals(bytesOf(1, 2, 3), packet.data!!.array!![1].bytes)
    }

    @Test
    fun repeatedPlaceholdersShareOneCopyInToKotlin() {
        val references = 1_000
        val packet = aliasingPacket(references)
        lateinit var converted: List<*>
        val allocated = allocatedBytesDuring { converted = packet.data!!.toKotlin() as List<*> }
        val arrays = converted.drop(1).map { it as ByteArray }
        assertEquals(references, arrays.size)
        // One attachment, one copy for the whole conversion: JavaScript hands out the same Buffer for every reference.
        arrays.forEach { assertSame(arrays[0], it) }
        assertArrayEquals(ByteArray(ATTACHMENT) { it.toByte() }, arrays[0])
        // A copy per reference would be references * ATTACHMENT = 64 MiB.
        assertTrue(allocated < 4L * 1024 * 1024, "conversion allocated $allocated bytes")
    }

    @Test
    fun toKotlinStillCopiesTheValueAndKeepsDistinctBinariesDistinct() {
        val shared = SocketIOValue.Binary(bytesOf(1))
        val value = socketIOArray(shared, SocketIOValue.Binary(bytesOf(1)), socketIOObject("x" to shared))
        val first = value.toKotlin() as List<*>
        val second = value.toKotlin() as List<*>
        assertNotSame(first[0], second[0])
        assertNotSame(first[0], first[1])
        assertSame(first[0], (first[2] as Map<*, *>)["x"])
        (first[0] as ByteArray)[0] = 7
        assertArrayEquals(bytesOf(1), shared.bytes)
    }

    private fun aliasingPacket(references: Int): SocketIOPacket {
        val header = "51-[\"e\"" + ",{\"_placeholder\":true,\"num\":0}".repeat(references) + "]"
        val decoder = SocketIODecoder()
        decoder.add(header)
        return decoder.add(ByteArray(ATTACHMENT) { it.toByte() })!!
    }

    private companion object {
        const val BIG = 16 * 1024 * 1024
        const val ATTACHMENT = 64 * 1024

        /** Bytes the current thread allocated while running [block]; exact on HotSpot, no timing involved. */
        fun allocatedBytesDuring(block: () -> Unit): Long {
            val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            check(bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled) { "allocation accounting unavailable" }
            val before = bean.currentThreadAllocatedBytes
            block()
            return bean.currentThreadAllocatedBytes - before
        }
    }
}
