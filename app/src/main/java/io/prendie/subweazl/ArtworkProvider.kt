package io.prendie.subweazl

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

/** Exposes only cached artwork, read-only, to trusted system media controllers. */
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val trusted = uid == ctx.applicationInfo.uid || ctx.packageManager.getPackagesForUid(uid).orEmpty().any {
            ctx.getSystemService(MediaSessionManager::class.java).isTrustedForMediaControl(
                MediaSessionManager.RemoteUserInfo(it, Binder.getCallingPid(), uid))
        }
        if (!trusted || mode != "r") throw SecurityException("Artwork is read-only for trusted media hosts")
        val parts = uri.pathSegments
        if (parts.size == 2 && parts[0] == "icon") return openIcon(parts[1])
        if (parts.size != 2 || parts.any { !it.matches(Regex("[0-9a-f]{64}")) })
            throw FileNotFoundException("Invalid artwork")
        val file = ctx.repo.file(parts[0], "art/${parts[1]}")
        val fallback = uri.getQueryParameter("fallback")?.takeIf { it == "playlist" }
        if (!file.exists()) {
            val cover = uri.getQueryParameter("cover") ?: throw FileNotFoundException("Artwork not cached")
            if (hash(cover) != parts[1]) throw FileNotFoundException("Invalid artwork")
            if (parts[0] == ctx.repo.active.value?.key && !ctx.repo.offline.value) {
                try { ctx.repo.artwork(parts[0], cover) }
                catch (e: Exception) { if (fallback == null) throw FileNotFoundException("Artwork unavailable").apply { initCause(e) } }
            }
            if (!file.exists()) return fallback?.let(::openIcon) ?: throw FileNotFoundException("Artwork unavailable")
        }
        if (fallback != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return openIcon(fallback)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    @Synchronized private fun openIcon(name: String): ParcelFileDescriptor {
        val ctx = requireNotNull(context)
        val resource = when (name) {
            "playlist" -> R.drawable.auto_playlist
            "needle" -> R.drawable.auto_needle
            else -> throw FileNotFoundException("Unknown artwork icon")
        }
        val file = File(ctx.cacheDir, "auto-art-$name.png")
        if (!file.exists()) {
            val bitmap = requireNotNull(ContextCompat.getDrawable(ctx, resource)).toBitmap(256, 256)
            val temporary = File(file.path + ".part")
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temporary.renameTo(file)) throw FileNotFoundException("Unable to cache icon")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "image/*"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}

fun artworkUri(account: String, coverId: String): Uri = Uri.Builder().scheme("content")
    .authority("${BuildConfig.APPLICATION_ID}.artwork").appendPath(account).appendPath(hash(coverId)).appendQueryParameter("cover", coverId).build()

fun autoIconUri(name: String): Uri = Uri.Builder().scheme("content")
    .authority("${BuildConfig.APPLICATION_ID}.artwork").appendPath("icon").appendPath(name).build()
