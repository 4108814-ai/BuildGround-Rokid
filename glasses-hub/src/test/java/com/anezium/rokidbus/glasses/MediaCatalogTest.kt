package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                directories = listOf(directory),
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

    @Test
    fun `media from every root is listed while sidecars and missing roots are ignored`() {
        val parent = Files.createTempDirectory("media-catalog-roots-test").toFile()
        try {
            val photos = parent.resolve("photos").apply { mkdir() }
            val videos = parent.resolve("videos").apply { mkdir() }
            val missing = parent.resolve("missing")
            val photo = photos.resolve("img-20260803-230850-a3-P1-8.jpg").apply {
                writeBytes(byteArrayOf(1))
            }
            val video = videos.resolve("vid-20260803-231006-26.mp4").apply {
                writeBytes(byteArrayOf(2))
            }
            videos.resolve("vid-20260803-231006-26.txt").writeText("videoWidth:3072")
            val nowMillis = System.currentTimeMillis() + 1_000L
            val catalog = MediaCatalog(
                directories = listOf(photos, videos, missing),
                gate = MediaSyncStabilityGate(minAgeMillis = 0L, minSampleGapMillis = 0L),
                clock = { nowMillis },
            )

            assertTrue(catalog.scan().items.isEmpty())
            val names = catalog.scan().items.map { it.name }.toSet()

            assertEquals(setOf(photo.name, video.name), names)
            assertEquals(photo.canonicalFile, catalog.resolve(photo.name))
            assertEquals(video.canonicalFile, catalog.resolve(video.name))
            assertNull(catalog.resolve("vid-20260803-231006-26.txt"))
            assertNull(catalog.resolve("missing.jpg"))
            assertNull(catalog.resolve("../outside.jpg"))
            assertNull(catalog.resolve(photo.absolutePath))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `the first root wins when a supported name exists in more than one root`() {
        val parent = Files.createTempDirectory("media-catalog-duplicate-test").toFile()
        try {
            val first = parent.resolve("first").apply { mkdir() }
            val second = parent.resolve("second").apply { mkdir() }
            val name = "img-duplicate.jpg"
            val expected = first.resolve(name).apply { writeBytes(byteArrayOf(1)) }
            second.resolve(name).writeBytes(byteArrayOf(2, 3))
            val nowMillis = System.currentTimeMillis() + 1_000L
            val catalog = MediaCatalog(
                directories = listOf(first, second),
                gate = MediaSyncStabilityGate(minAgeMillis = 0L, minSampleGapMillis = 0L),
                clock = { nowMillis },
            )

            catalog.scan()
            val scan = catalog.scan()

            assertEquals(listOf(name), scan.items.map { it.name })
            assertEquals(expected.length(), scan.items.single().sizeBytes)
            assertEquals(expected.canonicalFile, catalog.resolve(name))
        } finally {
            parent.deleteRecursively()
        }
    }
}
