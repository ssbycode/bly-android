package com.ssbycode.bly.domain.realTimeCommunication

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.flow.update
import java.util.Date
import java.util.UUID

class RealTimeService(
    private val context: Context,
    private val signalingService: SignalingService,
    override val localDeviceID: String
) : RealTimeCommunication {
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private val executor = Executors.newSingleThreadExecutor()

    private val _connectedDevicesFlow = MutableStateFlow<Map<String, DeviceConnection>>(emptyMap())
    override val connectedDevicesFlow: StateFlow<Map<String, DeviceConnection>> get() = _connectedDevicesFlow

    private val _messagesFlow = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    override val messagesFlow: StateFlow<Map<String, List<Message>>> get() = _messagesFlow

    init {
        initializePeerConnectionFactory()
        setupSignaling()
    }

    override fun connectTo(remoteDeviceID: String) {
        if (_connectedDevicesFlow.value.containsKey(remoteDeviceID)) {
            Log.d("RealTimeService", "Already connected to device: $remoteDeviceID")
            return
        }

        val peerConnection = createPeerConnection(remoteDeviceID) ?: return

        // Configurar canal de dados
        val dataChannelConfig = DataChannel.Init().apply {
            ordered = true
            negotiated = false
        }

        val dataChannel = peerConnection.createDataChannel("messageChannel", dataChannelConfig)

        _connectedDevicesFlow.value[remoteDeviceID]?.let { deviceConnection ->
            _connectedDevicesFlow.update { devices ->
                devices.toMutableMap().apply {
                    put(remoteDeviceID, deviceConnection.copy(dataChannel = dataChannel))
                }
            }
        }

        // Criar e enviar oferta
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sessionDescription?.let { sdp ->
                    peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            signalingService.sendSignal(
                                localDeviceID,
                                "OFFER",
                                sdp.description,
                                remoteDeviceID
                            )
                        }
                        override fun onCreateFailure(p0: String?) {
                            Log.e("RealTimeService", "Error creating local description: $p0")
                        }
                        override fun onSetFailure(p0: String?) {
                            Log.e("RealTimeService", "Error setting local description: $p0")
                        }
                    }, sdp)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e("RealTimeService", "Error creating offer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("RealTimeService", "Error setting offer: $error")
            }
        }, constraints)
    }

    override fun disconnectFrom(remoteDeviceID: String) {
        val deviceConnection = _connectedDevicesFlow.value[remoteDeviceID] ?: return

        // Fechar canal de dados
        deviceConnection.dataChannel?.close()

        // Fechar conexão peer
        deviceConnection.connection.dispose()

        // Remover do mapa de dispositivos conectados
        _connectedDevicesFlow.update { devices ->
            devices.toMutableMap().apply { remove(remoteDeviceID) }
        }

        // Limpar mensagens do dispositivo
        _messagesFlow.update { messages ->
            messages.toMutableMap().apply { remove(remoteDeviceID) }
        }

        // Enviar sinal de bye
        signalingService.sendSignal(
            localDeviceID,
            "BYE",
            "",
            remoteDeviceID
        )
    }

    override fun disconnectAll() {
        _connectedDevicesFlow.value.keys.toList().forEach { deviceId ->
            disconnectFrom(deviceId)
        }
    }

    override fun sendMessage(data: ByteArray, toRemoteDeviceID: String) {
        try {
            val deviceConnection = _connectedDevicesFlow.value[toRemoteDeviceID] ?: run {
                Log.w("RealTimeService", "Attempt to send message to non-connected device: $toRemoteDeviceID")
                return
            }

            val dataChannel = deviceConnection.dataChannel ?: run {
                Log.w("RealTimeService", "No data channel available for device: $toRemoteDeviceID")
                return
            }

            // Verifica o estado da conexão e do canal
            if (!deviceConnection.isConnected) {
                Log.w("RealTimeService", "Connection not established with device: $toRemoteDeviceID")
                return
            }

            if (dataChannel.state() != DataChannel.State.OPEN) {
                Log.w("RealTimeService", "Data channel not open for device: $toRemoteDeviceID")
                return
            }

            // Envia a mensagem
            val buffer = ByteBuffer.wrap(data)
            val success = dataChannel.send(DataChannel.Buffer(buffer, true))

            if (success) {
                // Atualiza o flow de mensagens apenas se o envio foi bem sucedido
                _messagesFlow.update { messages ->
                    val deviceMessages = messages[toRemoteDeviceID].orEmpty().toMutableList()
                    val message = Message(
                        id = UUID.randomUUID().toString(),
                        content = String(data, Charsets.UTF_8),  // Especifica o charset explicitamente
                        senderId = localDeviceID,
                        timestamp = Date(),
                        forwardedBy = emptyList()
                    )
                    deviceMessages.add(message)
                    messages.toMutableMap().apply {
                        put(toRemoteDeviceID, deviceMessages)
                    }
                }

                Log.d("RealTimeService", "Message sent successfully to device: $toRemoteDeviceID")
            } else {
                Log.e("RealTimeService", "Failed to send message to device: $toRemoteDeviceID")
            }
        } catch (e: Exception) {
            Log.e("RealTimeService", "Error sending message to device: $toRemoteDeviceID", e)
        }
    }

    override fun broadcast(data: ByteArray) {
        _connectedDevicesFlow.value.keys.forEach { deviceId ->
            sendMessage(data, deviceId)
        }
    }

    private fun initializePeerConnectionFactory() {
        // Inicializa os globals do Android
        PeerConnectionFactory.initializeAndroidGlobals(
            context,
            true,
            true,
            true
        )

        // Inicializa o tracer interno
        PeerConnectionFactory.initializeInternalTracer()

        // Cria a factory
        peerConnectionFactory = PeerConnectionFactory()
    }

    private fun setupSignaling() {
        signalingService.listenSignal(localDeviceID) { type, data, sender, completion ->
            println("type")
//            when (type.uppercase()) {
//                SignalType.INITIAL.name -> handleInitSignal(sender, completion)
//                SignalType.OFFER.name -> handleOfferSignal(data, sender, completion)
//                SignalType.ANSWER.name -> handleAnswerSignal(data, sender, completion)
//                SignalType.CANDIDATE.name -> handleCandidateSignal(data, sender, completion)
//                SignalType.BYE.name -> handleByeSignal(sender, completion)
//                else -> completion(false)
//            }
        }
    }

    private fun handleInitSignal(sender: String, completion: (Boolean) -> Unit) {
        createPeerConnection(sender)?.let {
            completion(true)
        } ?: completion(false)
    }

    private fun handleOfferSignal(sdp: String, sender: String, completion: (Boolean) -> Unit) {
        val connection = _connectedDevicesFlow.value[sender]?.connection
            ?: createPeerConnection(sender)

        connection?.let { peerConnection ->
            val sessionDescription = SessionDescription(
                SessionDescription.Type.OFFER,
                sdp
            )

            peerConnection.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    createAndSetAnswer(sender)
                    completion(true)
                }
                override fun onCreateFailure(p0: String?) { completion(false) }
                override fun onSetFailure(p0: String?) { completion(false) }
            }, sessionDescription)
        } ?: completion(false)
    }

    private fun handleAnswerSignal(sdp: String, sender: String, completion: (Boolean) -> Unit) {
        val connection = _connectedDevicesFlow.value[sender]?.connection

        connection?.let { peerConnection ->
            val sessionDescription = SessionDescription(
                SessionDescription.Type.ANSWER,
                sdp
            )

            peerConnection.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() { completion(true) }
                override fun onCreateFailure(p0: String?) { completion(false) }
                override fun onSetFailure(p0: String?) { completion(false) }
            }, sessionDescription)
        } ?: completion(false)
    }

    private fun createPeerConnection(remoteDeviceId: String): PeerConnection? {
        val iceServers = listOf(
            PeerConnection.IceServer("stun:stun.l.google.com:19302")
        )

        val connection = peerConnectionFactory.createPeerConnection(
            iceServers,
            MediaConstraints(),
            createPeerConnectionObserver(remoteDeviceId)
        )

        connection?.let { peerConnection ->
            _connectedDevicesFlow.update { devices ->
                devices.toMutableMap().apply {
                    put(remoteDeviceId, DeviceConnection(remoteDeviceId, peerConnection))
                }
            }
        }

        return connection
    }

    private fun createPeerConnectionObserver(remoteDeviceId: String) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let {
                // Converte o candidato para formato JSON
                val candidateJson = """
                    {
                        "sdpMid": "${it.sdpMid}",
                        "sdpMLineIndex": ${it.sdpMLineIndex},
                        "sdp": "${it.sdp}"
                    }
                """.trimIndent()

                signalingService.sendSignal(
                    localDeviceID,
                    "Canditado",
                    candidateJson,
                    remoteDeviceId
                )
            }
        }

        override fun onDataChannel(dataChannel: DataChannel?) {
            dataChannel?.let { channel ->
                // Atualizar o DeviceConnection com o novo canal de dados
                _connectedDevicesFlow.value[remoteDeviceId]?.let { deviceConnection ->
                    _connectedDevicesFlow.update { devices ->
                        devices.toMutableMap().apply {
                            put(remoteDeviceId, deviceConnection.copy(dataChannel = channel))
                        }
                    }
                }

                // Configurar observer do canal de dados
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(amount: Long) {}

                    override fun onStateChange() {
                        Log.d("RealTimeService", "DataChannel state changed: ${channel.state()}")
                    }

                    override fun onMessage(buffer: DataChannel.Buffer) {
                        try {
                            val data = ByteArray(buffer.data.remaining())
                            buffer.data.get(data)

                            // Atualizar o flow de mensagens
                            _messagesFlow.update { messages ->
                                val deviceMessages = messages[remoteDeviceId].orEmpty().toMutableList()
                                val message = Message(
                                    id = UUID.randomUUID().toString(),
                                    content = String(data, Charsets.UTF_8),
                                    senderId = remoteDeviceId,
                                    timestamp = Date(),
                                    forwardedBy = emptyList()
                                )
                                deviceMessages.add(message)
                                messages.toMutableMap().apply {
                                    put(remoteDeviceId, deviceMessages)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("RealTimeService", "Error processing received message", e)
                        }
                    }
                })
            }
        }
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            newState?.let { state ->
                _connectedDevicesFlow.value[remoteDeviceId]?.let { deviceConnection ->
                    _connectedDevicesFlow.update { devices ->
                        devices.toMutableMap().apply {
                            put(remoteDeviceId, deviceConnection.copy(connectionState = state))
                        }
                    }
                }
            }
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onRenegotiationNeeded() {}
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
    }

    private fun createAndSetAnswer(sender: String) {
        val connection = _connectedDevicesFlow.value[sender]?.connection ?: return

        val constraints = MediaConstraints().apply {
            // Adiciona constraints necessários para áudio/vídeo
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        connection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sessionDescription?.let { sdp ->
                    connection.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            // Envia a resposta através do serviço de sinalização
                            signalingService.sendSignal(
                                deviceID = localDeviceID,
                                type = "ANSWER",
                                data = sdp.description,
                                receiver = sender
                            )
                        }
                        override fun onCreateFailure(p0: String?) {
                            Log.e("RealTimeService", "Error creating local description: $p0")
                        }
                        override fun onSetFailure(p0: String?) {
                            Log.e("RealTimeService", "Error setting local description: $p0")
                        }
                    }, sdp)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e("RealTimeService", "Error creating answer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("RealTimeService", "Error setting answer: $error")
            }
        }, constraints)
    }

    fun dispose() {
        try {
            _connectedDevicesFlow.value.forEach { (_, connection) ->
                connection.connection.dispose()
            }
            PeerConnectionFactory.stopInternalTracingCapture()
            PeerConnectionFactory.shutdownInternalTracer()
            peerConnectionFactory.dispose()
            executor.shutdown()
            signalingService.stopListening(localDeviceID)
        } catch (e: Exception) {
            println("RealTimeService Error disposing RealTimeService")
        }
    }
}