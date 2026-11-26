package com.darkp2p.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class WireConstantsTest {
    private val ours: Map<String, Any> = mapOf(
        "FRAME_TYPE_HELLO" to WireConstants.FRAME_TYPE_HELLO,
        "FRAME_TYPE_CTRL" to WireConstants.FRAME_TYPE_CTRL,
        "FRAME_TYPE_DATA" to WireConstants.FRAME_TYPE_DATA,
        "LEGACY_FRAME_TYPES" to WireConstants.LEGACY_FRAME_TYPES,
        "ROLE_INITIATOR" to WireConstants.ROLE_INITIATOR,
        "ROLE_RESPONDER" to WireConstants.ROLE_RESPONDER,
        "FRAME_HEADER_LEN" to WireConstants.FRAME_HEADER_LEN,
        "FRAME_OVERHEAD" to WireConstants.FRAME_OVERHEAD,
        "FRAME_IV_LEN" to WireConstants.FRAME_IV_LEN,
        "FRAME_TAG_LEN" to WireConstants.FRAME_TAG_LEN,
        "TRANSFER_ID_LEN" to WireConstants.TRANSFER_ID_LEN,
        "SESSION_NONCE_LEN" to WireConstants.SESSION_NONCE_LEN,
        "ROOM_KEY_LEN" to WireConstants.ROOM_KEY_LEN,
        "ACK_EVERY" to WireConstants.ACK_EVERY,
        "TX_WINDOW" to WireConstants.TX_WINDOW,
        "RAM_MODE_MAX_BYTES" to WireConstants.RAM_MODE_MAX_BYTES,
        "ACCEPT_TIMEOUT_MS" to WireConstants.ACCEPT_TIMEOUT_MS,
        "ACK_STALL_MS" to WireConstants.ACK_STALL_MS,
        "COMPLETE_TIMEOUT_MS" to WireConstants.COMPLETE_TIMEOUT_MS,
        "MAX_FILE_CHUNKS" to WireConstants.MAX_FILE_CHUNKS,
        "RECONNECT_BASE_MS" to WireConstants.RECONNECT_BASE_MS,
        "RECONNECT_MAX_MS" to WireConstants.RECONNECT_MAX_MS,
        "RECONNECT_STABLE_MS" to WireConstants.RECONNECT_STABLE_MS,
        "RELAY_CTRL_RESERVE" to WireConstants.RELAY_CTRL_RESERVE,
        "RELAY_MIN_REMAINING" to WireConstants.RELAY_MIN_REMAINING,
        "RELAY_BUSY_RETRY_MS" to WireConstants.RELAY_BUSY_RETRY_MS,
        "ABORT_REASONS" to WireConstants.ABORT_REASONS,
        "DECLINE_REASONS" to WireConstants.DECLINE_REASONS,
        "CHUNK_SIZE" to WireConstants.CHUNK_SIZE,
        "HIGH_WATER" to WireConstants.HIGH_WATER,
        "HELLO_TIMEOUT_MS" to WireConstants.HELLO_TIMEOUT_MS,
        "TRANSFER_KEY_CACHE_MAX" to WireConstants.TRANSFER_KEY_CACHE_MAX,
        "SEEN_TRANSFER_IDS_MAX" to WireConstants.SEEN_TRANSFER_IDS_MAX,
        "ENDED_TRANSFER_IDS_MAX" to WireConstants.ENDED_TRANSFER_IDS_MAX,
        "RX_MAX_PENDING_CHUNKS" to WireConstants.RX_MAX_PENDING_CHUNKS,
        "RX_MAX_PENDING_CTRL" to WireConstants.RX_MAX_PENDING_CTRL,
        "TX_WATCHDOG_MS" to WireConstants.TX_WATCHDOG_MS,
        "FULL_RETRY_MS" to WireConstants.FULL_RETRY_MS,
    )

    // Numbers compare as Long, whatever integer type the Kotlin constant has.
    private fun normalize(value: Any?): Any? = when (value) {
        is Number -> value.toLong()
        is List<*> -> value.map(::normalize)
        else -> value
    }

    private fun fromJson(element: JsonElement): Any = when (element) {
        is JsonArray -> element.map(::fromJson)
        is JsonPrimitive -> if (element.isString) element.content else element.long
        else -> error("unexpected constant $element")
    }

    @Test
    fun `the vector file is the v3 format`() {
        assertEquals("darkp2p/v3", Vectors.root.getValue("format").jsonPrimitive.content)
    }

    @Test
    fun `every constant exported by the web client has the same value here`() {
        val theirs = Vectors.root.getValue("constants").jsonObject.mapValues { fromJson(it.value) }
        // Same names on both sides: a constant added to the page must be added here too.
        assertEquals(theirs.keys.sorted(), ours.keys.sorted())
        for ((name, value) in theirs) assertEquals(value, normalize(ours.getValue(name)), name)
    }

    @Test
    fun `derived layout sizes`() {
        assertEquals(33, WireConstants.FRAME_HEADER_LEN)
        assertEquals(49, WireConstants.FRAME_OVERHEAD)
        assertEquals(16_049, WireConstants.CHUNK_SIZE + WireConstants.FRAME_OVERHEAD)
    }
}
