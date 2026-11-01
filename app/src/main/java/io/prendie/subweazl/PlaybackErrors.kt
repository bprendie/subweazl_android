package io.prendie.subweazl

/** Media3 wraps API failures in a generic source/I/O exception. Retain the useful status. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun playbackHttpStatus(error: Throwable): Int? {
    val seen = mutableSetOf<Throwable>()
    var cause: Throwable? = error
    while (cause != null && seen.add(cause)) {
        if (cause is ApiError && cause.status in 400..599) return cause.status
        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return null
}

fun playbackFailureMessage(error: Throwable, fallbackCode: String): String {
    val status = playbackHttpStatus(error)
    if (status != null) return if (status == 401 || status == 403)
        "Playback was refused by the server (HTTP $status). Check your account connection."
    else "Playback failed: the music server returned HTTP $status. Try playing this track again."

    return "Playback failed. ${fallbackCode.replace('_', ' ').lowercase()}"
}
