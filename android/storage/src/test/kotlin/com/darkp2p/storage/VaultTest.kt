package com.darkp2p.storage

import com.darkp2p.transport.PendingClaim
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same construction as the Keystore cipher (AES-256-GCM, nonce in front), with a key held in memory. */
internal class MemoryVaultCipher(private val key: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)) : VaultCipher {
    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < 28) throw GeneralSecurityException("truncated")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}

class VaultTest {
    private val dir: File = Files.createTempDirectory("vault").toFile()
    private val file = File(dir, "vault.bin")
    private val order = "pro-30d:${"cd".repeat(16)}"
    private val home = "https://darkp2p.com"

    @Test
    fun `tokens, the claim and unfinished receives survive a restart`() = runBlocking<Unit> {
        val cipher = MemoryVaultCipher()
        val claim = StoredClaim(home, PendingClaim(order, 1_700_000_000_000, invoiceId = "5000000001", paymentId = "4000000001"))
        Vault(file, cipher).apply {
            assertNull(token(home))
            setToken(home, " 1999999999.${"a".repeat(64)} ")
            setPendingClaim(claim)
            addUnfinishedReceive("content://docs/1")
            addUnfinishedReceive("content://docs/2")
            removeUnfinishedReceive("content://docs/1")
        }
        Vault(file, cipher).apply {
            assertEquals("1999999999.${"a".repeat(64)}", token(home))
            assertEquals(claim, pendingClaim())
            assertEquals(setOf("content://docs/2"), unfinishedReceives())
            assertFalse(wasUnreadable)
            setToken(home, "")
            setPendingClaim(null)
        }
        Vault(file, cipher).apply {
            assertNull(token(home))
            assertNull(pendingClaim())
        }
    }

    @Test
    fun `a token is kept for the server that issued it and no other`() = runBlocking<Unit> {
        val vault = Vault(file, MemoryVaultCipher())
        vault.setToken(home, "token-for-home")
        vault.setToken("https://selfhosted.example", "token-for-self")
        assertEquals("token-for-home", vault.token(home))
        assertEquals("token-for-self", vault.token("https://selfhosted.example"))
        assertNull(vault.token("https://evil.example"))
        assertNull(vault.token("http://${"a".repeat(56)}.onion"))
        // Same host, another port or scheme: another server.
        assertNull(vault.token("https://darkp2p.com:8443"))
    }

    @Test
    fun `nothing readable is on disk`() = runBlocking<Unit> {
        val vault = Vault(file, MemoryVaultCipher())
        vault.setToken(home, "1999999999.${"b".repeat(64)}")
        vault.setPendingClaim(StoredClaim(home, PendingClaim(order, 5, paymentId = "4000000009")))
        vault.addUnfinishedReceive("content://docs/secret-name.pdf")
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        for (secret in listOf("1999999999", "b".repeat(64), order, "4000000009", "secret-name", "darkp2p.com", "darkp2p_token")) {
            assertFalse(secret in raw, secret)
        }
        assertEquals(listOf("vault.bin"), dir.list()!!.toList()) // no temporary copy left behind
        assertFalse(order in vault.pendingClaim().toString())
    }

    @Test
    fun `a vault that cannot be opened reads as empty and can be used again`() = runBlocking<Unit> {
        Vault(file, MemoryVaultCipher()).setToken(home, "t")

        // The key is gone (device restore, Keystore reset).
        val lostKey = Vault(file, MemoryVaultCipher())
        assertNull(lostKey.token(home))
        assertTrue(lostKey.wasUnreadable)
        lostKey.setToken(home, "new")
        assertEquals("new", lostKey.token(home))

        // One flipped bit, a truncated file, an empty file.
        val cipher = MemoryVaultCipher()
        Vault(file, cipher).setToken(home, "t")
        val good = file.readBytes()
        for (bad in listOf(good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }, good.copyOf(20), ByteArray(0))) {
            file.writeBytes(bad)
            val vault = Vault(file, cipher)
            assertNull(vault.token(home))
            assertTrue(vault.wasUnreadable)
        }
        // Sealed for another purpose (other associated data): not accepted as a vault.
        file.writeBytes(cipher.seal("""{"v":1,"darkp2p_token":{"$home":"forged"}}""".toByteArray(), "something-else".toByteArray()))
        assertNull(Vault(file, cipher).token(home))
    }

    @Test
    fun `malformed records are dropped, not trusted`() = runBlocking<Unit> {
        val cipher = MemoryVaultCipher()
        val aad = "darkp2p-vault/v1".toByteArray()
        fun vaultOf(json: String) = Vault(file.also { it.writeBytes(cipher.seal(json.toByteArray(), aad)) }, cipher)

        val v = vaultOf(
            """{"v":1,"darkp2p_token":{"$home":"ok","https://x.example":7,"https://y.example":"  "},""" +
                """"darkp2p_pending_claim":{"origin":"$home","orderId":"$order","startedAt":5,"paymentId":"not-a-number","invoiceId":"77"},"unfinished":["a",7,"b"]}""",
        )
        assertEquals("ok", v.token(home))
        assertNull(v.token("https://x.example"))
        assertNull(v.token("https://y.example"))
        assertEquals(StoredClaim(home, PendingClaim(order, 5, invoiceId = "77")), v.pendingClaim())
        assertEquals(setOf("a", "b"), v.unfinishedReceives())

        for (claim in listOf(
            """{"origin":"$home","orderId":"pro-9d:${"cd".repeat(16)}","startedAt":5}""", """{"orderId":"$order","startedAt":5}""",
            """{"origin":"$home","orderId":"$order"}""", "7", "null", """"text"""",
        )) {
            assertNull(vaultOf("""{"v":1,"darkp2p_pending_claim":$claim}""").pendingClaim(), claim)
        }
        assertTrue(vaultOf("[]").wasUnreadable.let { true })
        assertNull(vaultOf("[]").token(home))
        assertNull(vaultOf("not json").token(home))
    }

    @Test
    fun `the list of unfinished receives is bounded`() = runBlocking<Unit> {
        val vault = Vault(file, MemoryVaultCipher())
        repeat(100) { vault.addUnfinishedReceive("content://docs/$it") }
        val kept = vault.unfinishedReceives()
        assertEquals(32, kept.size)
        assertTrue("content://docs/99" in kept && "content://docs/0" !in kept)
    }
}
