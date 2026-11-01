package io.prendie.subweazl

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContractTests {
    private fun segment(start: Long, duration: Long, id: String = "s") =
        obj(
            "id" to id,
            "assetId" to "a$id",
            "startMs" to start,
            "durationMs" to duration,
            "mediaStartMs" to 10,
            "byteLength" to 100,
            "sha256" to "a".repeat(64),
        )

    private fun manifest(vararg segments: JSONObject) =
        obj(
            "schemaVersion" to 1,
            "sessionId" to "f",
            "revision" to "r",
            "name" to "Test",
            "durationMs" to 10000,
            "tracks" to
                JSONArray(
                    listOf(
                        obj(
                            "stationId" to "p",
                            "name" to "Preset",
                            "segments" to JSONArray(segments.toList()),
                        )
                    )
                ),
        )

    @Test
    fun sharedTimelinePreservesGapsAndMediaOffsets() {
        val f = Flight.parse(manifest(segment(1000, 3000), segment(6000, 4000, "s2")))
        assertTrue(f.hasGaps)
        assertNull(f.segmentAt("p", 500))
        assertEquals(
            10L + 2500 - 1000,
            f.segmentAt("p", 2500)!!.let { it.mediaStart + 2500 - it.start },
        )
        assertNull(f.segmentAt("p", 4000))
        assertEquals("s2", f.segmentAt("p", 6000)!!.id)
        assertNull(f.segmentAt("p", 10000))
    }

    @Test
    fun rejectsOverlappingRecordingSegments() {
        assertThrows(IllegalArgumentException::class.java) {
            Flight.parse(manifest(segment(0, 5000), segment(4000, 6000, "s2")))
        }
    }

    @Test
    fun rejectsBadIntegrityAndOutOfBoundsCoverage() {
        assertThrows(IllegalArgumentException::class.java) {
            Flight.parse(manifest(segment(0, 11000)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Flight.parse(manifest(segment(0, 10000).put("sha256", "bad")))
        }
    }

    @Test
    fun completeRecordingDoesNotReportGaps() {
        assertFalse(Flight.parse(manifest(segment(0, 10000))).hasGaps)
    }

    @Test
    fun searchHandlesAccentsPunctuationAndWordOrder() {
        assertTrue(matches("rem orange", "R.E.M.", "Orange Crush"))
        assertTrue(matches("r e m orange", "R.E.M.", "Orange Crush"))
        assertTrue(matches("beyonce halo", "Beyoncé", "Halo"))
        assertFalse(matches("live", "Orange Crush", "R.E.M."))
    }

    @Test
    fun nullTrackFieldsRemainSafe() {
        val track = Track.parse(obj("id" to "t", "title" to null, "durationMs" to null))
        assertEquals("Untitled", track.title)
        assertEquals(0, track.durationMs)
        assertEquals("", track.revision)
    }

    @Test
    fun endpointPreservesServerSubpathAndEscapesIdentifiers() {
        assertEquals(
            "https://example.org/music/api/v1/library/tracks/a%2Fb",
            Api.endpoint(Api.base("https://example.org/music"), "library/tracks/" + Api.path("a/b"))
                .toString(),
        )
    }

    @Test
    fun credentialsInUrlsAndReservedHeadersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Api.base("https://u:p@example.org") }
        assertThrows(IllegalArgumentException::class.java) {
            Api.parseHeaders("Authorization: secret")
        }
    }

    @Test
    fun apiDoesNotFollowCredentialRedirects() {
        MockWebServer().use { source ->
            MockWebServer().use { dest ->
                source.start()
                dest.start()
                source.enqueue(
                    MockResponse().setResponseCode(302).setHeader("Location", dest.url("/secret"))
                )
                assertThrows(ApiError::class.java) {
                    Api.execute(source.url("/api/v1/me"), token = "fixture")
                }
                assertEquals(0, dest.requestCount)
            }
        }
    }

    @Test
    fun mapsVersionConflict() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(409)
                    .setBody("{\"error\":{\"code\":\"version_conflict\",\"message\":\"Changed\"}}")
            )
            val e = assertThrows(ApiError::class.java) { Api.execute(server.url("/test")) }
            assertEquals("version_conflict", e.code)
        }
    }
}
