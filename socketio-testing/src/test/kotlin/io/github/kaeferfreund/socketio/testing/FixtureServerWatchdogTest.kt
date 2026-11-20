package io.github.kaeferfreund.socketio.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * The watchdog script every fixture loads with `node --require` lives in the
 * shared temporary directory, so no other local user may read or replace it.
 */
class FixtureServerWatchdogTest {
    @Test
    fun theWatchdogScriptIsReadableAndWritableByItsOwnerOnly() {
        val script = FixtureServer.createWatchdogScript().toPath()
        try {
            assertTrue(Files.readString(script).contains("process.stdin.on(\"end\""))
            if (Files.getFileAttributeView(script, PosixFileAttributeView::class.java) != null) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(script),
                )
            } else {
                // Without POSIX permissions the file inherits the ACL of the user's temporary directory.
                assertTrue(Files.isReadable(script) && Files.isWritable(script))
            }
        } finally {
            Files.deleteIfExists(script)
        }
    }
}
