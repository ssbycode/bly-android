package com.ssbycode.bly.domain.realTimeCommunication

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import com.ssbycode.bly.domain.communication.RealTimeCommunication
import com.ssbycode.bly.data.realTimeCommunication.Message
import com.ssbycode.bly.data.realTimeCommunication.DeviceConnection
import com.ssbycode.bly.domain.communication.SignalingService

class RealTimeService(
    private val signalingService: SignalingService,
    override val localDeviceID: String = java.util.UUID.randomUUID().toString()
) : RealTimeCommunication {

    private val peerConnectionFactory: PeerConnectionFactory
    private val executor = Executors.newSingleThreadExecutor()

    private val _connectedDevicesFlow = MutableStateFlow<Map<String, DeviceConnection>>(emptyMap())
    override val connectedDevicesFlow: StateFlow<Map<String, DeviceConnection>> get() = _connectedDevicesFlow

    private val _messagesFlow = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    override val messagesFlow: StateFlow<Map<String, List<Message>>> get() = _messagesFlow

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
        val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        setupSignaling()
    }

    override fun connectTo(remoteDeviceID: String) {
        CoroutineScope(Dispatchers.Default).launch {
            if (_connectedDevicesFlow.value.containsKey(remoteDeviceID)) {
                return@launch
            }

            val peerConnection = createPeerConnection(remoteDeviceID)
            val dataChannel = createDataChannel(peerConnection, remoteDeviceID)

            _connectedDevicesFlow.value = _connectedDevicesFlow.value.toMutableMap().apply {
                this[remoteDeviceID] = DeviceConnection(peerConnection, dataChannel)
            }

            signalingService.sendSignal(
                localDeviceID,
                "init",
                "",
                remoteDeviceID
            )
            createAndSendOffer(remoteDeviceID, peerConnection)
        }
    }

    override fun disconnectFrom(remoteDeviceID: String) {
        CoroutineScope(Dispatchers.Default).launch {
            val connection = _connectedDevicesFlow.value[remoteDeviceID] ?: return@launch

            connection.dataChannel?.close()
            connection.peerConnection.close()

            _connectedDevicesFlow.value = _connectedDevicesFlow.value.toMutableMap().apply {
                remove(remoteDeviceID)
            }
            signalingService.sendSignal(
                localDeviceID,
                "bye",
                "disconnect",
                remoteDeviceID
            )
        }
    }

    override fun disconnectAll() {
        CoroutineScope(Dispatchers.Default).launch {
            _connectedDevicesFlow.value.keys.forEach { disconnectFrom(it) }
            _messagesFlow.value = emptyMap()
        }
    }

    override fun sendMessage(data: ByteArray, toRemoteDeviceID: String) {
        val connection = _connectedDevicesFlow.value[toRemoteDeviceID]
        val dataChannel = connection?.dataChannel ?: return

        if (dataChannel.state() == DataChannel.State.OPEN) {
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), false)
            dataChannel.send(buffer)
        }
    }

    override fun broadcast(data: ByteArray) {
        _connectedDevicesFlow.value.forEach { (_, connection) ->
            val dataChannel = connection.dataChannel ?: return@forEach
            if (dataChannel.state() == DataChannel.State.OPEN) {
                val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), false)
                dataChannel.send(buffer)
            }
        }
    }

    private fun setupSignaling() {
        signalingService.setOnSignalListener { type, data, sender, completion ->
            when (type) {
                "init" -> handleInitSignal(sender, completion)
                "offer" -> handleOfferSignal(data, sender, completion)
                "answer" -> handleAnswerSignal(data, sender, completion)
                "candidate" -> handleCandidateSignal(data, sender, completion)
                "bye" -> handleByeSignal(sender, completion)
                else -> completion(false)
            }
        }
    }

    private fun createPeerConnection(remoteDeviceID: String): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        ))
        config.bundlePolicy = PeerConnection.BundlePolicy.BALANCED
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.iceCandidatePoolSize = 10

        return peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingService.sendSignal(
                    localDeviceID,
                    "candidate",
                    candidate.toJSON().toString(),
                    remoteDeviceID
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RtpTransceiver) {}
        })
    }

    private fun createDataChannel(peerConnection: PeerConnection?, remoteDeviceID: String): DataChannel? {
        val config = DataChannel.Init().apply {
            ordered = true
            negotiated = true
            id = 0
        }
        return peerConnection?.createDataChannel("data-$remoteDeviceID", config)
    }

    private fun createAndSendOffer(remoteDeviceID: String, peerConnection: PeerConnection?) {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onSetSuccess() {
                        signalingService.sendSignal(
                            localDeviceID,
                            "offer",
                            sdp.description,
                            remoteDeviceID
                        )
                    }

                    override fun onCreateFailure(message: String?) {}
                    override fun onSetFailure(message: String?) {}
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(message: String?) {}
            override fun onSetFailure(message: String?) {}
        }, MediaConstraints())
    }

    private fun handleInitSignal(sender: String, completion: (Boolean) -> Unit) {
        connectTo(sender)
        completion(true)
    }

    private fun handleOfferSignal(data: String, sender: String, completion: (Boolean) -> Unit) {
        // Process incoming offer
    }

    private fun handleAnswerSignal(data: String, sender: String, completion: (Boolean) -> Unit) {
        // Process incoming answer
    }

    private fun handleCandidateSignal(data: String, sender: String, completion: (Boolean) -> Unit) {
        // Process incoming ICE candidates
    }

    private fun handleByeSignal(sender: String, completion: (Boolean) -> Unit) {
        disconnectFrom(sender)
        completion(true)
    }
}