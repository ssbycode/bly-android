package com.ssbycode.bly.data.realTimeCommunication

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

data class DeviceConnection(
    val connection: PeerConnection,
    val dataChannel: DataChannel?,
    val connectionState: IceCandidate,
    val isConnected: Boolean
)
