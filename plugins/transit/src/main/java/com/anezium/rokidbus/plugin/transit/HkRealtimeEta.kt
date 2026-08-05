package com.anezium.rokidbus.plugin.transit

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class HkRealtimeEta(private val http: (String) -> String) {
    /**
     * Returns null for an ineligible stop or failed realtime request so the
     * caller can preserve the Transitous scheduled board.
     */
    fun overlay(stopId: String, scheduled: List<TransitDeparture>): List<TransitDeparture>? {
        val stop = parseStopId(stopId) ?: return null
        return runCatching {
            when (stop.operator) {
                Operator.KMB -> kmbDepartures(stop.operatorStopId)
                Operator.CTB -> ctbDepartures(stop.operatorStopId, scheduled)
                Operator.GMB -> gmbDepartures(stop.operatorStopId)
            }
        }.getOrNull()
    }

    private fun kmbDepartures(operatorStopId: String): List<TransitDeparture> =
        parseBusDepartures(
            http("https://data.etabus.gov.hk/v1/transport/kmb/stop-eta/$operatorStopId"),
        )

    private fun ctbDepartures(
        operatorStopId: String,
        scheduled: List<TransitDeparture>,
    ): List<TransitDeparture> {
        val routes = scheduled
            .sortedBy { it.scheduledDeparture ?: it.departure }
            .mapNotNull { departure -> departure.routeShortName?.trim()?.takeIf { it.isNotBlank() } }
            .distinct()
            .take(MAX_CTB_ROUTES)

        return routes.flatMap { route ->
            parseBusDepartures(
                http("https://rt.data.gov.hk/v2/transport/citybus/eta/CTB/$operatorStopId/$route"),
            )
        }.sortedBy { it.departure }
    }

    private fun gmbDepartures(operatorStopId: String): List<TransitDeparture> {
        val data = JSONObject(
            http("https://data.etagmb.gov.hk/eta/stop/$operatorStopId"),
        ).getJSONArray("data")
        return buildList {
            for (index in 0 until data.length()) {
                val routeEta = data.getJSONObject(index)
                val eta = routeEta.getJSONArray("eta")
                val instants = parseGmbInstants(eta)
                if (instants.isEmpty()) continue

                val routeId = routeEta.getLong("route_id")
                val routeSequence = routeEta.getInt("route_seq")
                val route = GMB_ROUTE_CACHE.computeIfAbsent(routeId) { fetchGmbRoute(it) }
                val destination = route.destinations[routeSequence]
                    ?: throw IOException("GMB route $routeId has no direction $routeSequence")
                instants.forEach { instant ->
                    add(realtimeDeparture(route.routeCode, destination, instant))
                }
            }
        }.sortedBy { it.departure }
    }

    private fun fetchGmbRoute(routeId: Long): GmbRoute {
        val data = JSONObject(
            http("https://data.etagmb.gov.hk/route/$routeId"),
        ).getJSONArray("data")
        val route = (0 until data.length())
            .map { index -> data.getJSONObject(index) }
            .firstOrNull { item -> item.optLong("route_id", -1L) == routeId }
            ?: throw IOException("GMB route $routeId was not found")
        val routeCode = route.getString("route_code").trim()
        if (routeCode.isBlank()) throw IOException("GMB route $routeId has no route code")

        val directions = route.getJSONArray("directions")
        val destinations = buildMap {
            for (index in 0 until directions.length()) {
                val direction = directions.getJSONObject(index)
                put(direction.getInt("route_seq"), direction.getString("dest_en").trim())
            }
        }
        return GmbRoute(routeCode, destinations)
    }

    private fun parseBusDepartures(json: String): List<TransitDeparture> {
        val data = JSONObject(json).getJSONArray("data")
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.getJSONObject(index)
                val instant = parseEta(item.optString("eta")) ?: continue
                val route = item.getString("route").trim()
                if (route.isBlank()) throw IOException("Realtime ETA has no route code")
                add(
                    realtimeDeparture(
                        route = route,
                        destination = item.getString("dest_en").trim(),
                        instant = instant,
                    ),
                )
            }
        }.sortedBy { it.departure }
    }

    private fun parseGmbInstants(eta: JSONArray): List<Instant> = buildList {
        for (index in 0 until eta.length()) {
            val instant = parseEta(eta.getJSONObject(index).optString("timestamp")) ?: continue
            add(instant)
        }
    }

    private fun realtimeDeparture(
        route: String,
        destination: String,
        instant: Instant,
    ) = TransitDeparture(
        mode = "BUS",
        routeShortName = route,
        headsign = titleCaseIfUppercase(destination),
        departure = instant,
        scheduledDeparture = instant,
        cancelled = false,
    )

    private fun parseEta(value: String): Instant? =
        value.trim().takeIf { it.isNotBlank() }?.let { timestamp ->
            runCatching { OffsetDateTime.parse(timestamp).toInstant() }.getOrNull()
        }

    private fun titleCaseIfUppercase(value: String): String {
        val trimmed = value.trim()
        if (trimmed.none { it.isLetter() } || trimmed != trimmed.uppercase(Locale.ENGLISH)) {
            return trimmed
        }
        return trimmed.lowercase(Locale.ENGLISH).replace(WORD_START) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase(Locale.ENGLISH)
        }
    }

    private fun parseStopId(stopId: String): HkStop? {
        val match = HK_STOP_ID.matchEntire(stopId) ?: return null
        return HkStop(
            operator = Operator.valueOf(match.groupValues[1]),
            operatorStopId = match.groupValues[2],
        )
    }

    private enum class Operator {
        KMB,
        CTB,
        GMB,
    }

    private data class HkStop(
        val operator: Operator,
        val operatorStopId: String,
    )

    private data class GmbRoute(
        val routeCode: String,
        val destinations: Map<Int, String>,
    )

    private companion object {
        const val MAX_CTB_ROUTES = 8
        val HK_STOP_ID = Regex("^hk-[^_]*(?:_[^_]*)*_(KMB|CTB|GMB)-(.+)$")
        val WORD_START = Regex("(^|[^A-Za-z])([a-z])")
        val GMB_ROUTE_CACHE = ConcurrentHashMap<Long, GmbRoute>()
    }
}
