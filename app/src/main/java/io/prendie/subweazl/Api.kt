package io.prendie.subweazl

import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ApiError(val code: String, message: String, val status: Int = 0) : IOException(message)

data class Reply(val json: JSONObject, val etag: String?) {
    val data
        get() = json.optJSONObject("data") ?: JSONObject()
}

class Api(val account: Account, private val vault: Vault, private val allowed: () -> Boolean) {
    companion object {
        val http =
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

        fun base(value: String): HttpUrl {
            val url = value.trim().trimEnd('/').toHttpUrl()
            require(
                url.username.isEmpty() &&
                    url.password.isEmpty() &&
                    url.query == null &&
                    url.fragment == null
            ) {
                "Use a server URL without credentials, query, or fragment"
            }
            return url
        }

        fun endpoint(base: HttpUrl, path: String) =
            base.newBuilder().addEncodedPathSegments("api/v1/" + path.trimStart('/')).build()

        fun path(id: String) =
            HttpUrl.Builder()
                .scheme("https")
                .host("id.invalid")
                .addPathSegment(id)
                .build()
                .encodedPath
                .removePrefix("/")

        fun execute(
            url: HttpUrl,
            method: String = "GET",
            body: JSONObject? = null,
            headers: Map<String, String> = emptyMap(),
            token: String? = null,
            key: String? = null,
            etag: String? = null,
        ): Reply {
            val request =
                Request.Builder()
                    .url(url)
                    .header("X-Weazl-Request", "1")
                    .header("Accept", "application/json")
            headers.forEach { (k, v) -> request.header(k, v) }
            token?.let { request.header("Authorization", "Bearer $it") }
            key?.let { request.header("Idempotency-Key", it) }
            etag?.let { request.header("If-Match", it) }
            request.method(
                method,
                if (method in listOf("GET", "HEAD")) null
                else (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()),
            )
            http.newCall(request.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val parsed = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                if (!response.isSuccessful) {
                    val error = parsed.optJSONObject("error")
                    throw ApiError(
                        error?.str("code") ?: "http_${response.code}",
                        error?.str("message")?.takeIf { it.isNotBlank() }
                            ?: "Server request failed (${response.code})",
                        response.code,
                    )
                }
                if (raw.isNotBlank() && !parsed.has("data"))
                    throw ApiError(
                        "invalid_response",
                        "The server returned an invalid API response",
                    )
                return Reply(parsed, response.header("ETag"))
            }
        }

        fun headerMap(j: JSONObject) = j.keys().asSequence().associateWith { j.getString(it) }

        fun parseHeaders(text: String): JSONObject =
            JSONObject().apply {
                text
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        require(line.contains(':')) { "Use one Name: value per header" }
                        val name = line.substringBefore(':').trim()
                        val value = line.substringAfter(':').trim()
                        require(
                            name.lowercase() !in
                                listOf(
                                    "authorization",
                                    "cookie",
                                    "host",
                                    "content-length",
                                    "connection",
                                    "x-weazl-request",
                                )
                        ) {
                            "Header $name is reserved"
                        }
                        put(name, value)
                    }
            }

        fun login(
            vault: Vault,
            name: String,
            home: String,
            remote: String,
            prefer: Boolean,
            username: String,
            password: String,
            device: String,
            headers: JSONObject,
            serverType: String = "weazlmusic",
        ): Account {
            if (serverType == "navidrome") {
                require(remote.isBlank()) { "Use one URL for a direct Navidrome account; alternate origins require a separate account." }
                val origin = base(home)
                val auth = Navidrome.authentication(password)
                Navidrome(origin, username, auth, headerMap(headers)).ping()
                val identity = hash("navidrome|" + origin.toString() + "|" + username)
                val account = Account(identity, name.ifBlank { username }, origin.toString(), "", false,
                    username, identity, username, identity, "navidrome")
                vault.write(identity, obj("navidrome" to auth, "headers" to headers))
                return account
            }
            require(serverType == "weazlmusic") { "Unknown server type" }
            val urls =
                listOfNotNull(base(home), remote.takeIf { it.isNotBlank() }?.let(::base)).let {
                    if (prefer) it.reversed() else it
                }
            var chosen: HttpUrl? = null
            var installation: String? = null
            var failure: Exception? = null
            for (url in urls) {
                try {
                    val info = execute(endpoint(url, "info"), headers = headerMap(headers)).data
                    require(info.optInt("apiVersion") == 1) {
                        "This server does not support WeazlTunes v1"
                    }
                    if (installation != null)
                        require(installation == info.getString("installationId")) {
                            "Home and remote URLs identify different installations"
                        }
                    else {
                        chosen = url
                        installation = info.getString("installationId")
                    }
                } catch (e: IOException) {
                    failure = e
                }
            }
            val selected = chosen ?: throw (failure ?: IOException("Server unavailable"))
            val credentials =
                execute(
                        endpoint(selected, "auth/login"),
                        "POST",
                        obj(
                            "username" to username,
                            "password" to password,
                            "deviceName" to device,
                            "clientId" to "subweazl-android",
                        ),
                        headerMap(headers),
                    )
                    .data
            val accountId = credentials.getString("accountId")
            val libraryId = credentials.getString("libraryId")
            val id =
                hash(
                    listOf(installation!!, accountId, libraryId).joinToString("") {
                        "${it.length}:$it"
                    }
                )
            val account =
                Account(
                    id,
                    name.ifBlank { username },
                    base(home).toString(),
                    remote.takeIf { it.isNotBlank() }?.let { base(it).toString() }.orEmpty(),
                    prefer,
                    username,
                    installation,
                    accountId,
                    libraryId,
                )
            vault.write(id, obj("credentials" to credentials, "headers" to headers))
            return account
        }
    }

    fun navidrome(): Navidrome {
        if (!allowed()) throw ApiError("offline", "Offline mode is enabled")
        val secrets = vault.read(account.key) ?: throw ApiError("unauthorized", "Sign in again")
        return Navidrome(base(account.home), account.username, secrets.getJSONObject("navidrome"),
            headerMap(secrets.optJSONObject("headers") ?: JSONObject()))
    }

    @Volatile private var selected: HttpUrl? = null
    private var verifiedAt = 0L

    @Synchronized
    fun connect(): HttpUrl {
        if (!allowed()) throw ApiError("offline", "Offline mode is enabled")
        if (selected != null && System.currentTimeMillis() - verifiedAt < 60_000) return selected!!
        val secrets =
            vault.read(account.key)
                ?: throw ApiError("unauthorized", "Sign in again; saved music remains available")
        val urls =
            listOfNotNull(
                    base(account.home),
                    account.remote.takeIf { it.isNotBlank() }?.let(::base),
                )
                .let { if (account.preferRemote) it.reversed() else it }
        var failure: IOException? = null
        for (url in urls) {
            try {
                val info =
                    execute(
                            endpoint(url, "info"),
                            headers = headerMap(secrets.getJSONObject("headers")),
                        )
                        .data
                if (info.str("installationId") != account.installation)
                    throw ApiError(
                        "identity_changed",
                        "Server identity changed. Sign in as a new account.",
                    )
                selected = url
                verifiedAt = System.currentTimeMillis()
                return url
            } catch (e: ApiError) {
                throw e
            } catch (e: IOException) {
                failure = e
            }
        }
        throw (failure ?: IOException("Unable to connect"))
    }

    @Synchronized
    private fun credentials(force: Boolean = false): JSONObject {
        val saved = vault.read(account.key) ?: throw ApiError("unauthorized", "Sign in again")
        var c = saved.getJSONObject("credentials")
        if (
            force ||
                Instant.parse(c.getString("accessExpiresAt"))
                    .isBefore(Instant.now().plusSeconds(60))
        ) {
            if (Instant.parse(c.getString("refreshExpiresAt")).isBefore(Instant.now()))
                throw ApiError("session_revoked", "Your session expired. Sign in again.")
            val key = c.str("pendingRefreshKey").ifBlank { UUID.randomUUID().toString() }
            c.put("pendingRefreshKey", key)
            vault.write(account.key, saved)
            val fresh =
                execute(
                        endpoint(connect(), "auth/refresh"),
                        "POST",
                        obj("refreshToken" to c.getString("refreshToken")),
                        headerMap(saved.getJSONObject("headers")),
                        key = key,
                    )
                    .data
            require(
                fresh.str("accountId") == account.accountId &&
                    fresh.str("libraryId") == account.libraryId &&
                    fresh.str("deviceId") == c.str("deviceId")
            ) {
                "Account identity changed"
            }
            c = fresh
            saved.put("credentials", fresh)
            vault.write(account.key, saved)
        }
        return c
    }

    fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        key: String? = null,
        etag: String? = null,
        query: Map<String, String> = emptyMap(),
    ): Reply {
        if (account.serverType == "navidrome") return navidrome().request(path, method, body, etag)
        val origin = connect()
        val url =
            endpoint(origin, path)
                .newBuilder()
                .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
        val mutationKey = if (method == "GET") null else key ?: UUID.randomUUID().toString()
        val headers = headerMap(vault.read(account.key)!!.getJSONObject("headers"))
        fun send(c: JSONObject) =
            execute(url, method, body, headers, c.getString("accessToken"), mutationKey, etag)
        return try {
            send(credentials())
        } catch (e: ApiError) {
            if (e.status == 401) send(credentials(true)) else throw e
        } catch (e: IOException) {
            selected = null
            throw e
        }
    }

    fun pages(path: String, query: Map<String, String> = emptyMap()): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var cursor = ""
        var revision: String? = null
        val seen = mutableSetOf<String>()
        do {
            val response =
                request(
                        path,
                        query =
                            query +
                                mapOf("limit" to "200") +
                                if (cursor.isBlank()) emptyMap() else mapOf("cursor" to cursor),
                    )
                    .json
            val nextRevision = response.str("snapshotRevision")
            if (revision != null && revision != nextRevision)
                throw ApiError("snapshot_expired", "Library changed during sync. Please retry.")
            revision = nextRevision
            result.addAll(response.rows("data"))
            cursor = response.str("nextCursor")
            if (cursor.isNotEmpty() && !seen.add(cursor))
                throw ApiError("invalid_response", "Server repeated a page cursor")
        } while (cursor.isNotEmpty())
        return result
    }

    fun lease(kind: String, id: String, quality: String = "original"): JSONObject {
        if (account.serverType == "navidrome") {
            val command = when(kind) { "trackStream" -> "stream"; "trackOriginal" -> "download"; "cover" -> "getCoverArt"
                else -> throw ApiError("unsupported", "This feature requires a WeazlMusic server.") }
            val params = listOf("id" to id) + if (kind == "trackStream") listOf("format" to "raw") else emptyList()
            return obj("url" to navidrome().url(command, params).toString())
        }
        val lease =
            request(
                    "media/leases",
                    "POST",
                    obj("kind" to kind, "resourceId" to id, "quality" to quality),
                )
                .data
        val raw = lease.getString("url")
        val origin = connect()
        val resolved = origin.resolve(raw) ?: throw IOException("Invalid media URL")
        require(
            resolved.scheme == origin.scheme &&
                resolved.host == origin.host &&
                resolved.port == origin.port &&
                resolved.username.isEmpty() &&
                resolved.password.isEmpty()
        ) {
            "Media lease must remain on the verified server"
        }
        lease.put("url", resolved.toString())
        return lease
    }

    suspend fun radioEvents(playbackId: String, receive: (JSONObject) -> Unit) =
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            val url =
                endpoint(connect(), "radio/events")
                    .newBuilder()
                    .addQueryParameter("playbackId", playbackId)
                    .build()
            val request =
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + credentials().getString("accessToken"))
                    .header("Accept", "text/event-stream")
                    .header("X-Weazl-Request", "1")
            mediaHeaders().forEach { (k, v) -> request.header(k, v) }
            val call = http.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        try {
                            response.use { r ->
                                if (!r.isSuccessful) throw IOException("Radio metadata unavailable")
                                val source =
                                    r.body?.source() ?: throw IOException("No metadata response")
                                while (continuation.isActive) {
                                    val line = source.readUtf8Line() ?: break
                                    if (line.startsWith("data:"))
                                        runCatching {
                                                JSONObject(line.removePrefix("data:").trim())
                                            }
                                            .getOrNull()
                                            ?.let(receive)
                                }
                            }
                            if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                        }
                    }
                }
            )
        }

    fun mediaHeaders(): Map<String, String> =
        headerMap(vault.read(account.key)?.optJSONObject("headers") ?: JSONObject())
}
