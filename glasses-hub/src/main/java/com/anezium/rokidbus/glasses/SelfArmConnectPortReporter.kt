package com.anezium.rokidbus.glasses

internal data class SelfArmConnectPortReport(
    val requestId: String,
    val action: String,
    val connectPort: Int?,
    val updateOnly: Boolean,
)

/** Keeps late port reports tied to the manual-navigation request that opened this setup flow. */
internal class SelfArmConnectPortReporter(
    private val waitForPort: (Long) -> Int,
) {
    private data class Attempt(
        val requestId: String,
        val action: String,
        var initialReportSent: Boolean = false,
        var pendingPort: Int = 0,
        var lastReportedPort: Int = 0,
    )

    private val lock = Any()
    private var activeAttempt: Attempt? = null

    fun begin(requestId: String, action: String) {
        synchronized(lock) {
            activeAttempt = Attempt(requestId, action)
        }
    }

    fun initialReport(timeoutMs: Long): SelfArmConnectPortReport? {
        val expected = synchronized(lock) { activeAttempt } ?: return null
        val waitedPort = if (timeoutMs <= 0L) {
            0
        } else {
            try {
                waitForPort(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                0
            }
        }.takeIf(::validPort) ?: 0
        return synchronized(lock) {
            val current = activeAttempt
            if (current !== expected || current.initialReportSent) return@synchronized null
            val port = waitedPort.takeIf(::validPort) ?: current.pendingPort.takeIf(::validPort)
            current.initialReportSent = true
            current.pendingPort = 0
            current.lastReportedPort = port ?: 0
            current.toReport(port, updateOnly = false)
        }
    }

    fun pushKnownPort(port: Int): SelfArmConnectPortReport? {
        if (!validPort(port)) return null
        return synchronized(lock) {
            val current = activeAttempt ?: return@synchronized null
            if (!current.initialReportSent) {
                current.pendingPort = port
                return@synchronized null
            }
            if (current.lastReportedPort == port) return@synchronized null
            current.lastReportedPort = port
            current.toReport(port, updateOnly = true)
        }
    }

    fun clear() {
        synchronized(lock) { activeAttempt = null }
    }

    private fun Attempt.toReport(port: Int?, updateOnly: Boolean): SelfArmConnectPortReport =
        SelfArmConnectPortReport(
            requestId = requestId,
            action = action,
            connectPort = port,
            updateOnly = updateOnly,
        )

    private fun validPort(port: Int): Boolean = port in 1..65535
}
