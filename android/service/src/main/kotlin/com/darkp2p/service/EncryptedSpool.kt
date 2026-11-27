package com.darkp2p.service

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A temporary copy of a stream whose length is not known up front.
 *
 * The protocol announces a file's size before its first byte, so such a stream
 * has to be read to the end once before it can be offered. The copy never sits
 * on disk in the clear: it is encrypted block by block under a key that exists
 * only in this object's memory, so whatever a crash leaves behind is noise.
 */
public class EncryptedSpool private constructor(private val file: File, private val key: SecretKeySpec, public val size: Long) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")

    /** [length] bytes at [offset], or fewer at the end. */
    @Synchronized
    public fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
        val out = ByteArray(minOf(length.toLong(), size - offset).toInt())
        var done = 0
        while (done < out.size) {
            val pos = offset + done
            val block = pos / BLOCK
            val plain = readBlock(block)
            val within = (pos % BLOCK).toInt()
            val n = minOf(plain.size - within, out.size - done)
            if (n <= 0) throw IOException("spool is shorter than recorded")
            System.arraycopy(plain, within, out, done, n)
            done += n
        }
        return out
    }

    private var cachedIndex = -1L
    private var cached = ByteArray(0)

    private fun readBlock(index: Long): ByteArray {
        if (index == cachedIndex) return cached
        val plainLen = minOf(BLOCK.toLong(), size - index * BLOCK).toInt()
        val sealed = ByteArray(plainLen + TAG)
        raf.seek(index * (BLOCK + TAG))
        raf.readFully(sealed)
        val plain = try {
            cipher(Cipher.DECRYPT_MODE, key, index).doFinal(sealed)
        } catch (e: GeneralSecurityException) {
            throw IOException("spool block was altered")
        }
        cachedIndex = index
        cached = plain
        return plain
    }

    override fun close() {
        runCatching { raf.close() }
        file.delete()
        cached.fill(0)
    }

    public companion object {
        private const val BLOCK = 64 * 1024
        private const val TAG = 16

        // The block index is the nonce: unique per block, and the key is used for this one file only.
        private fun cipher(mode: Int, key: SecretKeySpec, index: Long): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, key, GCMParameterSpec(TAG * 8, ByteBuffer.allocate(12).putLong(4, index).array()))
            }

        /**
         * Copies [input] to a new spool file in [dir].
         * @throws IOException if reading fails or the stream is longer than [maxBytes]; nothing is left behind.
         */
        public fun copy(input: InputStream, dir: File, maxBytes: Long, random: SecureRandom = SecureRandom()): EncryptedSpool {
            dir.mkdirs()
            val file = File.createTempFile("tx-", ".spool", dir)
            val key = SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES")
            try {
                var total = 0L
                var index = 0L
                file.outputStream().use { out ->
                    val block = ByteArray(BLOCK)
                    while (true) {
                        var filled = 0
                        while (filled < BLOCK) {
                            val r = input.read(block, filled, BLOCK - filled)
                            if (r < 0) break
                            filled += r
                        }
                        if (filled == 0) break
                        total += filled
                        if (total > maxBytes) throw IOException("stream is larger than the space allowed for it")
                        out.write(cipher(Cipher.ENCRYPT_MODE, key, index++).doFinal(block, 0, filled))
                        if (filled < BLOCK) break
                    }
                    block.fill(0)
                }
                return EncryptedSpool(file, key, total)
            } catch (e: Exception) {
                file.delete()
                throw if (e is IOException) e else IOException("spooling failed")
            }
        }

        /** Spool files left by a process that died: unreadable without their key, and deleted here. */
        public fun deleteLeftovers(dir: File) {
            dir.listFiles { f -> f.name.startsWith("tx-") && f.name.endsWith(".spool") }?.forEach { it.delete() }
        }
    }
}
