package com.virkey.app.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Safe display metadata only. Authentication secrets never enter UI state. */
data class SavedWifiPc(val address: String, val name: String, val fingerprint: String)

internal data class WifiCredential(val pc: SavedWifiPc, val deviceId: String, val token: String) {
    override fun toString() = "WifiCredential(pc=$pc, credentials=[redacted])"
}

internal fun normalizedHex(value: String, length: Int): String = value.lowercase(java.util.Locale.ROOT).also {
    require(it.length == length && it.all { character -> character in "0123456789abcdef" }) { "Invalid saved pairing" }
}

internal interface WifiPairingStore {
    fun load(): WifiCredential?
    fun save(credential: WifiCredential)
    fun clear()
}

internal class MemoryWifiPairingStore : WifiPairingStore {
    private var saved: WifiCredential? = null
    @Synchronized override fun load() = saved
    @Synchronized override fun save(credential: WifiCredential) { saved = credential }
    @Synchronized override fun clear() { saved = null }
}

/** Atomic, backup-excluded storage; encryption key stays in Android Keystore. Call off the UI thread. */
internal class EncryptedWifiPairingStore(
    file: File,
    private val secretKey: () -> SecretKey,
) : WifiPairingStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "wifi-pairing.bin"), ::wifiStorageKey)
    private val storage = AtomicFile(file)

    @Synchronized override fun load(): WifiCredential? {
        val encoded = try { storage.openRead().use { it.readBytesLimited(8192) } }
            catch (_: java.io.FileNotFoundException) { return null }
        require(encoded.size in 30..8192 && encoded[0] == 1.toByte()) { "Invalid saved pairing" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
        cipher.updateAAD(AAD)
        val clear = cipher.doFinal(encoded, 13, encoded.size - 13)
        return try { parseCredential(JSONObject(String(clear, Charsets.UTF_8))) } finally { clear.fill(0) }
    }

    @Synchronized override fun save(credential: WifiCredential) {
        val json = JSONObject().put("address", credential.pc.address).put("name", credential.pc.name)
            .put("fingerprint", credential.pc.fingerprint).put("deviceId", credential.deviceId).put("token", credential.token)
        parseCredential(json) // Reject malformed or oversized records before writing.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey()) // Fresh random IV for every write.
        cipher.updateAAD(AAD)
        val clear = json.toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(clear) } finally { clear.fill(0) }
        val encoded = byteArrayOf(1) + cipher.iv + encrypted
        require(cipher.iv.size == 12 && encoded.size <= 8192)
        val output = storage.startWrite()
        try {
            output.write(encoded)
            storage.finishWrite(output)
            // AtomicFile logs some commit failures instead of throwing. Never report an
            // old credential as successfully replaced if the filesystem rejected the rename.
            val committed = storage.openRead().use { it.readBytesLimited(8192) }
            if (!committed.contentEquals(encoded)) throw IOException("Could not commit saved pairing")
        }
        catch (error: Exception) { storage.failWrite(output); throw error }
    }

    @Synchronized override fun clear() {
        storage.delete()
        if (storage.baseFile.exists()) throw IOException("Could not forget saved PC")
    }

    private fun parseCredential(json: JSONObject): WifiCredential {
        val endpoint = WifiEndpoint.parse(json.getString("address"))
        val name = json.getString("name")
        require(name.length <= 128)
        return WifiCredential(SavedWifiPc("${endpoint.host}:${endpoint.port}", name,
            normalizeFingerprint(json.getString("fingerprint"))), normalizedHex(json.getString("deviceId"), 32),
            normalizedHex(json.getString("token"), 64))
    }

    companion object { private val AAD = "Virkey Wi-Fi pairing v1".toByteArray(Charsets.UTF_8) }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val bytes = readNBytesCompat(limit + 1)
    require(bytes.size <= limit) { "Saved pairing exceeds size limit" }
    return bytes
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

@Synchronized private fun wifiStorageKey(): SecretKey {
    val alias = "virkey-wifi-pairing-v1"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generateKey()
    }
}
