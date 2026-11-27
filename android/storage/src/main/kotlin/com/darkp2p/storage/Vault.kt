package com.darkp2p.storage

import com.darkp2p.protocol.Json
import com.darkp2p.protocol.JsonParseException
import com.darkp2p.protocol.JsonValue
import com.darkp2p.transport.PendingClaim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Seals and opens the vault file. On a device the key lives in the Android
 * Keystore and never leaves it ([KeystoreVaultCipher]).
 */
public interface VaultCipher {
    /** Returns everything needed to open it again (nonce included). */
    @Throws(GeneralSecurityException::class)
    public fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray

    /** @throws GeneralSecurityException if [sealed] was altered, or the key is not the one that sealed it. */
    @Throws(GeneralSecurityException::class)
    public fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}

/** A checkout waiting for its token, and the server it was started on. */
public data class StoredClaim(val origin: String, val claim: PendingClaim) {
    override fun toString(): String = "StoredClaim(***)"
}

/**
 * The few secrets the app keeps between runs, in one encrypted file:
 *
 *  - Pro tokens, **per server**. A token is a bearer credential for the server
 *    that issued it, so it is only ever sent back to that server: joining a
 *    room on another host must not hand it over.
 *  - The pending checkout claim (the page's `darkp2p_pending_claim`).
 *  - Destinations of receives in progress, so a file left half-written by a
 *    killed process can be deleted on the next start.
 *
 * Never a room key, a room ID or a chat message.
 *
 * A file that cannot be opened (altered, or the Keystore key is gone after a
 * restore or reset) reads as empty: nothing is guessed and nothing crashes.
 */
public class Vault(private val file: File, private val cipher: VaultCipher) {
    private data class Contents(
        val tokens: Map<String, String> = emptyMap(),
        val claim: StoredClaim? = null,
        val unfinished: Set<String> = emptySet(),
    )

    private val mutex = Mutex()
    private var cached: Contents? = null

    /** True if the stored file existed but could not be opened; its contents are treated as lost. */
    @Volatile
    public var wasUnreadable: Boolean = false
        private set

    public suspend fun token(origin: String): String? = read().tokens[origin]

    public suspend fun setToken(origin: String, token: String?): Unit = update {
        it.copy(tokens = if (token.isNullOrBlank()) it.tokens - origin else it.tokens + (origin to token.trim()))
    }

    public suspend fun pendingClaim(): StoredClaim? = read().claim

    public suspend fun setPendingClaim(claim: StoredClaim?): Unit = update { it.copy(claim = claim) }

    /** Document URIs of receives that have not finished. */
    public suspend fun unfinishedReceives(): Set<String> = read().unfinished

    public suspend fun addUnfinishedReceive(uri: String): Unit = update { it.copy(unfinished = (it.unfinished + uri).takeLastSet(MAX_UNFINISHED)) }

    public suspend fun removeUnfinishedReceive(uri: String): Unit = update { it.copy(unfinished = it.unfinished - uri) }

    private fun Set<String>.takeLastSet(n: Int): Set<String> = if (size <= n) this else toList().takeLast(n).toSet()

    private suspend fun read(): Contents = mutex.withLock { cached ?: load().also { cached = it } }

    private suspend fun update(change: (Contents) -> Contents) = mutex.withLock {
        val before = cached ?: load()
        val after = change(before)
        if (after != before) store(after)
        cached = after
    }

    private suspend fun load(): Contents = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext Contents()
        try {
            decode(cipher.open(file.readBytes(), AAD))
        } catch (e: GeneralSecurityException) {
            wasUnreadable = true
            Contents()
        } catch (e: IOException) {
            wasUnreadable = true
            Contents()
        } catch (e: JsonParseException) {
            wasUnreadable = true
            Contents()
        }
    }

    // Write to a sibling, force it to disk, then rename over the old file: a
    // crash leaves either the old vault or the new one, never half of one.
    private suspend fun store(contents: Contents) = withContext(Dispatchers.IO) {
        val sealed = cipher.seal(encode(contents), AAD)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".new")
        FileOutputStream(tmp).use { out ->
            out.write(sealed)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("could not replace the vault file")
        }
    }

    private fun encode(c: Contents): ByteArray {
        val claim = c.claim?.let {
            // The claim itself is the page's record, field for field.
            Json.obj(
                "origin" to Json.str(it.origin),
                "orderId" to Json.str(it.claim.orderId),
                "startedAt" to Json.num(it.claim.startedAt),
                "paymentId" to it.claim.paymentId?.let(Json::str),
                "invoiceId" to it.claim.invoiceId?.let(Json::str),
            )
        }
        val root = Json.obj(
            "v" to Json.num(1),
            "darkp2p_token" to JsonValue.Obj(c.tokens.mapValues { Json.str(it.value) }),
            "darkp2p_pending_claim" to claim,
            "unfinished" to JsonValue.Arr(c.unfinished.map(Json::str)),
        )
        return Json.stringify(root).toByteArray(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): Contents {
        val root = Json.parse(bytes) as? JsonValue.Obj ?: throw JsonParseException("not a vault")
        val tokens = (root["darkp2p_token"] as? JsonValue.Obj)?.fields
            ?.mapNotNull { (origin, v) -> v.stringOrNull?.takeIf { it.isNotBlank() }?.let { origin to it } }?.toMap() ?: emptyMap()
        val claim = (root["darkp2p_pending_claim"] as? JsonValue.Obj)?.let { o ->
            val origin = o["origin"]?.stringOrNull
            val orderId = o["orderId"]?.stringOrNull
            val startedAt = o["startedAt"]?.safeIntegerOrNull
            // Anything else (an older-format claim) can no longer be claimed.
            if (origin == null || orderId == null || startedAt == null || !PendingClaim.isOrderId(orderId)) return@let null
            StoredClaim(
                origin,
                PendingClaim(
                    orderId, startedAt,
                    invoiceId = o["invoiceId"]?.stringOrNull?.takeIf(PendingClaim::isNowPaymentsId),
                    paymentId = o["paymentId"]?.stringOrNull?.takeIf(PendingClaim::isNowPaymentsId),
                ),
            )
        }
        val unfinished = (root["unfinished"] as? JsonValue.Arr)?.items?.mapNotNull { it.stringOrNull }?.toSet() ?: emptySet()
        return Contents(tokens, claim, unfinished)
    }

    private companion object {
        // Binds the ciphertext to its purpose and format version.
        val AAD = "darkp2p-vault/v1".toByteArray(Charsets.US_ASCII)
        const val MAX_UNFINISHED = 32
    }
}
