package com.darkp2p.protocol

import java.io.File

/** The vectors exported from the web client (`test/vectors/v3.json`), read with this module's own JSON parser. */
internal object Vectors {
    val root: JsonValue.Obj by lazy {
        val path = System.getProperty("darkp2p.vectors") ?: error("system property darkp2p.vectors is not set")
        Json.parse(File(path).readBytes()) as JsonValue.Obj
    }

    fun list(name: String): List<JsonValue.Obj> = (root[name] as JsonValue.Arr).items.map { it as JsonValue.Obj }

    fun obj(name: String): JsonValue.Obj = root[name] as JsonValue.Obj
}

internal fun JsonValue.Obj.str(key: String): String = (fields.getValue(key) as JsonValue.Str).value
internal fun JsonValue.Obj.long(key: String): Long = fields.getValue(key).safeIntegerOrNull ?: error("$key is not an integer")
internal fun JsonValue.Obj.int(key: String): Int = long(key).toInt()
internal fun JsonValue.Obj.bool(key: String): Boolean = (fields.getValue(key) as JsonValue.Bool).value
internal fun JsonValue.Obj.hex(key: String): ByteArray = Bytes.fromHex(str(key))
internal fun JsonValue.Obj.arr(key: String): List<JsonValue> = (fields.getValue(key) as JsonValue.Arr).items
internal fun JsonValue.Obj.obj(key: String): JsonValue.Obj = fields.getValue(key) as JsonValue.Obj
internal fun JsonValue.long(): Long = safeIntegerOrNull ?: error("$this is not an integer")
