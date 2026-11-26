package com.darkp2p.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF (RFC 5869) with SHA-256, as Web Crypto's `HKDF` derives keys. */
public object Hkdf {
    private const val HASH_LEN = 32

    public fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "HKDF output length out of range" }
        // Extract. An empty salt means HashLen zero bytes (RFC 5869 §2.2).
        val prk = hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt).doFinal(ikm)
        // Expand.
        val mac = hmac(prk)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val n = minOf(block.size, length - filled)
            System.arraycopy(block, 0, out, filled, n)
            filled += n
            counter++
        }
        prk.fill(0)
        block.fill(0)
        return out
    }

    private fun hmac(key: ByteArray): Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
}
