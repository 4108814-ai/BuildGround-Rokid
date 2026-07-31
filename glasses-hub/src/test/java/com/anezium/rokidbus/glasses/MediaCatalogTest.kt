package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MediaCatalogTest {
    @Test
    fun `a fresh capture keeps the catalog settling beside eligible old captures`() {
        val directory = Files.createTempDirectory("media-catalog-test").toFile()
        try {
            var nowMillis = 10_000L
            val oldCapture = directory.resolve("img-old.jpg").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(0L)
            }
            val catalog = MediaCatalog(
                directory = directory,
                gate = MediaSyncStabilityGate(minAgeMillis = 5_000L, minSampleGapMillis = 3_000L),
                clock = { nowMillis },
            )

            assertTrue(catalog.scan().settling)
            nowMillis = 13_000L
            assertEquals(listOf(oldCapture.name), catalog.scan().items.map { it.name })

            directory.resolve("img-new.jpg").apply {
                writeBytes(byteArrayOf(2))
                setLastModified(nowMillis)
            }
            nowMillis = 14_000L
            val mixed = catalog.scan()

            assertEquals(listOf(oldCapture.name), mixed.items.map { it.name })
            assertTrue(mixed.settling)

            nowMillis = 19_000L
            assertFalse(catalog.scan().settling)
        } finally {
            directory.deleteRecursively()
        }
    }
}
