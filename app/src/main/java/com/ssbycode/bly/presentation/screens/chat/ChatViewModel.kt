package com.ssbycode.bly.presentation.screens.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssbycode.bly.data.realTimeCommunication.Message
import com.ssbycode.bly.domain.bluetooth.BluetoothService
import com.ssbycode.bly.domain.realTimeCommunication.RealTimeService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

class ChatViewModel(
    private val bluetoothService: BluetoothService,
    private val realTimeService: RealTimeService,
    private val localDeviceID: String = UUID.randomUUID().toString()
) : ViewModel() {

    private val _connectedDevices = MutableStateFlow<List<String>>(emptyList())
    val connectedDevices: StateFlow<List<String>> = _connectedDevices.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _newMessage = MutableStateFlow("")
    val newMessage: StateFlow<String> = _newMessage.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    val sendButtonDisabled: Boolean
        get() = _newMessage.value.isEmpty()

    init {
        setupBindings()
    }

    /**
     * Envia uma mensagem para todos os dispositivos conectados via RTC (e futuramente via Bluetooth).
     */
    fun broadcast() {
        if (!isConnected.value || newMessage.value.isEmpty()) return

        val message = Message(
            content = newMessage.value,
            senderId = localDeviceID
        )

        addMessageToChat(message)
        _newMessage.value = ""

        viewModelScope.launch {
            try {
                realTimeService.broadcast(message.toJson().toByteArray())

                // Envio via Bluetooth (comentado enquanto não implementado)
                // bluetoothService.sendMessage(message.toJson())
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Erro ao enviar mensagem: ${e.message}")
            }
        }
    }

    /**
     * Configura os fluxos de mensagens e estados dos dispositivos conectados.
     */
    private fun setupBindings() {
        viewModelScope.launch {
            realTimeService.messages.collect { newMessages ->
                handleIncomingMessages(newMessages.values.flatten())
            }
        }

        // viewModelScope.launch {
        //     bluetoothService.connectedDevices.collect { devices ->
        //         _connectedDevices.value = devices.values.toList()
        //         _isConnected.value = devices.isNotEmpty()
        //     }
        // }

        // viewModelScope.launch {
        //     bluetoothService.isScanning.collect { scanning ->
        //         _isScanning.value = scanning
        //     }
        // }
    }

    /**
     * Adiciona uma mensagem ao chat, garantindo que não haja duplicatas.
     */
    private fun addMessageToChat(message: Message) {
        _messages.update { currentMessages ->
            if (currentMessages.none { it.id == message.id }) {
                (currentMessages + message).sortedBy { it.timestamp }
            } else {
                currentMessages
            }
        }
    }

    /**
     * Filtra e adiciona mensagens recebidas ao chat.
     */
    private fun handleIncomingMessages(newMessages: List<Message>) {
        val incomingMessages = newMessages.filter { newMessage ->
            _messages.value.none { it.id == newMessage.id }
        }.map { message ->
            message.copy(isFromCurrentUser = message.senderId == localDeviceID)
        }

        if (incomingMessages.isNotEmpty()) {
            _messages.update { currentMessages ->
                (currentMessages + incomingMessages).sortedBy { it.timestamp }
            }
        }
    }

    /**
     * Define se uma mensagem deve ser considerada a última em uma sequência.
     */
    fun messagesWithSequenceInfo(): List<Pair<Message, Boolean>> {
        val messagesList = _messages.value
        return messagesList.mapIndexed { i, message ->
            val nextMessage = messagesList.getOrNull(i + 1)
            val isLastInSequence = nextMessage == null ||
                    nextMessage.senderId != message.senderId ||
                    shouldBreakSequence(message, nextMessage)

            message to isLastInSequence
        }
    }

    private fun shouldBreakSequence(current: Message, next: Message): Boolean {
        val timeThreshold = 120_000L // 120 segundos
        return next.date().time - current.date().time > timeThreshold
    }

    /**
     * Desconecta todos os dispositivos conectados.
     */
    fun disconnect() {
        Log.d("ChatViewModel", "Desconectando todos os dispositivos")

        // bluetoothService.disconnectAll() // Implementar futuramente
    }

    companion object {
        fun Factory(bluetoothService: BluetoothService, realTimeService: RealTimeService): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ChatViewModel(bluetoothService, realTimeService) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }
    }
}
