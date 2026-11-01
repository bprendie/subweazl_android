package io.prendie.subweazl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

class AudioDownloadWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val repository = applicationContext.repo
            val key = inputData.getString("account") ?: return@withContext Result.failure()
            val storage = inputData.getString("storage") ?: return@withContext Result.failure()
            val recordFile = repository.recordFile(key, storage)
            val record = readJson(recordFile) ?: return@withContext Result.failure()
            val target = repository.downloadFile(key, storage)
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            fun state(status: String, error: String? = null) {
                if (recordFile.exists()) {
                    record.put("status", status).put("error", error ?: JSONObject.NULL)
                    atomicWrite(recordFile, record.toString())
                    repository.refreshLocalDownloads(key)
                }
            }
            if (repository.accountSetting(key, "offline", "false").toBoolean()) {
                state("paused")
                return@withContext Result.success()
            }
            try {
                setForeground(notification(record.str("title")))
                val client = repository.api(key)
                val lease =
                    client.lease(
                        record.str("kind"),
                        record.str("resourceId"),
                        record.str("quality", "original"),
                    )
                val expected =
                    record.optLong("expectedBytes").takeIf { it > 0 } ?: lease.optLong("byteLength")
                val expectedHash = record.str("sha256").ifBlank { lease.str("sha256") }
                val entity =
                    lease
                        .str("etag")
                        .ifBlank { lease.str("representationId") }
                        .ifBlank { expectedHash }
                if (partial.exists() && (entity.isBlank() || record.str("entity") != entity))
                    partial.delete()
                if (expected > 0 && partial.length() > expected) partial.delete()
                val offset = partial.length()
                val budget =
                    repository
                        .accountSetting(key, "budgetGiB", "8")
                        .toLongOrNull()
                        ?.times(1024L * 1024 * 1024) ?: (8L * 1024 * 1024 * 1024)
                val used = target.parentFile!!.listFiles().orEmpty().sumOf { it.length() }
                require(expected <= 0 || used + expected - offset <= budget) {
                    "Audio storage limit reached. Remove downloads or increase the limit."
                }
                require(
                    expected <= 0 ||
                        target.parentFile!!.usableSpace > expected - offset + 32L * 1024 * 1024
                ) {
                    "Not enough storage space"
                }
                record.put("expectedBytes", expected).put("entity", entity)
                state("downloading")
                val request = Request.Builder().url(lease.getString("url"))
                client.mediaHeaders().forEach { (k, v) -> request.header(k, v) }
                if (offset > 0) {
                    request.header("Range", "bytes=$offset-")
                    if (lease.str("etag").isNotEmpty())
                        request.header("If-Range", lease.str("etag"))
                }
                if (expected <= 0 || offset < expected)
                    Api.http.newCall(request.build()).execute().use { response ->
                        if (!response.isSuccessful)
                            throw ApiError(
                                "download_http",
                                "Download failed (${response.code})",
                                response.code,
                            )
                        val resumed = response.code == 206
                        if (resumed)
                            require(
                                response.header("Content-Range")?.startsWith("bytes $offset-") ==
                                    true
                            ) {
                                "Server returned the wrong download range"
                            }
                        RandomAccessFile(partial, "rw").use { output ->
                            if (!resumed) output.setLength(0) else output.seek(offset)
                            val body = response.body ?: throw IOException("Empty media response")
                            body.byteStream().use { input ->
                                val buffer = ByteArray(128 * 1024)
                                var last = 0L
                                while (true) {
                                    if (
                                        isStopped ||
                                            repository
                                                .accountSetting(key, "offline", "false")
                                                .toBoolean()
                                    )
                                        throw InterruptedException()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(used - offset + output.length() + count <= budget) {
                                        "Audio storage limit reached"
                                    }
                                    output.write(buffer, 0, count)
                                    if (expected > 0 && output.length() > expected)
                                        throw IOException("Download exceeds its declared size")
                                    if (System.currentTimeMillis() - last > 700) {
                                        record.put("bytes", output.length())
                                        state("downloading")
                                        setProgress(
                                            workDataOf(
                                                "bytes" to output.length(),
                                                "total" to expected,
                                            )
                                        )
                                        last = System.currentTimeMillis()
                                    }
                                }
                            }
                            output.fd.sync()
                        }
                    }
                require(partial.length() > 0 && (expected <= 0 || partial.length() == expected)) {
                    "Download is incomplete"
                }
                if (expectedHash.isNotEmpty() && !sha256(partial).equals(expectedHash, true)) {
                    partial.delete()
                    throw IOException(
                        "Download integrity check failed. Retry to download a fresh copy."
                    )
                }
                require(partial.renameTo(target)) { "Unable to save verified audio" }
                record.put("bytes", target.length()).put("sha256", expectedHash)
                state("complete")
                Result.success()
            } catch (e: InterruptedException) {
                state("paused")
                Result.success()
            } catch (e: Exception) {
                if (!isStopped) state("failed", repository.friendly(e))
                if (e is IOException && runAttemptCount < 3 && !isStopped) Result.retry()
                else Result.failure()
            }
        }

    private fun notification(title: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("downloads", "Music downloads", NotificationManager.IMPORTANCE_LOW)
        )
        val intent =
            PendingIntent.getActivity(
                applicationContext,
                0,
                Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(applicationContext, "downloads")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Saving offline")
                .setContentText(title)
                .setOngoing(true)
                .setContentIntent(intent)
                .build()
        return if (android.os.Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(
                id.hashCode(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        else ForegroundInfo(id.hashCode(), notification)
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
