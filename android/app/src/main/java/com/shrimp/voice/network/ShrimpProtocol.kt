package com.shrimp.voice.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Protocol parser for shrimp-server WebSocket communication.
 *
 * JSON frame (text):  {"tag": "SYS|STT|蝦蝦", "msg": "..."}
 * Binary frame:       raw WAV PCM data for TTS playback
 */
object ShrimpProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class MessageFrame(
        val tag: String = "",
        val msg: String = "",
        val event: String? = null
    )

    /** Parse a text JSON frame from the server. Returns null if parsing fails. */
    fun parseTextFrame(text: String): MessageFrame? {
        return try {
            json.decodeFromString<MessageFrame>(text)
        } catch (e: Exception) {
            null
        }
    }

    /** Check if raw bytes look like WAV audio (RIFF header). */
    fun isWavData(data: ByteArray): Boolean {
        return data.size >= 4 &&
            data[0] == 'R'.code.toByte() &&
            data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() &&
            data[3] == 'F'.code.toByte()
    }

    /** Build a JSON text frame for sending. */
    fun buildTextFrame(tag: String, msg: String): String {
        return json.encodeToString(MessageFrame.serializer(), MessageFrame(tag, msg))
    }
}
