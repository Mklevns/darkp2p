package com.darkp2p.service

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesTest {
    @Test
    fun `hostile file names become harmless ones`() {
        val cases = mapOf(
            "report.pdf" to "report.pdf",
            "ünïcødé – 文件.bin" to "ünïcødé – 文件.bin",
            "../../etc/passwd" to "_.._etc_passwd",
            "..\\..\\windows\\system32" to "_.._windows_system32",
            ".hidden" to "hidden",
            "..." to FileNames.FALLBACK,
            "" to FileNames.FALLBACK,
            "   " to FileNames.FALLBACK,
            "a\u0000b\u0007c\nd\t\te" to "abc d e",
            "line\u2028break\u00A0nbsp" to "line break\u00A0nbsp",
            // A right-to-left override would display this as "invoice-exe.pdf".
            "invoice-‮fdp.exe" to "invoice-fdp.exe",
            "a​b⁦c﻿d" to "abcd",
            "con:1*?\"<>|" to "con_1______",
            "name. " to "name",
            "\uD800broken" to "broken",
        )
        for ((input, expected) in cases) assertEquals(expected, FileNames.safe(input), input)

        val long = FileNames.safe("文".repeat(300) + ".tar.gz")
        assertTrue(long.toByteArray().size <= 200 && long.endsWith(".gz") && long.startsWith("文"), long)
        assertTrue(FileNames.safe("x".repeat(500)).length == 200)
        // Whatever goes in, the result is never empty, never a path, never hidden.
        val random = Random(5)
        repeat(20_000) {
            val name = buildString { repeat(random.nextInt(40)) { append("./\\ ‮\u0000a文😀.\t:".let { a -> a[random.nextInt(a.length)] }) } }
            val safe = FileNames.safe(name)
            assertTrue(safe.isNotEmpty() && !safe.startsWith(".") && '/' !in safe && '\\' !in safe && safe.none { it.isISOControl() }, "$name -> $safe")
        }
    }

    @Test
    fun `announced MIME types are kept only if they are plain`() {
        assertEquals("application/pdf", FileNames.safeMime("application/pdf"))
        assertEquals("image/svg+xml", FileNames.safeMime("image/SVG+xml"))
        for (bad in listOf(null, "", "text", "text/html; charset=utf-8", "a/b/c", "text/ht ml", "*/*", "x".repeat(70) + "/y")) {
            assertEquals("application/octet-stream", FileNames.safeMime(bad), bad)
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("spool").toFile()

    @Test
    fun `a spooled stream reads back exactly, at any offset`() {
        val random = Random(1)
        for (size in listOf(0, 1, 65_535, 65_536, 65_537, 3 * 65_536, 1_000_003)) {
            val data = ByteArray(size).also(random::nextBytes)
            val dir = tempDir()
            EncryptedSpool.copy(ByteArrayInputStream(data), dir, Long.MAX_VALUE).use { spool ->
                assertEquals(size.toLong(), spool.size)
                // As the transfer reads it: 16000-byte chunks in order.
                val chunks = (0 until size step 16_000).map { spool.read(it.toLong(), minOf(16_000, size - it)) }
                assertContentEquals(data, chunks.fold(ByteArray(0)) { a, b -> a + b }, "size $size")
                repeat(50) {
                    val off = if (size == 0) 0 else random.nextInt(size)
                    val len = 1 + random.nextInt(100_000)
                    assertContentEquals(data.copyOfRange(off, minOf(size, off + len)), spool.read(off.toLong(), len))
                }
                assertEquals(0, spool.read(size.toLong(), 10).size)
            }
            assertEquals(0, dir.list()!!.size) // deleted on close
        }
    }

    @Test
    fun `a stream that trickles in small reads is spooled whole`() {
        val data = ByteArray(200_000) { (it * 7).toByte() }
        val trickle = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= data.size) return -1
                val n = minOf(len, 1 + pos % 777, data.size - pos)
                System.arraycopy(data, pos, b, off, n)
                pos += n
                return n
            }
        }
        EncryptedSpool.copy(trickle, tempDir(), Long.MAX_VALUE).use { assertContentEquals(data, it.read(0, data.size)) }
    }

    @Test
    fun `the spool on disk is not the file`() {
        val dir = tempDir()
        val secret = "TOP-SECRET-CONTENT-".repeat(5000).toByteArray()
        EncryptedSpool.copy(ByteArrayInputStream(secret), dir, Long.MAX_VALUE).use { spool ->
            val onDisk = dir.listFiles()!!.single()
            assertFalse("TOP-SECRET" in String(onDisk.readBytes(), Charsets.ISO_8859_1))
            // Altered on disk: reading fails instead of sending something else.
            val bytes = onDisk.readBytes()
            bytes[100] = (bytes[100].toInt() xor 1).toByte()
            onDisk.writeBytes(bytes)
            assertFailsWith<IOException> { spool.read(0, 1000) }
        }
    }

    @Test
    fun `a stream over the limit or one that fails leaves nothing behind`() {
        val dir = tempDir()
        assertFailsWith<IOException> { EncryptedSpool.copy(ByteArrayInputStream(ByteArray(300_000)), dir, 200_000) }
        val failing = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 100_000) 1 else throw IOException("gone")
        }
        assertFailsWith<IOException> { EncryptedSpool.copy(failing, dir, Long.MAX_VALUE) }
        assertEquals(0, dir.list()!!.size)

        // Leftovers of a killed process are swept.
        File(dir, "tx-123.spool").writeBytes(ByteArray(10))
        File(dir, "other.txt").writeBytes(ByteArray(10))
        EncryptedSpool.deleteLeftovers(dir)
        assertEquals(listOf("other.txt"), dir.list()!!.toList())
    }

    @Test
    fun `locks follow moving bytes and the service stops after ten idle minutes`() {
        val moving = RoomActivity(transferActive = true, lastProgressAt = 1_000, lastBusyAt = 1_000)
        assertTrue(ServicePolicy.holdLocks(moving, 1_000))
        assertTrue(ServicePolicy.holdLocks(moving, 5_999))
        assertFalse(ServicePolicy.holdLocks(moving, 6_000)) // stalled for five seconds: released
        assertFalse(ServicePolicy.holdLocks(moving.copy(transferActive = false), 1_000)) // an idle room holds nothing
        assertFalse(ServicePolicy.shouldStop(moving, 10_000_000)) // never while a transfer is open

        val idle = RoomActivity(transferActive = false, lastProgressAt = 0, lastBusyAt = 1_000)
        assertFalse(ServicePolicy.shouldStop(idle, 1_000 + 599_999))
        assertTrue(ServicePolicy.shouldStop(idle, 1_000 + 600_000))
    }
}
