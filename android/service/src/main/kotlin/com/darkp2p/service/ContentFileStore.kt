package com.darkp2p.service

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.darkp2p.storage.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Files on the device: what is sent is read from `content://` URIs, what is
 * received goes to the Downloads collection or to a document the user chose.
 *
 * Not yet exercised on a device.
 *
 * @param vault remembers documents being written, so a file left half-written
 *   by a killed process is deleted on the next start.
 */
public class ContentFileStore(context: Context, private val vault: Vault?) : FileStore {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val spoolDir = File(appContext.cacheDir, "spool")

    override suspend fun freeSpace(target: SaveTarget): Long? = withContext(Dispatchers.IO) {
        if (target != SaveTarget.Downloads) return@withContext null
        try {
            val storage = appContext.getSystemService(StorageManager::class.java)
            storage.getAllocatableBytes(storage.getUuidForPath(Environment.getExternalStorageDirectory()))
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun create(target: SaveTarget, name: String, mime: String, size: Long): FileSink = withContext(Dispatchers.IO) {
        when (target) {
            SaveTarget.Downloads -> {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DarkP2P")
                    // Invisible to the user and to other apps until the file is complete and verified.
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("could not create the download")
                try {
                    UriSink(uri, open(uri, "w"), pending = true)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
            is SaveTarget.Document -> {
                val uri = Uri.parse(target.uri)
                vault?.addUnfinishedReceive(target.uri)
                // "wt": the document the user just created is ours to overwrite from the start.
                UriSink(uri, open(uri, "wt"), pending = false)
            }
        }
    }

    private fun open(uri: Uri, mode: String): ParcelFileDescriptor = resolver.openFileDescriptor(uri, mode) ?: throw IOException("could not open the destination")

    private inner class UriSink(private val uri: Uri, private val pfd: ParcelFileDescriptor, private val pending: Boolean) : FileSink {
        private val file = FileOutputStream(pfd.fileDescriptor)
        private val out = BufferedOutputStream(file, 256 * 1024)

        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { out.write(bytes) }

        override suspend fun commit(): String = withContext(Dispatchers.IO) {
            out.flush()
            // On disk before it is announced as saved.
            file.fd.sync()
            out.close()
            pfd.close()
            if (pending) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } else {
                vault?.removeUnfinishedReceive(uri.toString())
            }
            // The system may have renamed it (a name already taken, an extension for the type).
            displayName(uri) ?: FileNames.FALLBACK
        }

        override suspend fun discard(): Unit = withContext(Dispatchers.IO) {
            runCatching { out.close() }
            runCatching { pfd.close() }
            if (pending) {
                runCatching { resolver.delete(uri, null, null) }
            } else {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                vault?.removeUnfinishedReceive(uri.toString())
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    /**
     * Opens [uri] for sending. Call it when the file is chosen or shared: the open
     * descriptor stays readable after the temporary permission to the URI ends.
     *
     * @throws IOException if it cannot be opened.
     */
    public suspend fun source(uri: Uri): FileSource = withContext(Dispatchers.IO) {
        val name = FileNames.safe(displayName(uri) ?: uri.lastPathSegment ?: "")
        val mime = FileNames.safeMime(resolver.getType(uri))
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("could not open the file")
        val size = pfd.statSize
        if (size >= 0) return@withContext DescriptorSource(name, size, mime, pfd)

        // No size and no seeking (a stream from another app): the protocol needs
        // the size first, so copy it once, encrypted, and send the copy.
        try {
            val spool = FileInputStream(pfd.fileDescriptor).use { EncryptedSpool.copy(it, spoolDir, maxBytes = spoolDir.apply { mkdirs() }.usableSpace / 2) }
            SpoolSource(name, mime, spool)
        } finally {
            runCatching { pfd.close() }
        }
    }

    private class DescriptorSource(override val name: String, override val size: Long, override val mime: String, private val pfd: ParcelFileDescriptor) : FileSource {
        private val channel = FileInputStream(pfd.fileDescriptor).channel

        override suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
            val buffer = ByteBuffer.allocate(length)
            var position = offset
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, position)
                if (n < 0) break // shorter than announced: the room reports that the file changed
                position += n
            }
            if (buffer.hasRemaining()) buffer.array().copyOf(buffer.position()) else buffer.array()
        }

        override fun close() {
            runCatching { channel.close() }
            runCatching { pfd.close() }
        }
    }

    private class SpoolSource(override val name: String, override val mime: String, private val spool: EncryptedSpool) : FileSource {
        override val size: Long get() = spool.size

        override suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) { spool.read(offset, length) }

        override fun close() = spool.close()
    }

    /**
     * Removes what a killed process left behind: downloads never completed,
     * documents never finished, spool copies. Call once at startup, before a room is joined.
     */
    public suspend fun sweep(): Unit = withContext(Dispatchers.IO) {
        EncryptedSpool.deleteLeftovers(spoolDir)

        val stale = ArrayList<Uri>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        val args = arrayOf(appContext.packageName)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        runCatching {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val query = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                resolver.query(collection, projection, query, null)
            } else {
                @Suppress("DEPRECATION")
                resolver.query(MediaStore.setIncludePending(collection), projection, selection, args, null)
            }
            cursor?.use { c -> while (c.moveToNext()) stale += Uri.withAppendedPath(collection, c.getLong(0).toString()) }
        }
        for (uri in stale) runCatching { resolver.delete(uri, null, null) }

        vault?.let { v ->
            for (uri in v.unfinishedReceives()) {
                runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(uri)) }
                v.removeUnfinishedReceive(uri)
            }
        }
    }
}
