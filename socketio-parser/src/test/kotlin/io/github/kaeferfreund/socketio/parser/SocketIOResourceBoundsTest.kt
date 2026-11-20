package io.github.kaeferfreund.socketio.parser

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.management.ManagementFactory

/**
 * Peer input must not cost more memory than the configured limits admit:
 * rejected attachments are not copied.
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

    private companion object {
        const val BIG = 16 * 1024 * 1024

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
