package com.anezium.rokidbus.glasses

/** One `stat` of a capture file, as observed by a catalog scan. */
data class MediaFileSample(
    val name: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)

/**
 * Keeps a video that is still recording out of the catalog.
 *
 * A capture becomes eligible only when two independent scans, at least [minSampleGapMillis]
 * apart, agree on both its size and its mtime, and the mtime itself is at least
 * [minAgeMillis] old. A file being written fails all three checks, so an in-progress recording
 * can never be handed to the file server.
 */
class MediaSyncStabilityGate(
    private val minAgeMillis: Long = MIN_AGE_MS,
    private val minSampleGapMillis: Long = MIN_SAMPLE_GAP_MS,
) {
    private data class Observation(
        val sample: MediaFileSample,
        val observedAtMillis: Long,
    )

    private val observations = HashMap<String, Observation>()

    /**
     * Records [files] as the latest scan and returns the subset that is safe to transfer.
     * Files that disappeared between scans are forgotten so a recycled name never inherits
     * stale stability.
     */
    @Synchronized
    fun observe(files: List<MediaFileSample>, nowMillis: Long): List<MediaFileSample> {
        val eligible = ArrayList<MediaFileSample>(files.size)
        val seen = HashSet<String>(files.size)
        files.forEach { sample ->
            seen += sample.name
            val previous = observations[sample.name]
            val unchanged = previous != null &&
                previous.sample.sizeBytes == sample.sizeBytes &&
                previous.sample.modifiedMillis == sample.modifiedMillis
            val settled = previous != null &&
                nowMillis - previous.observedAtMillis >= minSampleGapMillis
            val old = nowMillis - sample.modifiedMillis >= minAgeMillis
            if (sample.sizeBytes > 0L && unchanged && settled && old) {
                eligible += sample
            }
            // The first observation of a changed file restarts its settling window.
            if (previous == null || !unchanged) {
                observations[sample.name] = Observation(sample, nowMillis)
            }
        }
        observations.keys.retainAll(seen)
        return eligible
    }

    @Synchronized
    fun forget(name: String) {
        observations.remove(name)
    }

    companion object {
        const val MIN_AGE_MS = 5_000L
        const val MIN_SAMPLE_GAP_MS = 3_000L
    }
}
