package com.virkey.app.network

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [AtomicFileWindowsShadow::class])
class WifiPairingStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val credential = WifiCredential(SavedWifiPc("192.168.1.2:49372", "Saved PC", "ab".repeat(32)), "cd".repeat(16), "ef".repeat(32))
    private fun file() = File(folder.root, "pairing.bin")
    private fun store() = EncryptedWifiPairingStore(file()) { key }

    @Test fun encryptedRecordSurvivesStoreRecreationWithoutPlaintextSecrets() {
        store().save(credential)
        assertEquals(credential, store().load())
        val bytes = file().readBytes().toString(Charsets.UTF_8)
        assertFalse(bytes.contains(credential.token))
        assertFalse(bytes.contains(credential.pc.address))
        assertFalse(credential.toString().contains(credential.token))
    }
    @Test fun eachSaveUsesAFreshNonce() {
        store().save(credential); val first = file().readBytes()
        store().save(credential); assertFalse(first.contentEquals(file().readBytes()))
        assertEquals(credential, store().load())
    }
    @Test fun missingAndForgottenRecordReturnNull() {
        assertNull(store().load())
        store().save(credential); store().clear()
        assertNull(store().load())
    }
    @Test fun tamperedRecordIsRejectedWithoutSilentlyOverwritingIt() {
        store().save(credential)
        val corrupt = file().readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        file().writeBytes(corrupt)
        assertThrows(java.security.GeneralSecurityException::class.java) { store().load() }
        assertArrayEquals(corrupt, file().readBytes())
    }
    @Test fun wrongKeyCannotDecryptAndNeverFallsBackToPlaintext() {
        store().save(credential)
        val other = EncryptedWifiPairingStore(file()) { SecretKeySpec(ByteArray(32), "AES") }
        assertThrows(java.security.GeneralSecurityException::class.java) { other.load() }
    }
    @Test fun oversizedAndInvalidCredentialsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { store().save(credential.copy(token = "bad")) }
        assertThrows(IllegalArgumentException::class.java) { store().save(credential.copy(pc = credential.pc.copy(name = "a".repeat(129)))) }
        file().writeBytes(ByteArray(8193))
        assertThrows(IllegalArgumentException::class.java) { store().load() }
    }
    @Test fun failedEncryptionKeepsLastGoodCredential() {
        store().save(credential)
        val failed = EncryptedWifiPairingStore(file()) { throw IOException("Storage key unavailable") }
        assertThrows(IOException::class.java) { failed.save(credential) }
        assertEquals(credential, store().load())
    }
    @Test fun interruptedAtomicWriteKeepsLastGoodPairing() {
        store().save(credential)
        val atomic = AtomicFile(file())
        val output = atomic.startWrite()
        output.write(byteArrayOf(1, 2, 3)); atomic.failWrite(output)
        assertEquals(credential, store().load())
    }

    @Test fun silentCommitFailureIsReportedAndKeepsLastGoodPairing() {
        store().save(credential)
        AtomicFileWindowsShadow.failNextRename = true
        assertThrows(IOException::class.java) { store().save(credential.copy(pc = credential.pc.copy(name = "New PC"))) }
        assertEquals(credential, store().load())
    }
}
