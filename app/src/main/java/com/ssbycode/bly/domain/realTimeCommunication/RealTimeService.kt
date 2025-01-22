package com.ssbycode.bly.domain.realTimeCommunication

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
import com.ssbycode.bly.data.realTimeCommunication.TimestampAdapter
import com.ssbycode.bly.domain.communication.SignalingService
import com.ssbycode.bly.domain.firebase.SignalType
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
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

    // Public Methods
    override fun connectTo(remoteDeviceID: String) {
        Log.i("RealTimeService", "Initiating connection to device: $remoteDeviceID")

        if (_connectedDevicesFlow.value.containsKey(remoteDeviceID)) {
            Log.d("RealTimeService", "Already connected to device: $remoteDeviceID")
            return
        }

        val connection = createConnection(remoteDeviceID) ?: run {
            Log.e("RealTimeService", "Failed to create peer connection for device: $remoteDeviceID")
            return
        }

        val dataChannel =
            createDataChannelFor(connection = connection, remoteDeviceID = remoteDeviceID)

        _connectedDevicesFlow.update { currentMap ->
            currentMap + (remoteDeviceID to DeviceConnection(
                dataChannel = dataChannel,
                deviceId = remoteDeviceID,
                connection = connection
            ))
        }

        signalingService.sendSignal(
            deviceID = localDeviceID,
            type = SignalType.INITIAL.value,
            data = "none",
            receiver = remoteDeviceID
        )

        // Criamos a oferta SDP primeiro
        createAndSendOfferTo(remoteDeviceID = remoteDeviceID, connection = connection)
    }

    override fun disconnectFrom(remoteDeviceID: String) {
        Log.i("RealTimeService", "Disconnecting from device: $remoteDeviceID")

        val deviceConnection = _connectedDevicesFlow.value[remoteDeviceID] ?: run {
            Log.d("RealTimeService", "Not connected to device: $remoteDeviceID")
            return
        }

        deviceConnection.dataChannel?.close()
        deviceConnection.connection.dispose()

        _connectedDevicesFlow.update { devices ->
            devices.toMutableMap().apply { remove(remoteDeviceID) }
        }

        _messagesFlow.update { messages ->
            messages.toMutableMap().apply { remove(remoteDeviceID) }
        }

        signalingService.sendSignal(
            localDeviceID,
            SignalType.BYE.value,
            "disconnect",
            remoteDeviceID
        )

        Log.i("RealTimeService", "Disconnected from device: $remoteDeviceID")
    }

    override fun disconnectAll() {
        Log.i("RealTimeService", "Disconnecting from all devices")

        _connectedDevicesFlow.value.keys.toList().forEach { deviceId ->
            disconnectFrom(deviceId)
        }

        _messagesFlow.value = emptyMap()
    }

    override fun broadcast(data: ByteArray) {
        _connectedDevicesFlow.value.forEach { (deviceID, connection) ->
            // Verifica se a conexão está pronta para enviar mensagens
            if (!connection.isConnected) {
                return@forEach
            }

            val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), false)
            connection.dataChannel?.send(buffer)
            Log.d("RealTimeService", "Message sent via: $deviceID")
        }

        // Processa a mensagem local
        handleIncomingMessage(data = data, deviceID = localDeviceID)
    }

    fun dispose() {
        try {
            disconnectAll()
            PeerConnectionFactory.stopInternalTracingCapture()
            PeerConnectionFactory.shutdownInternalTracer()
            peerConnectionFactory.dispose()
            executor.shutdown()
            signalingService.stopListening(localDeviceID)
        } catch (e: Exception) {
            Log.e("RealTimeService", "Error disposing RealTimeService", e)
        }
    }

    // Private Methods - Connection Setup
    private fun initializePeerConnectionFactory() {
        // Configuração global
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(initializationOptions)

        // Configuração da fábrica de conexões
        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    private fun setupSignaling() {
        Log.i("RealTimeService", "Setting up signaling service")

        signalingService.listenSignal(localDeviceID) { type, data, sender, completion ->
            handleSignal(type, data, sender, completion)
        }
    }

    private fun createConnection(remoteDeviceId: String): PeerConnection? {
        // Configuração dos servidores ICE
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )

        // Configuração RTC
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceConnectionReceivingTimeout = 3000
            iceBackupCandidatePairPingInterval = 5000
        }

        // Restrições para a conexão
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        // Criação do PeerConnection
        return peerConnectionFactory.createPeerConnection(
            rtcConfig,
            constraints,
            createPeerConnectionObserver(remoteDeviceId)
        )
    }

    private fun createDataChannelFor(
        connection: PeerConnection,
        remoteDeviceID: String
    ): DataChannel? {
        val dataChannelConfig = DataChannel.Init().apply {
            ordered = true
            negotiated = true
            id = 0
        }

        val label = "data-$remoteDeviceID"
        val dataChannel = connection.createDataChannel(label, dataChannelConfig) ?: run {
            Log.e("RealTimeService", "Failed to create data channel for peer: $remoteDeviceID")
            return null
        }

        setupDataChannelObserver(dataChannel, remoteDeviceID)
        Log.d("RealTimeService", "📡 Data channel created for peer: $remoteDeviceID")
        logDataChannelState(dataChannel, remoteDeviceID)
        return dataChannel
    }

    private fun logDataChannelState(dataChannel: DataChannel, peerId: String) {
        Log.d(
            "WebRTC", """
        DataChannel state for peer $peerId:
        - Label: ${dataChannel.label()}
        - ID: ${dataChannel.id()}
        - State: ${dataChannel.state()}
    """.trimIndent()
        )
    }

    private fun createAndSendOfferTo(remoteDeviceID: String, connection: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        // Criar e enviar a oferta
        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                setLocalDescriptionAndSendSignal(connection, sdp, remoteDeviceID)
            }

            override fun onCreateFailure(error: String) {
                Log.e("RealTimeService", "Failed to create offer: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    private fun setLocalDescriptionAndSendSignal(
        connection: PeerConnection,
        sdp: SessionDescription,
        remoteDeviceID: String
    ) {
        connection.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d("RealTimeService", "Local description set successfully")
                signalingService.sendSignal(
                    deviceID = localDeviceID,
                    type = SignalType.OFFER.value,
                    data = sdp.description,
                    receiver = remoteDeviceID
                )
            }

            override fun onSetFailure(error: String) {
                Log.e("RealTimeService", "Failed to set local description: $error")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    private fun createAnswerFor(remoteDeviceID: String) {
        val connection = _connectedDevicesFlow.value[remoteDeviceID]?.connection ?: run {
            Log.e(
                "RealTimeService",
                "Failed to create answer - No connection found for device: $remoteDeviceID"
            )
            return
        }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        connection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sessionDescription?.let { sdp ->
                    connection.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            signalingService.sendSignal(
                                deviceID = localDeviceID,
                                type = SignalType.ANSWER.value,
                                data = sdp.description,
                                receiver = remoteDeviceID
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

    private fun setupDataChannelObserver(dataChannel: DataChannel, remoteDeviceId: String) {
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                try {
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)

                    Log.d(
                        "RealTimeService", """
                        📥 Received data:
                        - Channel Label: ${dataChannel.label()}
                        - Data Size: ${data.size} bytes
                        - Is Binary: ${buffer.binary}
                    """.trimIndent()
                    )

                    CoroutineScope(Dispatchers.Main).launch {
                        handleIncomingMessage(data, remoteDeviceId)
                    }
                } catch (e: Exception) {
                    Log.e("RealTimeService", "Error processing received message", e)
                }
            }

            override fun onBufferedAmountChange(amount: Long) {
                Log.d("RealTimeService", "Buffer amount changed: $amount")
            }

            override fun onStateChange() {
                val state = dataChannel.state()
                Log.d(
                    "RealTimeService", """
                    DataChannel state changed:
                    - Label: ${dataChannel.label()}
                    - New State: $state
                """.trimIndent()
                )

                if (state == DataChannel.State.OPEN) {
                    _connectedDevicesFlow.value[remoteDeviceId]?.let { deviceConnection ->
                        _connectedDevicesFlow.update { devices ->
                            devices.toMutableMap().apply {
                                put(
                                    remoteDeviceId, deviceConnection.copy(
                                        dataChannel = dataChannel,
                                        connectionState = PeerConnection.IceConnectionState.CONNECTED
                                    )
                                )
                            }
                        }
                    }
                }
            }
        })
    }

    // Private Methods - Connection Observer
    private fun createPeerConnectionObserver(remoteDeviceId: String) =
        object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d("RealTimeService", "🧊 onIceCandidate: ${it.sdp}")
                    val candidateData = """
                {
                    "sdp": "${it.sdp}",
                    "sdpMid": "${it.sdpMid ?: "0"}",
                    "sdpMLineIndex": ${it.sdpMLineIndex}
                }
            """.trimIndent()

                    signalingService.sendSignal(
                        localDeviceID,
                        SignalType.CANDIDATE.value,
                        candidateData,
                        remoteDeviceId
                    )
                }
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
                TODO("Not yet implemented")
            }

            override fun onAddStream(p0: MediaStream?) {
                TODO("Not yet implemented")
            }

            override fun onRemoveStream(p0: MediaStream?) {
                TODO("Not yet implemented")
            }

            override fun onDataChannel(dataChannel: DataChannel?) {
                dataChannel?.let { channel ->
                    Log.d(
                        "RealTimeService", """
                📨 Data Channel received:
                - Label: ${channel.label()}
                - State: ${channel.state()}
            """.trimIndent()
                    )
                    setupDataChannelObserver(channel, remoteDeviceId)
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                newState?.let { state ->
                    Log.d(
                        "RealTimeService", """
                🧊 ICE Connection state changed for peer $remoteDeviceId:
                - Previous State: ${_connectedDevicesFlow.value[remoteDeviceId]?.connectionState}
                - New State: $state
            """.trimIndent()
                    )

                    _connectedDevicesFlow.update { devices ->
                        devices.toMutableMap().apply {
                            val deviceConnection =
                                this[remoteDeviceId]?.copy(connectionState = state)
                            if (deviceConnection != null) {
                                this[remoteDeviceId] = deviceConnection
                            }
                        }
                    }
                }
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {
                Log.d("RealTimeService", "🧊 ICE Connection receiving changed: $p0")
            }

            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                Log.d("RealTimeService", "🧊 ICE Gathering state changed to: $p0")
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                Log.d("RealTimeService", "🎥 Track added: ${transceiver?.receiver?.track()?.id()}")
            }

            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
                TODO("Not yet implemented")
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
                Log.d("RealTimeService", "📡 Signaling state changed to: $p0")
            }
        }

    private fun isMessageProcessed(message: Message): Boolean {
        // Verificar se a mensagem já existe em alguma conversa
        return _messagesFlow.value.any { (_, messages) ->
            messages.any { it.id == message.id }
        }
    }

    private fun retransmitMessage(message: Message, excludingDeviceID: String) {
        // Enviar para todos os dispositivos conectados exceto o remetente
        _connectedDevicesFlow.value.forEach { (deviceID, connection) ->
            if (deviceID != excludingDeviceID && connection.isConnected) {
                val jsonString = Gson().toJson(message)
                val data = jsonString.toByteArray(Charsets.UTF_8)
                val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), false)
                connection.dataChannel?.send(buffer)
                Log.i("RealTimeService", "Retransmitted message to ${connection.deviceId}")
            }
        }
    }

    // Handlers
    private fun handleIncomingMessage(data: ByteArray, deviceID: String) {
        try {
            val jsonString = String(data, Charsets.UTF_8)
            Log.d("RealTimeService", "Received JSON: $jsonString")

            val json = Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            }
            val message = json.decodeFromString<Message>(jsonString)

            if (!isMessageProcessed(message)) {
                _messagesFlow.update { messages ->
                    val deviceMessages = messages[deviceID].orEmpty().toMutableList()
                    deviceMessages.add(message.copy(isFromCurrentUser = message.senderId == localDeviceID))
                    messages.toMutableMap().apply {
                        put(deviceID, deviceMessages)
                    }
                }

                if (message.senderId != localDeviceID) {
                    retransmitMessage(message, excludingDeviceID = deviceID)
                }
            }
        } catch (e: Exception) {
            Log.e("RealTimeService", "Error decoding message", e)
            e.printStackTrace()
        }
    }

    private fun handleSignal(
        type: String,
        data: String,
        sender: String,
        completion: (Boolean) -> Unit
    ) {
        Log.d("SignalHandler", "📡 Received signal: $type from peer: $sender")

        when (type.lowercase()) {
            SignalType.INITIAL.value.lowercase() -> handleInitSignal(sender) { success ->
                Log.d("SignalHandler", "✅ Init signal handled: $success")
                completion(success)
            }

            SignalType.OFFER.value.lowercase() -> handleOfferSignal(data, sender) { success ->
                Log.d("SignalHandler", "✅ Offer signal handled: $success")
                completion(success)
            }

            SignalType.ANSWER.value.lowercase() -> handleAnswerSignal(data, sender) { success ->
                Log.d("SignalHandler", "✅ Answer signal handled: $success")
                completion(success)
            }

            SignalType.CANDIDATE.value.lowercase() -> handleCandidateSignal(
                data,
                sender
            ) { success ->
                Log.d("SignalHandler", "✅ Candidate signal handled: $success")
                completion(success)
            }

            SignalType.BYE.value.lowercase() -> {
                handleByeSignal(sender) { success ->
                    Log.d("SignalHandler", "✅ Bye signal handled: $success")
                    completion(success)
                }
            }

            else -> {
                Log.e("SignalHandler", "❌ Unknown signal type: $type")
                completion(false)
            }
        }
    }

    private fun handleInitSignal(sender: String, completion: (Boolean) -> Unit) {
        Log.i("RealTimeService", "Handling init from: $sender")

        if (!_connectedDevicesFlow.value.containsKey(sender)) {
            val connection = createConnection(sender) ?: run {
                Log.e("RealTimeService", "Failed to create peer connection")
                completion(false)
                return
            }

            val dataChannel = createDataChannelFor(connection, sender)

            _connectedDevicesFlow.update { devices ->
                devices.toMutableMap().apply {
                    put(sender, DeviceConnection(sender, connection, dataChannel))
                }
            }

            Log.d("RealTimeService", "✅ New peer connection created for: $sender")
        } else {
            Log.d("RealTimeService", "Connection already exists for: $sender")
        }
    }

    private fun handleOfferSignal(sdp: String, sender: String, completion: (Boolean) -> Unit) {
        Log.i("RealTimeService", "📥 Handling offer from: $sender")

        val connection = _connectedDevicesFlow.value[sender]?.connection ?: run {
            Log.i("RealTimeService", "❌ No peer connection for offer from: $sender")
            completion(false)
            return
        }

        connection.let { peerConnection ->
            val sessionDescription = SessionDescription(
                SessionDescription.Type.OFFER,
                sdp
            )

            peerConnection.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    createAnswerFor(sender)
                    completion(true)
                }

                override fun onCreateFailure(p0: String?) {
                    Log.e("RealTimeService", "❌ Failed to set remote description: $p0")
                    completion(false)
                }

                override fun onSetFailure(p0: String?) {
                    Log.e("RealTimeService", "❌ Failed to set remote description: $p0")
                    completion(false)
                }
            }, sessionDescription)
        }
    }

    private fun handleAnswerSignal(sdp: String, sender: String, completion: (Boolean) -> Unit) {
        Log.i("RealTimeService", "📥 Handling answer from: $sender")

        val connection = _connectedDevicesFlow.value[sender]?.connection ?: run {
            Log.e("RealTimeService", "❌ No peer connection found for: $sender")
            completion(false)
            return
        }

        connection.let { peerConnection ->
            val sessionDescription = SessionDescription(
                SessionDescription.Type.ANSWER,
                sdp
            )

            peerConnection.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    completion(true)
                }

                override fun onCreateFailure(p0: String?) {
                    completion(false)
                }

                override fun onSetFailure(p0: String?) {
                    completion(false)
                }
            }, sessionDescription)
        }
    }

    private fun handleCandidateSignal(data: String, sender: String, completion: (Boolean) -> Unit) {
        Log.i("RealTimeService", "📥 Handling candidate from: $sender")

        val connection = _connectedDevicesFlow.value[sender]?.connection ?: run {
            Log.e("RealTimeService", "Error handling candidate signal")
            completion(false)
            return
        }

        val candidate = IceCandidate("0", 0, data)
        connection.addIceCandidate(candidate)
        completion(true)
    }

    private fun handleByeSignal(sender: String, completion: (Boolean) -> Unit) {
        Log.i("RealTimeService", "📥 Handling bye from: $sender")
        disconnectFrom(sender)
        completion(true)
    }
}