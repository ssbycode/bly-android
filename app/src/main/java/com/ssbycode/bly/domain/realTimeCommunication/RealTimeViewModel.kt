//package com.ssbycode.bly.domain.realTimeCommunication
//
//import android.util.Log
//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.viewModelScope
//import com.ssbycode.bly.data.realTimeCommunication.Message
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//import kotlinx.coroutines.flow.map
//import kotlinx.coroutines.launch
//import kotlinx.serialization.encodeToString
//import kotlinx.serialization.json.Json
//
//class RealTimeViewModel(private val service: RealTimeService) : ViewModel() {
//    private val _localDeviceID = MutableStateFlow(service.localDeviceID)
//    val localDeviceID: StateFlow<String> = _localDeviceID
//
//    private val _connectedDevices = MutableStateFlow<Set<String>>(emptySet())
//    val connectedDevices: StateFlow<Set<String>> = _connectedDevices
//
//    private val _isConnected = MutableStateFlow(false)
//    val isConnected: StateFlow<Boolean> = _isConnected
//
//    private val _messages = MutableStateFlow<List<Message>>(emptyList())
//    val messages: StateFlow<List<Message>> = _messages
//
//    init {
//        observeService()
//    }
//
//    fun connectTo(deviceID: String) {
//        service.connectTo(deviceID)
//    }
//
//    fun disconnectFrom(deviceID: String) {
//        service.disconnectFrom(deviceID)
//    }
//
//    fun disconnectAll() {
//        service.disconnectAll()
//        _messages.value = emptyList()
//    }
//
//    fun broadcast(content: String) {
//        if (_connectedDevices.value.isEmpty()) return
//
//        val message = Message(
//            content = content,
//            senderId = localDeviceID.value
//        )
//
//        try {
//            val json = Json {
//                encodeDefaults = true
//                ignoreUnknownKeys = true
//            }
//            val data = json.encodeToString(message).toByteArray(Charsets.UTF_8)
//            addMessageToHistory(message)
//            service.broadcast(data)
//        } catch (e: Exception) {
//            Log.e("RealTimeViewModel", "Failed to encode message for broadcast", e)
//            e.printStackTrace()
//        }
//    }
//
//    val messagesWithSequenceInfo: List<Pair<Message, Boolean>>
//        get() {
//            val messages = _messages.value
//            return messages.mapIndexed { index, message ->
//                val isLast = index == messages.lastIndex
//                val nextMessage = if (isLast) null else messages[index + 1]
//
//                val isLastInSequence = isLast ||
//                        nextMessage?.senderId != message.senderId ||
//                        shouldBreakSequence(current = message, next = nextMessage)
//
//                message to isLastInSequence
//            }
//        }
//
//    // Private Methods
//
//    private fun observeService() {
//        viewModelScope.launch {
//            // Observe connected devices
//            service.connectedDevicesFlow
//                .map { it.keys }
//                .collect { devices ->
//                    Log.w("RealTimeViewModel", "Connected devices: $devices")
//                    _connectedDevices.value = devices
//                }
//        }
//
//        viewModelScope.launch {
//            // Observe connection status
//            service.connectedDevicesFlow
//                .map { connections ->
//                    if (isConnected.value && connections.isEmpty()) {
//                        disconnectAll()
//                    }
//                    connections.isNotEmpty()
//                }
//                .collect { _isConnected.value = it }
//        }
//
//        viewModelScope.launch {
//            // Observe messages
//            viewModelScope.launch {
//                service.messagesFlow
//                    .collect { messages -> // messages é Map<String, List<Message>>
//                        Log.w("RealTimeViewModel", "Received messages: $messages")
//                        handleIncomingMessages(messages)
//                    }
//            }
//        }
//    }
//
//    private fun handleIncomingMessages(newMessages: Map<String, List<Message>>) {
//        val currentMessages = _messages.value
//
//        // Adicione um log para debug
//        Log.d("RealTimeViewModel", "Current messages: $currentMessages")
//        Log.d("RealTimeViewModel", "New messages map: $newMessages")
//
//        val incomingMessages = newMessages.values
//            .flatten()
//            .filter { newMessage ->
//                currentMessages.none { it.id == newMessage.id }
//            }
//            .map { message ->
//                // Não precisamos multiplicar o timestamp aqui
//                message.copy(isFromCurrentUser = message.senderId == localDeviceID.value)
//            }
//
//        Log.d("RealTimeViewModel", "Filtered incoming messages: $incomingMessages")
//
//        if (incomingMessages.isNotEmpty()) {
//            _messages.value = (currentMessages + incomingMessages)
//                .sortedBy { it.timestamp }
//
//            // Log para verificar mensagens finais
//            Log.d("RealTimeViewModel", "Final messages list: ${_messages.value}")
//        }
//    }
//
//    private fun addMessageToHistory(message: Message) {
//        val currentMessages = _messages.value
//        if (currentMessages.none { it.id == message.id }) {
//            _messages.value = (currentMessages + message)
//                .sortedBy { it.timestamp }
//        }
//    }
//
//    private fun shouldBreakSequence(current: Message, next: Message): Boolean {
//        val timeThreshold = 120_000L // 2 minutes in milliseconds
//        return next.timestamp - current.timestamp > timeThreshold
//    }
//}