package com.ssbycode.bly.domain.realTimeCommunication

import com.ssbycode.bly.data.realTimeCommunication.DeviceConnection
import com.ssbycode.bly.data.realTimeCommunication.Message
import com.ssbycode.bly.domain.communication.RealTimeCommunication
import kotlinx.coroutines.flow.StateFlow

class RealTimeManager(
    override val connectedDevicesFlow: StateFlow<Map<String, DeviceConnection>>,
    override val messagesFlow: StateFlow<Map<String, List<Message>>>,
    override val localDeviceID: String
) : RealTimeCommunication {

    override fun connectTo(remoteDeviceID: String) {
        TODO("Not yet implemented")
    }

    override fun disconnectFrom(remoteDeviceID: String) {
        TODO("Not yet implemented")
    }

    override fun disconnectAll() {
        TODO("Not yet implemented")
    }

    override fun sendMessage(data: ByteArray, toRemoteDeviceID: String) {
        TODO("Not yet implemented")
    }

    override fun broadcast(data: ByteArray) {
        TODO("Not yet implemented")
    }
}