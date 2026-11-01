package io.prendie.subweazl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

private val fileLock = Any()

fun atomicWrite(file: File, text: String) =
    synchronized(fileLock) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(text.toByteArray())
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

fun readJson(file: File): JSONObject? =
    synchronized(fileLock) {
        if (!file.exists()) null
        else JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    }

class Vault(context: Context) {
    private val dir = File(context.noBackupFilesDir, "vault").apply { mkdirs() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("subweazl.accounts", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "subweazl.accounts",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    @Synchronized
    fun read(id: String): JSONObject? {
        val j = readJson(File(dir, hash(id))) ?: return null
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, Base64.decode(j.getString("iv"), Base64.NO_WRAP)),
                )
            }
        return JSONObject(
            String(cipher.doFinal(Base64.decode(j.getString("value"), Base64.NO_WRAP)))
        )
    }

    @Synchronized
    fun write(id: String, value: JSONObject) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        atomicWrite(
            File(dir, hash(id)),
            obj(
                    "iv" to Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                    "value" to
                        Base64.encodeToString(
                            cipher.doFinal(value.toString().toByteArray()),
                            Base64.NO_WRAP,
                        ),
                )
                .toString(),
        )
    }

    @Synchronized
    fun remove(id: String) {
        File(dir, hash(id)).delete()
    }
}
