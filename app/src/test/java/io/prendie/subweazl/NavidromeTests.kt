package io.prendie.subweazl

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NavidromeTests {
    @Test fun existingAccountsKeepWeazlMusicAndNewAccountsRememberProtocol() {
        val legacy = obj("key" to "existing", "home" to "https://music.example")
        assertEquals("weazlmusic", Account.parse(legacy).serverType)
        val direct = Account.parse(legacy.put("serverType", "navidrome"))
        assertEquals("navidrome", Account.parse(direct.json()).serverType)
    }

    @Test fun authenticationUsesSaltedTokenAndPostWithoutPassword() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok"}}"""))
            val auth = Navidrome.authentication("isolated-password")
            assertNotEquals(auth.getString("s"), Navidrome.authentication("isolated-password").getString("s"))
            Navidrome(server.url("/music/"), "test user", auth, emptyMap()).ping()
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/music/rest/ping.view", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("t=${auth.getString("t")}"))
            assertFalse(body.contains("isolated-password"))
        }
    }
    @Test fun redirectsNeverForwardCredentials() {
        MockWebServer().use { origin -> MockWebServer().use { other ->
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/stolen")))
            val nav = Navidrome(origin.url("/"), "fixture", Navidrome.authentication("secret"), mapOf("X-Test" to "private"))
            val error = assertThrows(ApiError::class.java) { nav.ping() }
            assertEquals(302, error.status)
            assertEquals(0, other.requestCount)
            assertFalse(error.message!!.contains("secret"))
        } }
    }
    @Test fun authenticationFailureIsActionableWithoutServerText() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"private details"}}}"""))
            val nav = Navidrome(server.url("/"), "fixture", Navidrome.authentication("secret"), emptyMap())
            val error = assertThrows(ApiError::class.java) { nav.ping() }
            assertEquals("unauthorized", error.code)
            assertFalse(error.message!!.contains("private"))
        }
    }
}
