package com.ssbycode.bly.data.realTimeCommunication

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val content: String,
    val senderId: String,
    @Transient
    var isFromCurrentUser: Boolean = true
)