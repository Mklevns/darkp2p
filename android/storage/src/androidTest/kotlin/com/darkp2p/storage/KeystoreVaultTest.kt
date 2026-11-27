package com.darkp2p.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore

/**
 * The vault on a real Android Keystore. Everything else about the vault is
 * tested on the host JVM with a software cipher; this is the part that cannot
 * be: a key the app cannot export, on the device's own hardware.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreVaultTest {
    private val alias = "darkp2p_vault_test_${System.nanoTime()}"
    private val aad = "darkp2p-vault/v1".toByteArray(Charsets.US_ASCII)
    private val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "vault-test-${System.nanoTime()}.bin")
    private val keystore: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After
    fun cleanUp() {
        file.delete()
        File(file.path + ".new").delete()
        runCatching { keystore.deleteEntry(alias) }
    }

    @Test
    fun sealsAndOpensUnderAKeyThatStaysInTheKeystore() {
        val cipher = KeystoreVaultCipher(alias)
        val secret = "1900000000.${"0123456789abcdef".repeat(4)}".toByteArray()
        val sealed = cipher.seal(secret, aad)

        assertArrayEquals(secret, cipher.open(sealed, aad))
        // A second instance finds the same key by its alias: this is what survives a restart.
        assertArrayEquals(secret, KeystoreVaultCipher(alias).open(sealed, aad))
        // Two seals of the same text differ: the nonce is fresh every time.
        assertFalse(sealed.contentEquals(cipher.seal(secret, aad)))
        // The plaintext is nowhere in what gets written.
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("0123456789abcdef"))

        // The key exists, is AES, and cannot be read out of the Keystore.
        val key = keystore.getKey(alias, null)
        assertEquals("AES", key.algorithm)
        assertNull(key.encoded)
    }

    @Test
    fun refusesAnythingAlteredOrMeantForAnotherPurpose() {
        val cipher = KeystoreVaultCipher(alias)
        val sealed = cipher.seal("secret".toByteArray(), aad)

        for (i in listOf(0, sealed.size / 2, sealed.size - 1)) {
            val altered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows(GeneralSecurityException::class.java) { cipher.open(altered, aad) }
        }
        assertThrows(GeneralSecurityException::class.java) { cipher.open(sealed, "another-purpose".toByteArray()) }
        assertThrows(GeneralSecurityException::class.java) { cipher.open(sealed.copyOf(sealed.size - 1), aad) }
        assertThrows(GeneralSecurityException::class.java) { cipher.open(ByteArray(4), aad) }
    }

    @Test
    fun aVaultFileSurvivesANewProcessAndReadsAsEmptyOnceItsKeyIsGone() = runBlocking<Unit> {
        val origin = "https://darkp2p.com"
        val token = "1900000000.${"abcdef0123456789".repeat(4)}"
        Vault(file, KeystoreVaultCipher(alias)).setToken(origin, token)

        // What is on disk says nothing readable.
        val onDisk = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(onDisk.contains("abcdef0123456789"))
        assertFalse(onDisk.contains("darkp2p"))

        // A new Vault object with a new cipher object: nothing carried over in memory, as after a restart.
        val reopened = Vault(file, KeystoreVaultCipher(alias))
        assertEquals(token, reopened.token(origin))
        assertFalse(reopened.wasUnreadable)

        // The Keystore key is removed (a restore to another device, a reset of the credential store).
        // The file can no longer be opened: it reads as empty, says so, and nothing crashes.
        keystore.deleteEntry(alias)
        val orphaned = Vault(file, KeystoreVaultCipher(alias))
        assertNull(orphaned.token(origin))
        assertTrue(orphaned.wasUnreadable)
        // It works again from there, under a new key.
        orphaned.setToken(origin, token)
        assertEquals(token, Vault(file, KeystoreVaultCipher(alias)).token(origin))
    }
}
