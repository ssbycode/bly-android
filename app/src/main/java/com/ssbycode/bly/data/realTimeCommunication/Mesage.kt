package com.ssbycode.bly.data.realTimeCommunication

import java.util.Date

data class Message(
    val id: String,
    val content: String,
    val senderId: String,
    val timestamp: Date,
    val forwardedBy: List<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}