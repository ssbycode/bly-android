package com.ssbycode.bly.data.realTimeCommunication

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

data class DeviceConnection(
    val deviceId: String,
    val connection: PeerConnection,
    var dataChannel: DataChannel? = null,
    var connectionState: PeerConnection.IceConnectionState = PeerConnection.IceConnectionState.NEW
) {
    val isConnected: Boolean
        get() = connectionState == PeerConnection.IceConnectionState.NEW
}