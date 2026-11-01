@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package io.prendie.subweazl

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun directRadioUrl(value: String): String? = value.toHttpUrlOrNull()?.takeIf {
    it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
}?.toString()

/** Only relay gateway failures permit a single, credential-free attempt at the configured stream.
 * Both transports refuse redirects. Neither a sign-in failure nor arbitrary URLs trigger fallback. */
class RadioGatewayDataSource(
    private val relay: DataSource,
    private val direct: DataSource,
    private val fallback: (DataSpec) -> String?,
) : DataSource {
    private var active = relay
    override fun addTransferListener(listener: TransferListener) {
        relay.addTransferListener(listener); direct.addTransferListener(listener)
    }
    override fun open(dataSpec: DataSpec): Long {
        active = relay
        try { return relay.open(dataSpec) }
        catch (error: IOException) {
            if (playbackHttpStatus(error) !in listOf(502, 503, 504)) throw error
            val url = fallback(dataSpec) ?: throw error
            val clean = dataSpec.buildUpon().setUri(url).setHttpRequestHeaders(emptyMap())
                .setHttpMethod(DataSpec.HTTP_METHOD_GET).setHttpBody(null).setKey(null)
                .setPosition(0).setLength(androidx.media3.common.C.LENGTH_UNSET.toLong()).build()
            runCatching { relay.close() }
            active = direct
            return direct.open(clean)
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = active.read(buffer, offset, length)
    override fun getUri() = active.uri
    override fun getResponseHeaders() = active.responseHeaders
    override fun close() = active.close()
}
