package com.darkp2p.transport

/**
 * File access for the transfers of one room. Calls arrive one at a time, in
 * order, off the main thread. [transfer] is the room's handle for the transfer.
 *
 * Any exception means the file could not be read or written; the transfer is
 * then aborted with the `disk` reason.
 */
public interface TransferIo {
    /** [length] bytes at [offset] of the file being sent. Fewer bytes mean the file changed. */
    public suspend fun read(transfer: Int, offset: Long, length: Int): ByteArray

    /** Appends to the incoming file. */
    public suspend fun write(transfer: Int, bytes: ByteArray)

    /** The incoming file is complete and verified: make it durable and visible. */
    public suspend fun commit(transfer: Int)

    /** The incoming transfer ended without a verified file: delete what was written, if anything. */
    public suspend fun discard(transfer: Int)
}
