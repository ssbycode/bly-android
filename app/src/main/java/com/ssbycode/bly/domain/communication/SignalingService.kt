package com.ssbycode.bly.domain.communication

/**
 * Interface que define operações de sinalização usando Firebase.
 */
interface SignalingService {
    /**
     * Envia um sinal para um dispositivo específico.
     * @param deviceID Identificador único do dispositivo de destino
     * @param type Tipo do sinal (ex.: "offer", "answer", ou "candidate")
     * @param data Dados do sinal (ex.: SDP ou candidato ICE)
     * @param receiver Identificador do receptor da mensagem
     */
    fun sendSignal(deviceID: String, type: String, data: String, receiver: String)

    /**
     * Escuta sinais enviados para um dispositivo específico.
     * @param deviceID Identificador único do dispositivo atual
     * @param onSignalReceived Callback executado quando um sinal é recebido
     */
    fun listenSignal(
        deviceID: String,
        onSignalReceived: (type: String, data: String, sender: String, completion: (Boolean) -> Unit) -> Unit
    )

    /**
     * Interrompe a escuta de sinais para um dispositivo específico.
     * @param deviceID Identificador único do dispositivo
     */
    fun stopListening(deviceID: String)
}