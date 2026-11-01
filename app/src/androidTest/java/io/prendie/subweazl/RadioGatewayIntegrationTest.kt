@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package io.prendie.subweazl

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RadioGatewayIntegrationTest {
    @Test fun gatewayFallbackNeverForwardsAccountHeadersOrLeaseQuery() {
        MockWebServer().use { relay -> MockWebServer().use { direct ->
            relay.enqueue(MockResponse().setResponseCode(502))
            direct.enqueue(MockResponse().setBody("audio"))
            val source = RadioGatewayDataSource(OkHttpDataSource.Factory(Api.http).createDataSource(),
                OkHttpDataSource.Factory(Api.http).createDataSource()) { direct.url("/radio.mp3").toString() }
            val spec = DataSpec.Builder().setUri(Uri.parse(relay.url("/audio?lease=private").toString()))
                .setHttpRequestHeaders(mapOf("Authorization" to "Bearer fixture-secret", "X-Proxy-Secret" to "fixture"))
                .build()
            try {
                source.open(spec)
                assertEquals(5, source.read(ByteArray(10),0,10))
                val request = direct.takeRequest()
                assertEquals("/radio.mp3", request.path)
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("X-Proxy-Secret"))
                assertTrue(relay.takeRequest().getHeader("Authorization")!!.contains("fixture-secret"))
            } finally { source.close() }
        } }
    }
    @Test fun authenticationFailuresNeverFallBackAndDirectErrorsRemainVisible() {
        MockWebServer().use { relay -> MockWebServer().use { direct ->
            relay.enqueue(MockResponse().setResponseCode(401))
            val source = RadioGatewayDataSource(OkHttpDataSource.Factory(Api.http).createDataSource(),
                OkHttpDataSource.Factory(Api.http).createDataSource()) { direct.url("/").toString() }
            try {
                val error = assertThrows(java.io.IOException::class.java) {
                    source.open(DataSpec(Uri.parse(relay.url("/").toString())))
                }
                assertEquals(401, playbackHttpStatus(error))
                assertEquals(0, direct.requestCount)
            } finally { source.close() }
            relay.enqueue(MockResponse().setResponseCode(502)); direct.enqueue(MockResponse().setResponseCode(403))
            val again = RadioGatewayDataSource(OkHttpDataSource.Factory(Api.http).createDataSource(),
                OkHttpDataSource.Factory(Api.http).createDataSource()) { direct.url("/").toString() }
            try {
                val error = assertThrows(java.io.IOException::class.java) { again.open(DataSpec(Uri.parse(relay.url("/").toString()))) }
                assertTrue(playbackFailureMessage(error,"IO_BAD_HTTP_STATUS").contains("HTTP 403"))
            } finally { again.close() }
        } }
    }
}
