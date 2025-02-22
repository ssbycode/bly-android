package com.ssbycode.bly.data.realTimeCommunication

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Date
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val senderId: String,
    val timestamp: Long = System.currentTimeMillis(),
    @Transient var isFromCurrentUser: Boolean = true
) {
    // Converte timestamp para Date
    fun date(): Date = Date(timestamp)

    // Construtor secundário para simplificar criação
    constructor(content: String, senderId: String) : this(
        id = UUID.randomUUID().toString(),
        content = content,
        senderId = senderId,
        timestamp = System.currentTimeMillis(),
        isFromCurrentUser = true
    )

    // Método dentro da classe para converter para JSON
    fun toJson(): String {
        return Json.encodeToString(this)
    }
}