package com.anezium.rokidbus.glasses

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SelfArmCommandBridgeChannelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun healthyDirectoryIsReturnedWithoutReplacementOrContentChanges() {
        val externalFiles = temporaryFolder.newFolder("healthy-external")
        val channel = File(externalFiles, "cmd_bridge").apply { assertTrue(mkdir()) }
        val sentinel = File(channel, "doorbell").apply { writeText("keep") }
        val fileKey = attributes(channel).fileKey()
        val contents = channel.list()?.toSet()

        val result = SelfArmCommandBridgeClient.ensureChannelDir(externalFiles)

        assertEquals(channel, result)
        assertEquals(fileKey, attributes(channel).fileKey())
        assertEquals(contents, channel.list()?.toSet())
        assertEquals("keep", sentinel.readText())
    }

    @Test
    fun unwritableDirectoryIsRemovedAndRecreatedInsideExternalFiles() {
        val externalFiles = temporaryFolder.newFolder("repair-external")
        val sibling = File(externalFiles, "keep.txt").apply { writeText("outside channel") }
        val channel = File(externalFiles, "cmd_bridge").apply { assertTrue(mkdir()) }
        val staleEntry = File(channel, "doorbell").apply { writeText("stale") }
        var probeCount = 0

        // Windows does not enforce File.setWritable(false) for directory entry creation, so the
        // first probe result models the scoped-storage refusal while temp files exercise repair.
        val result = SelfArmCommandBridgeClient.ensureChannelDir(
            externalFiles,
            writableProbe = { candidate ->
                probeCount += 1
                probeCount > 1 && canCreateAndDeleteTempFile(candidate)
            },
        )

        assertEquals(channel, result)
        assertTrue(channel.isDirectory)
        assertFalse(staleEntry.exists())
        assertEquals("outside channel", sibling.readText())
        assertTrue(probeCount >= 2)
    }

    @Test
    fun repairFailureReturnsNull() {
        val externalFiles = temporaryFolder.newFolder("failed-repair-external")
        val channel = File(externalFiles, "cmd_bridge").apply { assertTrue(mkdir()) }
        val staleEntry = File(channel, "doorbell").apply { writeText("stale") }
        var removalAttempts = 0

        val result = SelfArmCommandBridgeClient.ensureChannelDir(
            externalFiles,
            writableProbe = { false },
            removeChannel = {
                removalAttempts += 1
                false
            },
        )

        assertNull(result)
        assertTrue(channel.isDirectory)
        assertTrue(staleEntry.exists())
        assertEquals(3, removalAttempts)
    }

    private fun canCreateAndDeleteTempFile(directory: File): Boolean {
        val probe = File.createTempFile("channel-test-", ".tmp", directory)
        return probe.delete()
    }

    private fun attributes(file: File): BasicFileAttributes {
        val result = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        assertNotNull(result)
        return result
    }
}
