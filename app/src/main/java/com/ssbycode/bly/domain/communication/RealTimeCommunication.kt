package com.ssbycode.bly.domain.communication

import kotlinx.coroutines.flow.StateFlow
import com.ssbycode.bly.data.realTimeCommunication.Message
import com.ssbycode.bly.data.realTimeCommunication.DeviceConnection

interface RealTimeCommunication {
    val connectedDevicesFlow: StateFlow<Map<String, DeviceConnection>>
    val messagesFlow: StateFlow<Map<String, List<Message>>>
    val localDeviceID: String

    fun connectTo(remoteDeviceID: String)
    fun disconnectFrom(remoteDeviceID: String)
    fun disconnectAll()
    fun sendMessage(data: ByteArray, toRemoteDeviceID: String)
    fun broadcast(data: ByteArray)
}