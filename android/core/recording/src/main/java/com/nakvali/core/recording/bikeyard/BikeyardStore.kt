package com.nakvali.core.recording.bikeyard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

internal interface BikeyardStore {
    fun read(): BikeyardStoredState
    fun write(state: BikeyardStoredState)
}

/** Both the key and ciphertext are device-local; never part of the ride backup. */
internal class BikeyardEncryptedStore(context: Context) : BikeyardStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "bikeyard/state.enc"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val alias = "nakvali.bikeyard.v1"

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    override fun read(): BikeyardStoredState {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return BikeyardStoredState()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Invalid BIKEYARD credential file" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return json.decodeFromString(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
    }

    override fun write(state: BikeyardStoredState) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(json.encodeToString(state).toByteArray())
        file.baseFile.parentFile!!.mkdirs()
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}
