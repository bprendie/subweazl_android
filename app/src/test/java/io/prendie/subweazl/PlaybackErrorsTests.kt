package io.prendie.subweazl

import org.junit.Assert.*
import org.junit.Test

class PlaybackErrorsTests {
    @Test fun nestedGatewayFailureKeepsStatusWithoutExposingServerResponse() {
        val error = RuntimeException(java.io.IOException(ApiError("http_502", "private upstream response", 502)))
        val message = playbackFailureMessage(error, "ERROR_CODE_IO_UNSPECIFIED")
        assertTrue(message.contains("HTTP 502"))
        assertFalse(message.contains("private"))
    }
    @Test fun authenticationFailureAndNonHttpFallbackAreDistinct() {
        assertTrue(playbackFailureMessage(ApiError("unauthorized", "private", 401), "IO").contains("account connection"))
        assertEquals("Playback failed. error code io unspecified",
            playbackFailureMessage(java.io.IOException(), "ERROR_CODE_IO_UNSPECIFIED"))
    }
}
