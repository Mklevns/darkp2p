package com.darkp2p.transport

import java.io.Closeable

/**
 * Something that can publish a local port as an onion service for a while.
 * Closing it takes the service down; nothing about it is kept.
 */
public interface OnionHost : Closeable {
    /**
     * Starts Tor if it is not running, then publishes port 80 of a new v3 onion
     * service, forwarded to [localPort] on this device's loopback interface.
     *
     * @param onProgress Tor's start-up progress, 0 to 100.
     * @return the service's host name, ending in `.onion`.
     * @throws java.io.IOException if Tor did not start or refused the service.
     */
    public suspend fun publish(localPort: Int, onProgress: (Int) -> Unit): String
}
