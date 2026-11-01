package io.prendie.subweazl

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

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
        if (parts.size != 2 || parts.any { !it.matches(Regex("[0-9a-f]{64}")) })
            throw FileNotFoundException("Invalid artwork")
        val file = ctx.repo.file(parts[0], "art/${parts[1]}")
        if (!file.exists()) {
            val cover = uri.getQueryParameter("cover") ?: throw FileNotFoundException("Artwork not cached")
            if (hash(cover) != parts[1] || parts[0] != ctx.repo.active.value?.key || ctx.repo.offline.value)
                throw FileNotFoundException("Artwork unavailable")
            // Provider requests run on Binder threads, never the playback or UI thread.
            ctx.repo.artwork(parts[0], cover) ?: throw FileNotFoundException("Artwork unavailable")
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
    .authority("io.prendie.subweazl.artwork").appendPath(account).appendPath(hash(coverId)).appendQueryParameter("cover", coverId).build()
