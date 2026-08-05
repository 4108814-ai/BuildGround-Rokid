package com.anezium.rokidbus.plugin.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class HkRealtimeEtaTest {
    @Test
    fun overlay_matchesSupportedStopTailsAndRejectsMtrAndNonHongKongStops() {
        val calls = mutableListOf<String>()
        val overlay = HkRealtimeEta { url ->
            calls += url
            when {
                "/kmb/stop-eta/" in url -> """{"data":[]}"""
                "/citybus/eta/" in url -> """{"data":[]}"""
                "/eta/stop/" in url -> """{"data":[]}"""
                else -> error("Unexpected URL: $url")
            }
        }
        val scheduled = listOf(departure(route = "1", at = "2026-08-05T11:00:00Z"))

        assertEquals(
            emptyList<TransitDeparture>(),
            overlay.overlay("hk-Hong-Kong-Transit_KMB-610F9B32EEAE091D", scheduled),
        )
        assertEquals(
            emptyList<TransitDeparture>(),
            overlay.overlay("hk-replaced_feed_prefix_CTB-001775", scheduled),
        )
        assertEquals(
            emptyList<TransitDeparture>(),
            overlay.overlay("hk-Hong-Kong-Transit_GMB-20010360", scheduled),
        )
        assertEquals(3, calls.size)

        calls.clear()
        listOf(
            "hk-Hong-Kong-Transit_MTR-MOK",
            "hk-Hong-Kong-Transit_MTR-PLATFORM-MOK-4",
            "fr-idf_KMB-610F9B32EEAE091D",
        ).forEach { stopId ->
            assertNull(overlay.overlay(stopId, scheduled))
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun overlay_mapsAndSortsKmbEtasWhileSkippingMissingAndInvalidTimestamps() {
        val overlay = HkRealtimeEta {
            """
                {
                  "type":"StopETA",
                  "version":"1.0",
                  "generated_timestamp":"2026-08-05T19:08:08+08:00",
                  "data":[
                    {"co":"KMB","route":"42A","dir":"I","service_type":1,"seq":7,
                     "dest_en":"TSING YI (CHEUNG HANG ESTATE)","eta_seq":3,
                     "eta":"2026-08-05T19:21:40+08:00","rmk_en":"Scheduled Bus"},
                    {"co":"KMB","route":"42A","dir":"I","dest_en":"IGNORED",
                     "eta_seq":4,"eta":null,"rmk_en":""},
                    {"co":"KMB","route":"42A","dir":"I","dest_en":"IGNORED",
                     "eta_seq":5,"eta":"not-a-timestamp","rmk_en":""},
                    {"co":"KMB","route":"42A","dir":"I","service_type":1,"seq":7,
                     "dest_en":"TSING YI (CHEUNG HANG ESTATE)","eta_seq":1,
                     "eta":"2026-08-05T19:12:27+08:00","rmk_en":""}
                  ]
                }
            """.trimIndent()
        }

        val result = overlay.overlay("hk-Hong-Kong-Transit_KMB-610F9B32EEAE091D", emptyList())
            ?: error("Expected a realtime KMB result")

        assertEquals(
            listOf(
                Instant.parse("2026-08-05T11:12:27Z"),
                Instant.parse("2026-08-05T11:21:40Z"),
            ),
            result.map { it.departure },
        )
        assertEquals(listOf("42A", "42A"), result.map { it.routeShortName })
        assertEquals(listOf("Tsing Yi (Cheung Hang Estate)", "Tsing Yi (Cheung Hang Estate)"), result.map { it.headsign })
        assertTrue(result.all { it.mode == "BUS" && it.scheduledDeparture == it.departure })
        assertFalse(result.any { it.cancelled })
    }

    @Test
    fun overlay_derivesDistinctCtbRoutesBySoonestScheduleAndCapsAtEight() {
        val requestedRoutes = mutableListOf<String>()
        val overlay = HkRealtimeEta { url ->
            requestedRoutes += url.substringAfterLast('/')
            """{"type":"ETA","version":"1.0","data":[]}"""
        }
        val scheduled = buildList {
            add(departure(route = " ", at = "2026-08-05T10:00:00Z"))
            for (route in 9 downTo 1) {
                add(
                    departure(
                        route = route.toString(),
                        at = "2026-08-05T12:00:00Z",
                        scheduledAt = "2026-08-05T11:${route.toString().padStart(2, '0')}:00Z",
                    ),
                )
            }
            add(
                departure(
                    route = "1",
                    at = "2026-08-05T13:00:00Z",
                    scheduledAt = "2026-08-05T12:59:00Z",
                ),
            )
        }

        assertEquals(
            emptyList<TransitDeparture>(),
            overlay.overlay("hk-Hong-Kong-Transit_CTB-001775", scheduled),
        )
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), requestedRoutes)
    }

    @Test
    fun overlay_abortsCtbBoardWhenAnyRouteFetchFails() {
        val calls = mutableListOf<String>()
        val overlay = HkRealtimeEta { url ->
            val route = url.substringAfterLast('/')
            calls += route
            if (route == "2") throw IOException("Citybus unavailable")
            """{"data":[]}"""
        }
        val scheduled = listOf(
            departure(route = "1", at = "2026-08-05T11:01:00Z"),
            departure(route = "2", at = "2026-08-05T11:02:00Z"),
            departure(route = "3", at = "2026-08-05T11:03:00Z"),
        )

        assertNull(overlay.overlay("hk-Hong-Kong-Transit_CTB-001775", scheduled))
        assertEquals(listOf("1", "2"), calls)
    }

    @Test
    fun overlay_resolvesGmbDirectionAndCachesRouteMetadataAcrossCalls() {
        var stopCalls = 0
        var routeCalls = 0
        val routeId = 9_876_543L
        val overlay = HkRealtimeEta { url ->
            when {
                "/eta/stop/" in url -> {
                    stopCalls += 1
                    """
                        {"type":"ETA-Stop","version":"1.0","data":[
                          {"route_id":$routeId,"route_seq":2,"stop_seq":6,"enabled":true,"eta":[
                            {"eta_seq":1,"diff":1,"timestamp":"2026-08-05T19:19:57.555+08:00","remarks_en":null},
                            {"eta_seq":2,"diff":13,"timestamp":"2026-08-05T19:31:42.285+08:00","remarks_en":"Scheduled"}
                          ]}
                        ]}
                    """.trimIndent()
                }
                "/route/$routeId" in url -> {
                    routeCalls += 1
                    """
                        {"type":"Route","version":"1.0","data":[
                          {"route_id":$routeId,"region":"KLN","route_code":"5M","directions":[
                            {"route_seq":1,"orig_en":"Mong Kok Station","dest_en":"Waterloo Hill (Hok Yu Lane)"},
                            {"route_seq":2,"orig_en":"Waterloo Hill (Hok Yu Lane)","dest_en":"Mong Kok Station(Circular)"}
                          ]}
                        ]}
                    """.trimIndent()
                }
                else -> error("Unexpected URL: $url")
            }
        }

        val first = overlay.overlay("hk-Hong-Kong-Transit_GMB-20010360", emptyList())
            ?: error("Expected a realtime GMB result")
        val second = overlay.overlay("hk-Hong-Kong-Transit_GMB-20010360", emptyList())
            ?: error("Expected the cached realtime GMB result")

        assertEquals(2, stopCalls)
        assertEquals(1, routeCalls)
        assertEquals(first, second)
        assertEquals(listOf("5M", "5M"), first.map { it.routeShortName })
        assertEquals(listOf("Mong Kok Station(Circular)", "Mong Kok Station(Circular)"), first.map { it.headsign })
        assertEquals(
            listOf(
                Instant.parse("2026-08-05T11:19:57.555Z"),
                Instant.parse("2026-08-05T11:31:42.285Z"),
            ),
            first.map { it.departure },
        )
    }

    @Test
    fun overlay_returnsEmptyForSuccessfulEmptyRealtimePayload() {
        val overlay = HkRealtimeEta { """{"type":"StopETA","version":"1.0","data":[]}""" }

        assertEquals(
            emptyList<TransitDeparture>(),
            overlay.overlay("hk-Hong-Kong-Transit_KMB-610F9B32EEAE091D", emptyList()),
        )
    }

    private fun departure(
        route: String?,
        at: String,
        scheduledAt: String = at,
    ) = TransitDeparture(
        mode = "BUS",
        routeShortName = route,
        headsign = "Scheduled destination",
        departure = Instant.parse(at),
        scheduledDeparture = Instant.parse(scheduledAt),
        cancelled = false,
    )
}
