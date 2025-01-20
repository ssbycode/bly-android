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
//import org.webrtc.*
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
import org.json.JSONObject
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

    // Public Methods
    override fun connectTo(remoteDeviceID: String) {
        Log.i("RealTimeService", "Initiating connection to device: $remoteDeviceID")

        if (_connectedDevicesFlow.value.containsKey(remoteDeviceID)) {
            Log.d("RealTimeService", "Already connected to device: $remoteDeviceID")
            return
        }

        val connection = createPeerConnection(remoteDeviceID) ?: run {
            Log.e("RealTimeService", "Failed to create peer connection for device: $remoteDeviceID")
            return
        }

        // Primeiro registramos apenas a conexão
        _connectedDevicesFlow.update { currentMap ->
            currentMap + (remoteDeviceID to DeviceConnection(
                deviceId = remoteDeviceID,
                connection = connection
            ))
        }

        // Enviamos o sinal de inicialização
        signalingService.sendSignal(
            deviceID = localDeviceID,
            type = SignalType.INITIAL.value,
            data = "none",
            receiver = remoteDeviceID
        )

        // Criamos a oferta SDP primeiro
        createAndSendOffer(remoteDeviceID = remoteDeviceID, connection = connection)
    }

    override fun disconnectFrom(remoteDeviceID: String) {
        Log.i("RealTimeService", "Disconnecting from device: $remoteDeviceID")

        if (!_connectedDevicesFlow.value.containsKey(remoteDeviceID)) {
            Log.d("RealTimeService", "Not connected to device: $remoteDeviceID")
            return
        }

        val deviceConnection = _connectedDevicesFlow.value[remoteDeviceID] ?: return

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

    override fun sendMessage(data: ByteArray, toRemoteDeviceID: String) {
        Log.i("RealTimeService", """
        Attempting to send message:
        - Target Device: $toRemoteDeviceID
        - Data Size: ${data.size} bytes
    """.trimIndent())

        try {
            val deviceConnection = _connectedDevicesFlow.value[toRemoteDeviceID] ?: run {
                Log.w("RealTimeService", "No connection found for device: $toRemoteDeviceID")
                return
            }

            val dataChannel = deviceConnection.dataChannel ?: run {
                Log.w("RealTimeService", "No DataChannel available for device: $toRemoteDeviceID")
                return
            }

            Log.d("RealTimeService", """
            Connection Status:
            - ICE State: ${deviceConnection.connectionState}
            - DataChannel State: ${dataChannel.state()}
            - Is Connected: ${deviceConnection.isConnected}
        """.trimIndent())

            if (dataChannel.state() != DataChannel.State.OPEN) {
                Log.w("RealTimeService", """
                Cannot send message - DataChannel not ready:
                - Current State: ${dataChannel.state()}
                - Connection State: ${deviceConnection.connectionState}
            """.trimIndent())
                return
            }

            val message = Message(
                id = UUID.randomUUID().toString(),
                content = String(data, Charsets.UTF_8),
                senderId = localDeviceID,
                timestamp = System.currentTimeMillis(), // Usa milissegundos diretamente
                forwardedBy = emptyList()
            )

            // O resto pode continuar igual
            val jsonString = Gson().toJson(message)
            val jsonData = jsonString.toByteArray(Charsets.UTF_8)

            // Log para debug
            Log.d("RealTimeService", "Sending JSON: $jsonString")

            val buffer = ByteBuffer.wrap(jsonData)
            val success = dataChannel.send(DataChannel.Buffer(buffer, false))

            if (success) {
                _messagesFlow.update { messages ->
                    val deviceMessages = messages[toRemoteDeviceID].orEmpty().toMutableList()
                    deviceMessages.add(message)
                    messages.toMutableMap().apply {
                        put(toRemoteDeviceID, deviceMessages)
                    }
                }
                Log.d("RealTimeService", "✅ Message sent successfully to: $toRemoteDeviceID")
            } else {
                Log.e("RealTimeService", "❌ Failed to send message to: $toRemoteDeviceID")
            }
        } catch (e: Exception) {
            Log.e("RealTimeService", "Error sending message to $toRemoteDeviceID", e)
        }
    }

    override fun broadcast(data: ByteArray) {
        _connectedDevicesFlow.value.keys.forEach { deviceId ->
            sendMessage(data, deviceId)
        }
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

    private fun createPeerConnection(remoteDeviceId: String): PeerConnection? {
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

        Log.d(
            "RealTimeService", """
        Creating peer connection:
        - Device ID: $remoteDeviceId
        - Bundle Policy: ${rtcConfig.bundlePolicy}
        - ICE Servers: ${rtcConfig.iceServers.size}
    """.trimIndent()
        )

        // Criação do PeerConnection
        return peerConnectionFactory.createPeerConnection(
            rtcConfig,
            constraints,
            createPeerConnectionObserver(remoteDeviceId)
        )
    }

    private fun createDataChannel(connection: PeerConnection, remoteDeviceID: String): DataChannel? {
        // Configuração idêntica ao iOS
        val dataChannelInit = DataChannel.Init().apply {
            ordered = true
            negotiated = true
            id = 0
        }

        return try {
            val label = "data-$remoteDeviceID"
            val dataChannel = connection.createDataChannel(label, dataChannelInit)

            if (dataChannel == null) {
                Log.e("WebRTC", "Failed to create data channel for peer: $remoteDeviceID")
                return null
            }

            setupDataChannelObserver(dataChannel, remoteDeviceID)
            Log.d("WebRTC", "📡 Data channel created for peer: $remoteDeviceID")
            logDataChannelState(dataChannel, remoteDeviceID)

            dataChannel
        } catch (e: Exception) {
            Log.e("WebRTC", "Error creating data channel", e)
            null
        }
    }

    private fun logDataChannelState(dataChannel: DataChannel, peerId: String) {
        Log.d("WebRTC", """
        DataChannel state for peer $peerId:
        - Label: ${dataChannel.label()}
        - ID: ${dataChannel.id()}
        - State: ${dataChannel.state()}
    """.trimIndent())
    }

    private fun setupDataChannelObserver(dataChannel: DataChannel, remoteDeviceId: String) {
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                try {
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)

                    Log.d("RealTimeService", """
                        📥 Received data:
                        - Channel Label: ${dataChannel.label()}
                        - Data Size: ${data.size} bytes
                        - Is Binary: ${buffer.binary}
                    """.trimIndent())

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
                Log.d("RealTimeService", """
                    DataChannel state changed:
                    - Label: ${dataChannel.label()}
                    - New State: $state
                """.trimIndent())

                if (state == DataChannel.State.OPEN) {
                    _connectedDevicesFlow.value[remoteDeviceId]?.let { deviceConnection ->
                        _connectedDevicesFlow.update { devices ->
                            devices.toMutableMap().apply {
                                put(remoteDeviceId, deviceConnection.copy(
                                    dataChannel = dataChannel,
                                    connectionState = PeerConnection.IceConnectionState.CONNECTED
                                ))
                            }
                        }
                    }
                }
            }
        })
    }

    // Private Methods - Connection Observer
    private fun createPeerConnectionObserver(remoteDeviceId: String) = object : PeerConnection.Observer {
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
                Log.d("RealTimeService", """
                📨 Data Channel received:
                - Label: ${channel.label()}
                - State: ${channel.state()}
            """.trimIndent())
                setupDataChannelObserver(channel, remoteDeviceId)
            }
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            newState?.let { state ->
                Log.d("RealTimeService", """
                🧊 ICE Connection state changed for peer $remoteDeviceId:
                - Previous State: ${_connectedDevicesFlow.value[remoteDeviceId]?.connectionState}
                - New State: $state
            """.trimIndent())

                _connectedDevicesFlow.update { devices ->
                    devices.toMutableMap().apply {
                        val deviceConnection = this[remoteDeviceId]?.copy(connectionState = state)
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

    // Private Methods - Signaling
    private fun setupSignaling() {
        signalingService.listenSignal(localDeviceID) { type, data, sender, completion ->
            when (type.uppercase()) {
                SignalType.INITIAL.value.uppercase() -> handleInitSignal(sender, completion)
                SignalType.OFFER.value.uppercase() -> handleOfferSignal(data, sender, completion)
                SignalType.ANSWER.value.uppercase() -> handleAnswerSignal(data, sender, completion)
                SignalType.CANDIDATE.value.uppercase() -> handleCandidateSignal(data, sender, completion)
                SignalType.BYE.value.uppercase() -> handleByeSignal(sender, completion)
                else -> completion(false)
            }
        }
    }

    // Private Methods - Signal Handlers
    private fun handleInitSignal(sender: String, completion: (Boolean) -> Unit) {
        try {
            if (!_connectedDevicesFlow.value.containsKey(sender)) {
                val peerConnection = createPeerConnection(sender)
                    ?: throw Exception("Failed to create peer connection")

                val dataChannel = createDataChannel(peerConnection, sender)

                _connectedDevicesFlow.update { devices ->
                    devices.toMutableMap().apply {
                        put(sender, DeviceConnection(sender, peerConnection, dataChannel))
                    }
                }

                Log.d("RealTimeService", "New peer connection created for: $sender")
            } else {
                Log.d("RealTimeService", "Connection already exists for: $sender")
            }

            completion(true)
        } catch (e: Exception) {
            Log.e("RealTimeService", "Error handling init signal: ${e.message}")
            completion(false)
        }
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

    private fun handleCandidateSignal(data: String, sender: String, completion: (Boolean) -> Unit) {
        try {
            val connection = _connectedDevicesFlow.value[sender]?.connection ?: run {
                completion(false)
                return
            }

            val candidate = IceCandidate("0", 0, data)
            connection.addIceCandidate(candidate)
            completion(true)
        } catch (e: Exception) {
            Log.e("RealTimeService", "Error handling candidate signal: ${e.message}", e)
            completion(false)
        }
    }

    private fun handleByeSignal(sender: String, completion: (Boolean) -> Unit) {
        try {
            val deviceConnection = _connectedDevicesFlow.value[sender] ?: run {
                Log.w("RealTimeService", "Received BYE signal from non-connected device: $sender")
                completion(false)
                return
            }

            deviceConnection.dataChannel?.close()
            deviceConnection.connection.dispose()

            _connectedDevicesFlow.update { devices ->
                devices.toMutableMap().apply { remove(sender) }
            }

            _messagesFlow.update { messages ->
                messages.toMutableMap().apply { remove(sender) }
            }

            Log.d("RealTimeService", "Successfully handled BYE signal from: $sender")
            completion(true)
        } catch (e: Exception) {
            Log.e("RealTimeService", "Error handling BYE signal from $sender", e)
            completion(false)
        }
    }

    // Private Methods - Message Handling
    private suspend fun handleIncomingMessage(data: ByteArray, deviceID: String) {
        try {
            // Criar o Gson customizado com o adapter
            val gson = GsonBuilder()
                .registerTypeAdapter(Long::class.java, TimestampAdapter())
                .create()

            // Converter bytes para string
            val jsonString = String(data, Charsets.UTF_8)
            Log.d("RealTimeService", "Received JSON: $jsonString")

            // Usar o gson customizado aqui, não o Gson() padrão
            val message = gson.fromJson(jsonString, Message::class.java)

            // O resto do código permanece igual
            if (!isMessageProcessed(message)) {
                _messagesFlow.update { messages ->
                    val deviceMessages = messages[deviceID].orEmpty().toMutableList()
                    deviceMessages.add(message)
                    messages.toMutableMap().apply {
                        put(deviceID, deviceMessages)
                    }
                }

                if (message.senderId != localDeviceID && !message.forwardedBy.contains(localDeviceID)) {
                    val updatedMessage = message.copy(
                        forwardedBy = message.forwardedBy + localDeviceID
                    )
                    retransmitMessage(updatedMessage, excludingDeviceID = deviceID)
                }
            }
        } catch (e: Exception) {
            Log.e("RealTimeService", "Error decoding message", e)
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
                val jsonData = jsonString.toByteArray(Charsets.UTF_8)
                sendMessage(jsonData, deviceID)
            }
        }
    }

    // Private Methods - Session Description Methods
//    private fun createPeerConnection(remoteDeviceId: String): PeerConnection? {
//        val iceServers = listOf(
//            PeerConnection.IceServer("stun:stun.l.google.com:19302"),
//            PeerConnection.IceServer("stun:stun1.l.google.com:19302"),
//            PeerConnection.IceServer("stun:stun2.l.google.com:19302")
//        )
//
//        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
//            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
//            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
//            keyType = PeerConnection.KeyType.ECDSA
//        }
//
//        val constraints = MediaConstraints().apply {
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
//            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
//        }
//
//        Log.d("RealTimeService", "Creating peer connection for device: $remoteDeviceId")
//
//        return peerConnectionFactory.createPeerConnection(
//            rtcConfig,
//            constraints,
//            createPeerConnectionObserver(remoteDeviceId)
//        )
//    }

    private fun createAndSendOffer(remoteDeviceID: String, connection: PeerConnection) {
        Log.d("WebRTC", "Starting offer creation process")

        // Criar o DataChannel primeiro
        val dataChannel = createDataChannel(connection, remoteDeviceID)
        if (dataChannel == null) {
            Log.e("WebRTC", "Aborting offer creation due to DataChannel creation failure")
            return
        }

        // Atualizar a conexão com o DataChannel
        _connectedDevicesFlow.update { devices ->
            devices.toMutableMap().apply {
                val currentConnection = devices[remoteDeviceID]
                if (currentConnection != null) {
                    put(remoteDeviceID, currentConnection.copy(dataChannel = dataChannel))
                }
            }
        }

        // Criar oferta com as mesmas constraints do iOS
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        try {
            connection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    connection.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d("WebRTC", "Local description set successfully")
                            signalingService.sendSignal(
                                deviceID = localDeviceID,
                                type = SignalType.OFFER.value,
                                data = sdp.description,
                                receiver = remoteDeviceID
                            )
                        }
                        override fun onSetFailure(error: String) {
                            Log.e("WebRTC", "Failed to set local description: $error")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, sdp)
                }
                override fun onCreateFailure(error: String) {
                    Log.e("WebRTC", "Failed to create offer: $error")
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, constraints)

        } catch (e: Exception) {
            Log.e("WebRTC", "Error in createAndSendOffer", e)
        }
    }

    private fun createAndSetAnswer(sender: String) {
        val connection = _connectedDevicesFlow.value[sender]?.connection ?: return

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
}
//
//class RealTimeService(
//    private val context: Context,
//    private val signalingService: SignalingService,
//    override val localDeviceID: String
//) : RealTimeCommunication {
//    private lateinit var peerConnectionFactory: PeerConnectionFactory
//    private val executor = Executors.newSingleThreadExecutor()
//
//    private val _connectedDevicesFlow = MutableStateFlow<Map<String, DeviceConnection>>(emptyMap())
//    override val connectedDevicesFlow: StateFlow<Map<String, DeviceConnection>> get() = _connectedDevicesFlow
//
//    private val _messagesFlow = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
//    override val messagesFlow: StateFlow<Map<String, List<Message>>> get() = _messagesFlow
//
//    init {
//        initializePeerConnectionFactory()
//        setupSignaling()
//    }
//
//    override fun connectTo(remoteDeviceID: String) {
//        Log.i("RealTimeService", "Initiating connection to device: $remoteDeviceID")
//
//        if (_connectedDevicesFlow.value.containsKey(remoteDeviceID)) {
//            Log.d("RealTimeService", "Already connected to device: $remoteDeviceID")
//            return
//        }
//
//        val connection = createPeerConnection(remoteDeviceID)
//
//        if (connection == null) {
//            Log.e("RealTimeService", "Failed to create peer connection for device: $remoteDeviceID")
//            return
//        }
//
//        // Configurar canal de dados
//        val dataChannelConfig = DataChannel.Init().apply {
//            ordered = true
//            negotiated = false
//        }
//
//        val dataChannel =
//            createDataChannel(connection = connection, remoteDeviceID = remoteDeviceID)
//        val newConnection = DeviceConnection(
//            deviceId = remoteDeviceID,
//            connection = connection,
//            dataChannel = dataChannel
//        )
//
//        _connectedDevicesFlow.update { currentMap ->
//            currentMap + (remoteDeviceID to newConnection)
//        }
//
//        signalingService.sendSignal(
//            deviceID = localDeviceID,
//            type = SignalType.INITIAL.value,
//            data = "none",
//            receiver = remoteDeviceID
//        )
//
//        createAndSendOffer(remoteDeviceID = remoteDeviceID, connection = connection)
//    }
//
//    override fun disconnectFrom(remoteDeviceID: String) {
//        Log.i("RealTimeService", "Disconnecting from device: $remoteDeviceID")
//
//        if (!_connectedDevicesFlow.value.containsKey(remoteDeviceID)) {
//            Log.d("RealTimeService", "Not connected to device: $remoteDeviceID")
//            return
//        }
//
//        val deviceConnection = _connectedDevicesFlow.value[remoteDeviceID] ?: return
//
//        deviceConnection.dataChannel?.close()
//        deviceConnection.connection.dispose()
//
//        // Remover do mapa de dispositivos conectados
//        _connectedDevicesFlow.update { devices ->
//            devices.toMutableMap().apply { remove(remoteDeviceID) }
//        }
//
//        // Limpar mensagens do dispositivo
//        _messagesFlow.update { messages ->
//            messages.toMutableMap().apply { remove(remoteDeviceID) }
//        }
//
//        // Enviar sinal de bye
//        signalingService.sendSignal(
//            localDeviceID,
//            SignalType.BYE.value,
//            "disconnect",
//            remoteDeviceID
//        )
//
//        Log.i("RealTimeService", "Disconnected from device: $remoteDeviceID")
//    }
//
//    override fun disconnectAll() {
//        Log.i("RealTimeService", "Disconnecting from all devices")
//
//        _connectedDevicesFlow.value.keys.toList().forEach { deviceId ->
//            disconnectFrom(deviceId)
//        }
//
//        _messagesFlow.value = emptyMap()
//    }
//
//    override fun sendMessage(data: ByteArray, toRemoteDeviceID: String) {
//        Log.i(
//            "RealTimeService", """
//        Attempting to send message:
//        - Target Device: $toRemoteDeviceID
//        - Data Size: ${data.size} bytes
//    """.trimIndent()
//        )
//
//        try {
//            val deviceConnection = _connectedDevicesFlow.value[toRemoteDeviceID] ?: run {
//                Log.w("RealTimeService", "No connection found for device: $toRemoteDeviceID")
//                return
//            }
//
//            val dataChannel = deviceConnection.dataChannel ?: run {
//                Log.w("RealTimeService", "No DataChannel available for device: $toRemoteDeviceID")
//                return
//            }
//
//            Log.d(
//                "RealTimeService", """
//            Connection Status:
//            - ICE State: ${deviceConnection.connectionState}
//            - DataChannel State: ${dataChannel.state()}
//            - Is Connected: ${deviceConnection.isConnected}
//        """.trimIndent()
//            )
//
//            // Verificamos se o DataChannel está pronto antes de tentar enviar
//            if (dataChannel.state() != DataChannel.State.OPEN) {
//                Log.w(
//                    "RealTimeService", """
//                Cannot send message - DataChannel not ready:
//                - Current State: ${dataChannel.state()}
//                - Connection State: ${deviceConnection.connectionState}
//            """.trimIndent()
//                )
//                return
//            }
//
//            // Tenta enviar a mensagem
//            val buffer = ByteBuffer.wrap(data)
//            val success = dataChannel.send(DataChannel.Buffer(buffer, false))
//
//            if (success) {
//                _messagesFlow.update { messages ->
//                    val deviceMessages = messages[toRemoteDeviceID].orEmpty().toMutableList()
//                    val message = Message(
//                        id = UUID.randomUUID().toString(),
//                        content = String(data, Charsets.UTF_8),
//                        senderId = localDeviceID,
//                        timestamp = Date(),
//                        forwardedBy = emptyList()
//                    )
//                    deviceMessages.add(message)
//                    messages.toMutableMap().apply {
//                        put(toRemoteDeviceID, deviceMessages)
//                    }
//                }
//                Log.d("RealTimeService", "✅ Message sent successfully to: $toRemoteDeviceID")
//            } else {
//                Log.e("RealTimeService", "❌ Failed to send message to: $toRemoteDeviceID")
//            }
//        } catch (e: Exception) {
//            Log.e(
//                "RealTimeService",
//                "Error sending message to $toRemoteDeviceID",
//                e
//            )
//        }
//    }
//
//    override fun broadcast(data: ByteArray) {
//        _connectedDevicesFlow.value.keys.forEach { deviceId ->
//            sendMessage(data, deviceId)
//        }
//    }
//
//    private fun initializePeerConnectionFactory() {
//        // Inicializa os globals do Android
//        PeerConnectionFactory.initializeAndroidGlobals(
//            context,
//            true,
//            true,
//            true
//        )
//
//        // Inicializa o tracer interno
//        PeerConnectionFactory.initializeInternalTracer()
//
//        // Cria a factory
//        peerConnectionFactory = PeerConnectionFactory()
//    }
//
//    private fun createAndSendOffer(remoteDeviceID: String, connection: PeerConnection) {
//        val constraints = MediaConstraints().apply {
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
//        }
//
//        connection.createOffer(object : SdpObserver {
//            override fun onCreateSuccess(sdp: SessionDescription) {
//                connection.setLocalDescription(object : SdpObserver {
//                    override fun onSetSuccess() {
//                        signalingService.sendSignal(
//                            deviceID = localDeviceID,
//                            type = SignalType.OFFER.value,
//                            data = sdp.description,
//                            receiver = remoteDeviceID
//                        )
//                    }
//
//                    override fun onSetFailure(error: String) {
//                        Log.e("WebRTC", "Failed to set local description: $error")
//                    }
//
//                    override fun onCreateSuccess(sdp: SessionDescription) {
//                        Log.i("WebRTC", "Offer created successfully")
//                    }
//
//                    override fun onCreateFailure(error: String) {
//                        Log.e("WebRTC", "Failed to create offer: $error")
//                    }
//                }, sdp)
//            }
//
//            override fun onCreateFailure(error: String) {
//                Log.e("WebRTC", "Failed to create offer: $error")
//            }
//
//            override fun onSetSuccess() {
//                Log.i("WebRTC", "Offer set successfully")
//            }
//
//            override fun onSetFailure(error: String) {
//                Log.e("WebRTC", "Failed to set offer: $error")
//            }
//        }, constraints)
//    }
//
//    private fun createDataChannel(
//        connection: PeerConnection,
//        remoteDeviceID: String
//    ): DataChannel? {
//        val config = DataChannel.Init().apply {
//            ordered = true
//            negotiated = true
//            id = 0
//        }
//
//        return connection.createDataChannel("data-$remoteDeviceID", config)?.also { channel ->
//            channel.registerObserver(object : DataChannel.Observer {
//                override fun onMessage(buffer: DataChannel.Buffer) {
//                    try {
//                        val data = ByteArray(buffer.data.remaining())
//                        buffer.data.get(data)
//
//                        Log.d(
//                            "RealTimeService", """
//                            📥 Received data:
//                            - Channel Label: ${channel.label()}
//                            - Data Size: ${data.size} bytes
//                            - Is Binary: ${buffer.binary}
//                        """.trimIndent()
//                        )
//
//                        handleIncomingMessage(data, remoteDeviceID)
//                    } catch (e: Exception) {
//                        Log.e("RealTimeService", "Error processing received message", e)
//                    }
//                }
//
//                override fun onBufferedAmountChange(amount: Long) {
//                    // Implementar mudança na quantidade de buffer
//                }
//
//                override fun onStateChange() {
//                    logDataChannelState(channel, remoteDeviceID)
//                }
//            })
//
//            Log.i("WebRTC", "📡 Data channel created for peer: $remoteDeviceID")
//        } ?: run {
//            Log.e("WebRTC", "Failed to create data channel for peer: $remoteDeviceID")
//            null
//        }
//    }
//
//    private fun logDataChannelState(channel: DataChannel, peerId: String) {
//        val state = when (channel.state()) {
//            DataChannel.State.CONNECTING -> "CONNECTING"
//            DataChannel.State.OPEN -> "OPEN"
//            DataChannel.State.CLOSING -> "CLOSING"
//            DataChannel.State.CLOSED -> "CLOSED"
//        }
//        Log.d("WebRTC", "Data channel state for peer $peerId: $state")
//    }
//
//    private fun setupSignaling() {
//        signalingService.listenSignal(localDeviceID) { type, data, sender, completion ->
//            when (type.uppercase()) {
//                SignalType.INITIAL.value.uppercase() -> handleInitSignal(sender, completion)
//                SignalType.OFFER.value.uppercase() -> handleOfferSignal(data, sender, completion)
//                SignalType.ANSWER.value.uppercase() -> handleAnswerSignal(data, sender, completion)
//                SignalType.CANDIDATE.value.uppercase() -> handleCandidateSignal(
//                    data,
//                    sender,
//                    completion
//                )
//
//                SignalType.BYE.value.uppercase() -> handleByeSignal(sender, completion)
//                else -> completion(false)
//            }
//        }
//    }
//
//    private fun handleInitSignal(sender: String, completion: (Boolean) -> Unit) {
//        try {
//            // Se não existe conexão, cria uma nova
//            if (!_connectedDevicesFlow.value.containsKey(sender)) {
//                val peerConnection = createPeerConnection(sender)
//                    ?: throw Exception("Failed to create peer connection")
//
//                val dataChannelConfig = DataChannel.Init().apply {
//                    ordered = true
//                    negotiated = false
//                }
//
//                val dataChannel =
//                    peerConnection.createDataChannel("messageChannel", dataChannelConfig)
//
//                _connectedDevicesFlow.update { devices ->
//                    devices.toMutableMap().apply {
//                        put(sender, DeviceConnection(sender, peerConnection, dataChannel))
//                    }
//                }
//
//                Log.d("RealTimeService", "New peer connection created for: $sender")
//            } else {
//                Log.d("RealTimeService", "Connection already exists for: $sender")
//            }
//
//            completion(true)
//        } catch (e: Exception) {
//            Log.e("RealTimeService", "Error handling init signal: ${e.message}")
//            completion(false)
//        }
//    }
//
//    private fun handleOfferSignal(sdp: String, sender: String, completion: (Boolean) -> Unit) {
//        val connection = _connectedDevicesFlow.value[sender]?.connection
//            ?: createPeerConnection(sender)
//
//        connection?.let { peerConnection ->
//            val sessionDescription = SessionDescription(
//                SessionDescription.Type.OFFER,
//                sdp
//            )
//
//            peerConnection.setRemoteDescription(object : SdpObserver {
//                override fun onCreateSuccess(p0: SessionDescription?) {}
//                override fun onSetSuccess() {
//                    createAndSetAnswer(sender)
//                    completion(true)
//                }
//
//                override fun onCreateFailure(p0: String?) {
//                    completion(false)
//                }
//
//                override fun onSetFailure(p0: String?) {
//                    completion(false)
//                }
//            }, sessionDescription)
//        } ?: completion(false)
//    }
//
//    private fun handleAnswerSignal(sdp: String, sender: String, completion: (Boolean) -> Unit) {
//        val connection = _connectedDevicesFlow.value[sender]?.connection
//
//        connection?.let { peerConnection ->
//            val sessionDescription = SessionDescription(
//                SessionDescription.Type.ANSWER,
//                sdp
//            )
//
//            peerConnection.setRemoteDescription(object : SdpObserver {
//                override fun onCreateSuccess(p0: SessionDescription?) {}
//                override fun onSetSuccess() {
//                    completion(true)
//                }
//
//                override fun onCreateFailure(p0: String?) {
//                    completion(false)
//                }
//
//                override fun onSetFailure(p0: String?) {
//                    completion(false)
//                }
//            }, sessionDescription)
//        } ?: completion(false)
//    }
//
//    private fun handleCandidateSignal(data: String, sender: String, completion: (Boolean) -> Unit) {
//        try {
//            val connection = _connectedDevicesFlow.value[sender]?.connection
//            if (connection == null) {
//                completion(false)
//                return
//            }
//
//            val candidate = IceCandidate(
//                "0",
//                0,
//                data
//            )
//
//            connection.addIceCandidate(candidate)
//            completion(true)
//        } catch (e: Exception) {
//            Log.e("RealTimeService", "Error handling candidate signal: ${e.message}", e)
//            completion(false)
//        }
//    }
//
//    private fun handleByeSignal(sender: String, completion: (Boolean) -> Unit) {
//        try {
//            // Recupera a conexão existente com o sender
//            val deviceConnection = _connectedDevicesFlow.value[sender] ?: run {
//                Log.w("RealTimeService", "Received BYE signal from non-connected device: $sender")
//                completion(false)
//                return
//            }
//
//            // Fecha o canal de dados se existir
//            deviceConnection.dataChannel?.close()
//
//            // Fecha e limpa a conexão peer
//            deviceConnection.connection.dispose()
//
//            // Remove o dispositivo do mapa de conexões
//            _connectedDevicesFlow.update { devices ->
//                devices.toMutableMap().apply { remove(sender) }
//            }
//
//            // Limpa as mensagens associadas a este dispositivo
//            _messagesFlow.update { messages ->
//                messages.toMutableMap().apply { remove(sender) }
//            }
//
//            Log.d("RealTimeService", "Successfully handled BYE signal from: $sender")
//            completion(true)
//        } catch (e: Exception) {
//            Log.e("RealTimeService", "Error handling BYE signal from $sender", e)
//            completion(false)
//        }
//    }
//
//    private fun createPeerConnection(remoteDeviceId: String): PeerConnection? {
//        val iceServers = listOf(
//            PeerConnection.IceServer("stun:stun.l.google.com:19302"),
//            PeerConnection.IceServer("stun:stun1.l.google.com:19302"),
//            PeerConnection.IceServer("stun:stun2.l.google.com:19302")
//        )
//
//        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
//            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
//            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
//            keyType = PeerConnection.KeyType.ECDSA
//        }
//
//        val constraints = MediaConstraints().apply {
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
//            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
//        }
//
//        Log.d("RealTimeService", "Creating peer connection for device: $remoteDeviceId")
//
//        val connection = peerConnectionFactory.createPeerConnection(
//            rtcConfig,
//            constraints,
//            createPeerConnectionObserver(remoteDeviceId)
//        ) ?: return null
//
//        // Primeiro registramos a conexão sem o DataChannel
//        _connectedDevicesFlow.update { devices ->
//            devices.toMutableMap().apply {
//                put(remoteDeviceId, DeviceConnection(remoteDeviceId, connection))
//            }
//        }
//
//        // Vamos criar o DataChannel somente depois que a conexão estiver estabelecida
//        // Isso será feito no observer quando o estado mudar para CONNECTED
//
//        return connection
//    }
//
////    private fun createPeerConnection(remoteDeviceId: String): PeerConnection? {
////        val iceServers = listOf(
////            PeerConnection.IceServer("stun:stun.l.google.com:19302")
////        )
////
////        val connection = peerConnectionFactory.createPeerConnection(
////            iceServers,
////            MediaConstraints(),
////            createPeerConnectionObserver(remoteDeviceId)
////        )
////
////        connection?.let { peerConnection ->
////            _connectedDevicesFlow.update { devices ->
////                devices.toMutableMap().apply {
////                    put(remoteDeviceId, DeviceConnection(remoteDeviceId, peerConnection))
////                }
////            }
////        }
////
////        return connection
////    }
//
//    private fun createPeerConnectionObserver(remoteDeviceId: String) =
//        object : PeerConnection.Observer {
//            override fun onIceCandidate(candidate: IceCandidate?) {
//                Log.d("RealTimeService", "🧊 onIceCandidate: ${candidate?.sdp}")
//                candidate?.let {
//                    // Criar um objeto com todas as informações necessárias
//                    val candidateData = """
//            {
//                "sdp": "${it.sdp}",
//                "sdpMid": "${it.sdpMid ?: "0"}",
//                "sdpMLineIndex": ${it.sdpMLineIndex}
//            }
//        """.trimIndent()
//
//                    signalingService.sendSignal(
//                        localDeviceID,
//                        SignalType.CANDIDATE.value,
//                        candidateData,
//                        remoteDeviceId
//                    )
//                }
//            }
//
//            override fun onDataChannel(dataChannel: DataChannel?) {
//                Log.d("RealTimeService", """
//        📨 Data Channel received:
//        - Label: ${dataChannel?.label()}
//        - State: ${dataChannel?.state()}
//    """.trimIndent())
//
//                dataChannel?.let { channel ->
//                    channel.registerObserver(object : DataChannel.Observer {
//                        override fun onBufferedAmountChange(amount: Long) {
//                            Log.d("RealTimeService", "Buffer amount changed: $amount")
//                        }
//
//                        override fun onStateChange() {
//                            val state = channel.state()
//                            Log.d("RealTimeService", """
//                    DataChannel state changed:
//                    - Label: ${channel.label()}
//                    - New State: $state
//                """.trimIndent())
//
//                            if (state == DataChannel.State.OPEN) {
//                                // Quando o canal abre, atualizamos o estado da conexão
//                                _connectedDevicesFlow.value[remoteDeviceId]?.let { deviceConnection ->
//                                    _connectedDevicesFlow.update { devices ->
//                                        devices.toMutableMap().apply {
//                                            put(remoteDeviceId, deviceConnection.copy(
//                                                dataChannel = channel,
//                                                connectionState = PeerConnection.IceConnectionState.CONNECTED
//                                            ))
//                                        }
//                                    }
//                                }
//                            }
//                        }
//
//                        override fun onMessage(buffer: DataChannel.Buffer?) {
//                            buffer?.let {
//                                try {
//                                    val data = ByteArray(buffer.data.remaining())
//                                    buffer.data.get(data)
//
//                                    Log.d("RealTimeService", """
//                            📥 Received data:
//                            - Channel Label: ${channel.label()}
//                            - Data Size: ${data.size} bytes
//                            - Is Binary: ${buffer.binary}
//                        """.trimIndent())
//
//                                    // Processar a mensagem recebida
//                                    CoroutineScope(Dispatchers.Main).launch {
//                                        handleIncomingMessage(data, remoteDeviceId)
//                                    }
//
//                                } catch (e: Exception) {
//                                    Log.e("RealTimeService", "Error processing received message", e)
//                                }
//                            }
//                        }
//                    })
//                }
//            }
//
//            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
//                newState?.let { state ->
//                    Log.d("RealTimeService", """
//            🧊 ICE Connection state changed for peer $remoteDeviceId:
//            - Previous State: ${_connectedDevicesFlow.value[remoteDeviceId]?.connectionState}
//            - New State: $state
//        """.trimIndent())
//
//                    _connectedDevicesFlow.value[remoteDeviceId]?.let { deviceConnection ->
//                        if (state == PeerConnection.IceConnectionState.CONNECTED && deviceConnection.dataChannel == null) {
//                            // Criar DataChannel quando a conexão estiver estabelecida
//                            try {
//                                val dataChannelInit = DataChannel.Init().apply {
//                                    ordered = true
//                                    negotiated = true
//                                    id = 0
//                                }
//
//                                val dataChannel = deviceConnection.connection.createDataChannel(
//                                    "data-$remoteDeviceId",
//                                    dataChannelInit
//                                )
//
//                                Log.d("RealTimeService", """
//                        DataChannel created:
//                        - Label: ${dataChannel.label()}
//                        - Negotiated: ${dataChannelInit.negotiated}
//                    """.trimIndent())
//
//                                _connectedDevicesFlow.update { devices ->
//                                    devices.toMutableMap().apply {
//                                        put(remoteDeviceId, deviceConnection.copy(
//                                            dataChannel = dataChannel,
//                                            connectionState = state
//                                        ))
//                                    }
//                                }
//
//                                setupDataChannelObserver(dataChannel, remoteDeviceId)
//                            } catch (e: Exception) {
//                                Log.e("RealTimeService", "Error creating DataChannel", e)
//                            }
//                        } else {
//                            // Atualizar apenas o estado
//                            _connectedDevicesFlow.update { devices ->
//                                devices.toMutableMap().apply {
//                                    put(remoteDeviceId, deviceConnection.copy(connectionState = state))
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//
//            override fun onDataChannel(dataChannel: DataChannel?) {
//                Log.d("RealTimeService", """
//        📨 Data Channel received:
//        - Label: ${dataChannel?.label()}
//        - State: ${dataChannel?.state()}
//    """.trimIndent())
//
//                dataChannel?.let { channel ->
//                    channel.registerObserver(object : DataChannel.Observer {
//                        override fun onBufferedAmountChange(amount: Long) {
//                            Log.d("RealTimeService", "Buffer amount changed: $amount")
//                        }
//
//                        override fun onStateChange() {
//                            val state = channel.state()
//                            Log.d("RealTimeService", """
//                    DataChannel state changed:
//                    - Label: ${channel.label()}
//                    - New State: $state
//                """.trimIndent())
//
//                            if (state == DataChannel.State.OPEN) {
//                                // Quando o canal abre, atualizamos o estado da conexão
//                                _connectedDevicesFlow.value[remoteDeviceId]?.let { deviceConnection ->
//                                    _connectedDevicesFlow.update { devices ->
//                                        devices.toMutableMap().apply {
//                                            put(remoteDeviceId, deviceConnection.copy(
//                                                dataChannel = channel,
//                                                connectionState = PeerConnection.IceConnectionState.CONNECTED
//                                            ))
//                                        }
//                                    }
//                                }
//                            }
//                        }
//
//                        override fun onMessage(buffer: DataChannel.Buffer?) {
//                            buffer?.let {
//                                try {
//                                    val data = ByteArray(buffer.data.remaining())
//                                    buffer.data.get(data)
//
//                                    Log.d("RealTimeService", """
//                            📥 Received data:
//                            - Channel Label: ${channel.label()}
//                            - Data Size: ${data.size} bytes
//                            - Is Binary: ${buffer.binary}
//                        """.trimIndent())
//
//                                    // Processar a mensagem recebida
//                                    CoroutineScope(Dispatchers.Main).launch {
//                                        handleIncomingMessage(data, remoteDeviceId)
//                                    }
//
//                                } catch (e: Exception) {
//                                    Log.e("RealTimeService", "Error processing received message", e)
//                                }
//                            }
//                        }
//                    })
//                }
//            }
//
//            override fun onIceConnectionReceivingChange(p0: Boolean) {
//                Log.d("RealTimeService", "🧊 ICE Connection receiving changed: $p0")
//            }
//
//            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
//                Log.d("RealTimeService", "🧊 ICE Gathering state changed to: $p0")
//            }
//
//            override fun onAddStream(p0: MediaStream?) {}
//            override fun onRemoveStream(p0: MediaStream?) {}
//            override fun onRenegotiationNeeded() {}
//            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
//                Log.d("RealTimeService", "📡 Signaling state changed to: $p0")
//            }
//        }
//
//    private fun handleIncomingMessage(data: ByteArray, from: String) {
//        try {
//            val messageString = String(data, Charsets.UTF_8)
//            val message = Message(
//                id = UUID.randomUUID().toString(),
//                content = messageString,
//                senderId = from,
//                timestamp = Date(),
//                forwardedBy = emptyList()
//            )
//
//            // Update messages flow
//            _messagesFlow.update { messages ->
//                val deviceMessages = messages[from].orEmpty().toMutableList()
//                deviceMessages.add(message)
//                messages.toMutableMap().apply {
//                    put(from, deviceMessages)
//                }
//            }
//
//            // Optional: Add notification similar to iOS if needed
//            // You can use LocalBroadcastManager or a similar mechanism
//        } catch (e: Exception) {
//            Log.e("RealTimeService", "Error handling incoming message", e)
//        }
//    }
//
//    private fun createAndSetAnswer(sender: String) {
//        val connection = _connectedDevicesFlow.value[sender]?.connection ?: return
//
//        val constraints = MediaConstraints().apply {
//            // Adiciona constraints necessários para áudio/vídeo
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
//        }
//
//        connection.createAnswer(object : SdpObserver {
//            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
//                sessionDescription?.let { sdp ->
//                    connection.setLocalDescription(object : SdpObserver {
//                        override fun onCreateSuccess(p0: SessionDescription?) {}
//                        override fun onSetSuccess() {
//                            // Envia a resposta através do serviço de sinalização
//                            signalingService.sendSignal(
//                                deviceID = localDeviceID,
//                                type = SignalType.ANSWER.value,
//                                data = sdp.description,
//                                receiver = sender
//                            )
//                        }
//
//                        override fun onCreateFailure(p0: String?) {
//                            Log.e("RealTimeService", "Error creating local description: $p0")
//                        }
//
//                        override fun onSetFailure(p0: String?) {
//                            Log.e("RealTimeService", "Error setting local description: $p0")
//                        }
//                    }, sdp)
//                }
//            }
//
//            override fun onSetSuccess() {}
//            override fun onCreateFailure(error: String?) {
//                Log.e("RealTimeService", "Error creating answer: $error")
//            }
//
//            override fun onSetFailure(error: String?) {
//                Log.e("RealTimeService", "Error setting answer: $error")
//            }
//        }, constraints)
//    }
//
//    fun dispose() {
//        try {
//            _connectedDevicesFlow.value.forEach { (_, connection) ->
//                connection.connection.dispose()
//            }
//            PeerConnectionFactory.stopInternalTracingCapture()
//            PeerConnectionFactory.shutdownInternalTracer()
//            peerConnectionFactory.dispose()
//            executor.shutdown()
//            signalingService.stopListening(localDeviceID)
//        } catch (e: Exception) {
//            println("RealTimeService Error disposing RealTimeService")
//        }
//    }
//}