package com.darkp2p.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** The vectors exported from the web client (`test/vectors/v3.json`). */
internal object Vectors {
    val root: JsonObject by lazy {
        val path = System.getProperty("darkp2p.vectors") ?: error("system property darkp2p.vectors is not set")
        Json.parseToJsonElement(File(path).readText(Charsets.UTF_8)).jsonObject
    }
}
