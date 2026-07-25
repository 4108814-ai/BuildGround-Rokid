package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncStabilityGateTest {
    private val gate = MediaSyncStabilityGate(minAgeMillis = 5_000L, minSampleGapMillis = 3_000L)

    @Test
    fun `a file is never eligible on its first sighting`() {
        val photo = MediaFileSample("img-1.jpg", 3_000_000L, 0L)

        assertTrue(gate.observe(listOf(photo), 100_000L).isEmpty())
    }

    @Test
    fun `an unchanged old file becomes eligible on the second scan`() {
        val photo = MediaFileSample("img-1.jpg", 3_000_000L, 100_000L)
        gate.observe(listOf(photo), 110_000L)

        val eligible = gate.observe(listOf(photo), 113_000L)

        assertEquals(listOf(photo), eligible)
    }

    @Test
    fun `a recording video is held back until its size stops moving`() {
        val name = "vid-1.mp4"
        gate.observe(listOf(MediaFileSample(name, 1_000_000L, 100_000L)), 110_000L)
        val growing = gate.observe(listOf(MediaFileSample(name, 2_000_000L, 113_000L)), 113_000L)
        val stillGrowing = gate.observe(listOf(MediaFileSample(name, 3_000_000L, 116_000L)), 116_000L)

        assertTrue(growing.isEmpty())
        assertTrue(stillGrowing.isEmpty())

        val settled = MediaFileSample(name, 3_000_000L, 116_000L)
        assertEquals(listOf(settled), gate.observe(listOf(settled), 125_000L))
    }

    @Test
    fun `a file whose mtime is still fresh waits even when its size is stable`() {
        val fresh = MediaFileSample("img-2.jpg", 500_000L, 120_000L)
        gate.observe(listOf(fresh), 121_000L)

        assertTrue(gate.observe(listOf(fresh), 124_000L).isEmpty())
        assertEquals(listOf(fresh), gate.observe(listOf(fresh), 126_000L))
    }

    @Test
    fun `two scans closer than the settling window do not qualify`() {
        val photo = MediaFileSample("img-3.jpg", 10L, 0L)
        gate.observe(listOf(photo), 100_000L)

        assertTrue(gate.observe(listOf(photo), 102_000L).isEmpty())
    }

    @Test
    fun `zero byte files are never offered`() {
        val empty = MediaFileSample("img-4.jpg", 0L, 0L)
        gate.observe(listOf(empty), 100_000L)

        assertTrue(gate.observe(listOf(empty), 110_000L).isEmpty())
    }

    @Test
    fun `a name that disappears loses its history so a recycled name restarts`() {
        val first = MediaFileSample("img-5.jpg", 100L, 0L)
        gate.observe(listOf(first), 100_000L)
        gate.observe(emptyList(), 103_000L)

        assertTrue(gate.observe(listOf(first), 106_000L).isEmpty())
    }

    @Test
    fun `forget drops a single entry, used after a delete`() {
        val photo = MediaFileSample("img-6.jpg", 100L, 0L)
        gate.observe(listOf(photo), 100_000L)
        gate.forget(photo.name)

        assertTrue(gate.observe(listOf(photo), 110_000L).isEmpty())
    }
}
