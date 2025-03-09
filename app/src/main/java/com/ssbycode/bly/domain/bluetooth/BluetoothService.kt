package com.ssbycode.bly.domain.bluetooth

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ssbycode.bly.formattedDeviceID

class BluetoothService(
    private val context: Context,
    private val localDeviceID: String
) {
    // Gerenciadores e adaptadores Bluetooth
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val bluetoothAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    // UUIDs para serviço e característica GATT
    private val serviceUUID: ParcelUuid = ParcelUuid.fromString("12345678-1234-1234-1234-1234567890AB")
    private val characteristicUUID: UUID = UUID.fromString("87654321-4321-4321-4321-BA0987654321")

    // Mapas e listas para gerenciar dispositivos
    val connectedDevices: MutableMap<String, String> = mutableMapOf()
    val discoveredDevices: MutableList<BluetoothDevice> = mutableListOf()
    val candidateDevices: MutableList<String> = mutableListOf()
    val disconnectedDevices: MutableList<BluetoothDevice> = mutableListOf()

    var isScanning: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val scanPeriod: Long = 10_000 // 10 segundos

    // Gerencia conexões GATT como central
    private val gattClients = mutableMapOf<String, BluetoothGatt>()

    // Lógica de reconexão
    private var reconnectionTimer: Timer? = null
    private val maxRetries = 5
    private val currentRetries = mutableMapOf<String, Int>()
    private val retryInterval: Long = 500 // em milissegundos

    // --- GATT Server (Android como periférico) ---
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            Log.d(TAG, "GATT Server - Estado da conexão: $newState para ${device?.address}")
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            Log.d(TAG, "GATT Server - Serviço adicionado: ${service?.uuid}")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            // Responde à leitura com sucesso
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            // *** Início das alterações para troca de ID via GATT Server ***
            if (value != null) {
                val receivedString = String(value, Charsets.UTF_8)
                Log.d(TAG, "GATT Server - Recebeu escrita: $receivedString de ${device?.address}")

                if (receivedString != "CONNECTED") {
                    if (candidateDevices.contains(receivedString)) {
                        Log.d(TAG, "Dispositivo já conectado, ignorando...")
                        if (responseNeeded) {
                            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                        }
                    } else {
                        Log.i(TAG, "🔗 Novo dispositivo remoto enviou ID: $receivedString")
                        device?.address?.let { candidateDevices.add(it) }
                        // Após um breve delay, notifica o dispositivo com o nosso ID
                        val response = localDeviceID.toByteArray(Charsets.UTF_8)
                        handler.postDelayed({
                            // Atualiza o valor da característica para disparar a notificação
                            val service = gattServer.services.find { it.uuid == serviceUUID.uuid }
                            val customCharacteristic = service?.getCharacteristic(characteristicUUID)
                            if (customCharacteristic != null && device != null) {
                                // Dispara uma notificação para o dispositivo (equivalente a onCharacteristicChanged)
                                gattServer.notifyCharacteristicChanged(device, customCharacteristic, false)
                            }
                        }, 300)
                        if (responseNeeded) {
                            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
                        }
                    }
                } else {
                    // Se recebeu "CONNECTED", responde com sucesso e muda o modo para scan
                    if (responseNeeded) {
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    }
                    stopAdvertising()
                    startScanning()
                }
                Log.i(TAG, "✅ Resposta de escrita enviada")
            } else {
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
            // *** Fim das alterações para troca de ID via GATT Server ***
        }
    }

    // Inicializa o GATT Server usando o contexto
    private val gattServer: BluetoothGattServer by lazy {
        bluetoothManager?.openGattServer(context, gattServerCallback)
            ?: throw IllegalStateException("Não foi possível abrir o GATT Server")
    }

    /**
     * Inicializa o GATT Server com o serviço e a característica para troca de ID.
     */
    fun initializeGattServer() {
        val service = BluetoothGattService(
            serviceUUID.uuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val properties = BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
        val permissions = BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE

        val characteristic = BluetoothGattCharacteristic(characteristicUUID, properties, permissions)

        // Adiciona o Client Characteristic Configuration Descriptor para notificações
        val configDescriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(configDescriptor)
        service.addCharacteristic(characteristic)

        val serviceAdded = gattServer.addService(service)
        Log.d(TAG, "GATT service adicionado: $serviceAdded")
    }

    // --- GATT Client (Android como central) ---
    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT Client - Conectado a ${gatt.device.name} ($deviceAddress)")
                gattClients[deviceAddress] = gatt
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT Client - Desconectado de ${gatt.device.name} ($deviceAddress)")
                connectedDevices.remove(deviceAddress)
                discoveredDevices.remove(gatt.device)
                disconnectedDevices.add(gatt.device)
                gattClients.remove(deviceAddress)
                startReconnectionAttempts(gatt.device)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.services.find { it.uuid == serviceUUID.uuid }
                if (service != null) {
                    val characteristic = service.getCharacteristic(characteristicUUID)
                    if (characteristic != null) {
                        // *** Início das alterações para troca de ID via GATT Client ***
                        Log.d(TAG, "🔍 Característica descoberta: ${characteristic.uuid}")
                        // Habilita notificações para a característica
                        gatt.setCharacteristicNotification(characteristic, true)
                        // Envia o nosso ID para o dispositivo conectado
                        val data = localDeviceID.toByteArray(Charsets.UTF_8)
                        characteristic.value = data
                        val writeSuccess = gatt.writeCharacteristic(characteristic)
                        Log.d(TAG, "📤 Enviando ID para o dispositivo conectado: $localDeviceID, sucesso: $writeSuccess")
                        // Registra o dispositivo como conectado
                        connectedDevices[gatt.device.address] = localDeviceID
                        // *** Fim das alterações para troca de ID via GATT Client ***
                    }
                }
            } else {
                Log.e(TAG, "❌ Erro ao descobrir serviços: status $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // *** Início do processamento do ID recebido (equivalente ao didUpdateValueFor) ***
            val data = characteristic.value
            if (data == null) {
                Log.e(TAG, "❌ Erro: Característica sem dados")
                return
            }
            val remoteDeviceID = String(data, Charsets.UTF_8)
            // Verifica se o ID recebido é válido e não está duplicado
            if (remoteDeviceID.isEmpty() || connectedDevices.values.contains(remoteDeviceID)) {
                stopScanning()
                startAdvertising()
                Log.e(TAG, "⚠️ Dados inválidos ou dispositivo já conectado")
                return
            }
            Log.d(TAG, "📥 Dados recebidos: $remoteDeviceID")
            // Interrompe o escaneamento e inicia o advertising
            stopScanning()
            startAdvertising()
            Log.i(TAG, "📣 Parando de scanear e começando a anunciar")
            // Atualiza a lista de dispositivos conectados
            connectedDevices[gatt.device.address] = remoteDeviceID
            // Envia a confirmação "CONNECTED" de volta
            characteristic.value = "CONNECTED".toByteArray(Charsets.UTF_8)
            val writeSuccess = gatt.writeCharacteristic(characteristic)
            Log.d(TAG, "GATT Client - Escrevendo 'CONNECTED', sucesso: $writeSuccess")
            // Após um breve delay, acione a lógica de conexão WebRTC (ou similar)
            handler.postDelayed({
                Log.d(TAG, "Conectando ao dispositivo remoto: $remoteDeviceID")
                // Exemplo: realTimeService?.connect(to = remoteDeviceID)
            }, 300)
            // *** Fim do processamento do ID recebido ***
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "GATT Client - Escrita realizada com sucesso em ${gatt.device.address}")
            } else {
                Log.e(TAG, "GATT Client - Erro ao escrever em ${gatt.device.address}, status: $status")
            }
        }
    }

    // --- Advertising e Escaneamento ---

    // Callback para Advertising (quando atuando como periférico)
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising iniciado com sucesso")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising falhou com o código: $errorCode")
        }
    }

    // Callback para escaneamento (quando atuando como central)
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device != null && !discoveredDevices.contains(device)) {
                discoveredDevices.add(device)
                Log.d(TAG, "Dispositivo descoberto: ${device.name ?: "Desconhecido"} - ${device.address}")
                // Tenta conectar como central
                device.connectGatt(context, false, gattClientCallback)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                val device = result.device
                if (!discoveredDevices.contains(device)) {
                    discoveredDevices.add(device)
                    Log.d(TAG, "Dispositivo (batch) descoberto: ${device.name ?: "Desconhecido"} - ${device.address}")
                    device.connectGatt(context, false, gattClientCallback)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Falha no escaneamento com código: $errorCode")
        }
    }

    /**
     * Inicializa o Bluetooth verificando permissões e estado, e configura o GATT Server.
     */
    fun initializeBluetooth() {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Permissões Bluetooth não concedidas")
            return
        }
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth está desativado")
            return
        }
        Log.d(TAG, "Bluetooth pronto para uso")
        // Define o nome do dispositivo como o localDeviceID
        bluetoothAdapter?.name = localDeviceID.formattedDeviceID
        initializeGattServer()
    }

    /**
     * Inicia o escaneamento de dispositivos BLE.
     */
    fun startScanning() {
        if (!isBluetoothEnabled() || !hasBluetoothPermissions()) {
            Log.e(TAG, "Bluetooth desligado ou sem permissões")
            return
        }
        if (isScanning) {
            Log.w(TAG, "Escaneamento já está em andamento")
            return
        }
        discoveredDevices.clear()
        isScanning = true
        val scanFilters = listOf<ScanFilter>() // Adicione filtros conforme necessário
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)
        Log.d(TAG, "Escaneamento BLE iniciado")
        handler.postDelayed({ stopScanning() }, scanPeriod)
    }

    /**
     * Para o escaneamento de dispositivos BLE.
     */
    fun stopScanning() {
        if (isScanning) {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "Escaneamento BLE parado")
        }
    }

    /**
     * Inicia o advertising BLE.
     */
    fun startAdvertising() {
        if (!isBluetoothEnabled()) {
            Log.d(TAG, "Bluetooth não está ativo para advertising")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(serviceUUID)
            .build()
        bluetoothAdvertiser?.startAdvertising(settings, advertiseData, advertiseCallback)
        Log.d(TAG, "Advertising iniciado com o ID: $localDeviceID")
    }

    /**
     * Para o advertising BLE.
     */
    fun stopAdvertising() {
        bluetoothAdvertiser?.stopAdvertising(advertiseCallback)
        Log.d(TAG, "Advertising parado")
    }

    /**
     * Desconecta todos os dispositivos conectados.
     */
    fun disconnectAll() {
        discoveredDevices.forEach { device ->
            gattClients[device.address]?.disconnect()
            connectedDevices.remove(device.address)
        }
        discoveredDevices.clear()
        Log.d(TAG, "Todos os dispositivos foram desconectados")
    }

    /**
     * Tenta reconectar a um dispositivo desconectado, com tentativas limitadas.
     */
    private fun startReconnectionAttempts(device: BluetoothDevice) {
        val deviceId = device.address
        currentRetries[deviceId] = 0
        reconnectionTimer?.cancel()
        reconnectionTimer = Timer()
        reconnectionTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val attempts = currentRetries[deviceId] ?: 0
                if (attempts < maxRetries) {
                    Log.d(TAG, "Tentativa ${attempts + 1} de reconexão para ${device.name ?: "Desconhecido"}")
                    currentRetries[deviceId] = attempts + 1
                    device.connectGatt(context, false, gattClientCallback)
                } else {
                    Log.d(TAG, "Máximo de tentativas atingido para ${device.name ?: "Desconhecido"}")
                    currentRetries.remove(deviceId)
                    reconnectionTimer?.cancel()
                    reconnectionTimer = null
                    if (connectedDevices.isEmpty()) {
                        stopScanning()
                        startAdvertising()
                    }
                }
            }
        }, 0, retryInterval)
    }

    /**
     * Verifica se o Bluetooth está ativo.
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * Verifica se as permissões necessárias estão concedidas.
     */
    fun hasBluetoothPermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private const val TAG = "BluetoothService"
    }
}
//class BluetoothService(
//    private val context: Context,
//    private val localDeviceID: String
//) {
//    // Gerenciadores do sistema
//    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
//    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
//    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
//    private val bluetoothAdvertiser: BluetoothLeAdvertiser? =
//        bluetoothAdapter?.bluetoothLeAdvertiser
//
//    // UUIDs para o serviço e característica do GATT
//    private val serviceUUID: ParcelUuid =
//        ParcelUuid.fromString("12345678-1234-1234-1234-1234567890ab")
//    private val characteristicUUID: UUID =
//        UUID.fromString("87654321-4321-4321-4321-BA0987654321")
//
//    // Mapeamentos e listas para gerenciamento de dispositivos
//    // No iOS: [UUID: String] para dispositivos conectados; aqui usamos endereço como chave e armazenamos o ID remoto
//    val connectedDevices: MutableMap<String, String> = mutableMapOf()
//    val discoveredDevices: MutableList<BluetoothDevice> = mutableListOf()
//    val candidateDevices: MutableList<String> = mutableListOf()
//    val disconnectedDevices: MutableList<BluetoothDevice> = mutableListOf()
//
//    // Indicador de escaneamento
//    var isScanning: Boolean = false
//        private set
//
//    // Handler para controlar o tempo de escaneamento
//    private val handler = Handler(Looper.getMainLooper())
//    private val scanPeriod: Long = 10_000 // 10 segundos
//
//    // Lógica de reconexão
//    private var reconnectionTimer: Timer? = null
//    private val maxRetries = 5
//    private val currentRetries = mutableMapOf<String, Int>()
//    private val retryInterval: Long = 500 // milissegundos
//
//    // Mapear conexões GATT (para o papel central)
//    private val gattClients = mutableMapOf<String, BluetoothGatt>()
//
//    // Inicializa o GATT Server usando o contexto
//    private val gattServer: BluetoothGattServer by lazy {
//        bluetoothManager?.openGattServer(context, gattServerCallback)
//            ?: throw IllegalStateException("Não foi possível abrir o GATT Server")
//    }
//
//    // --- GATT Server (Papel Periférico) ---
//
//    // Callback do GATT Server
//    private val gattServerCallback = object : BluetoothGattServerCallback() {
//        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
//            Log.d(TAG, "GATT Server - Estado da conexão: $newState para ${device?.address}")
//        }
//
//        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
//            Log.d(TAG, "GATT Server - Serviço adicionado: ${service?.uuid}")
//        }
//
//        override fun onCharacteristicReadRequest(
//            device: BluetoothDevice?,
//            requestId: Int,
//            offset: Int,
//            characteristic: BluetoothGattCharacteristic?
//        ) {
//            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
//        }
//
//        override fun onCharacteristicWriteRequest(
//            device: BluetoothDevice?,
//            requestId: Int,
//            characteristic: BluetoothGattCharacteristic?,
//            preparedWrite: Boolean,
//            responseNeeded: Boolean,
//            offset: Int,
//            value: ByteArray?
//        ) {
//            // --- Equivalente ao didReceiveWrite ---
//            if (value != null) {
//                val receivedString = String(value, Charsets.UTF_8)
//                Log.d(TAG, "GATT Server - Recebeu escrita: $receivedString de ${device?.address}")
//                if (receivedString != "CONNECTED") {
//                    if (candidateDevices.contains(receivedString)) {
//                        Log.d(TAG, "Dispositivo já conectado, ignorando...")
//                        Log.d(TAG, "Iniciando escaneamento de dispositivos...")
//                        if (responseNeeded) {
//                            gattServer.sendResponse(
//                                device,
//                                requestId,
//                                BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
//                                offset,
//                                null
//                            )
//                        }
//                    } else {
//                        Log.i(TAG, "🔗 Novo dispositivo remoto enviou ID: $receivedString")
//                        device?.address?.let { candidateDevices.add(it) }
//                        // Se houver uma característica customizada disponível para notificar, utilize-a
//                        val service = gattServer.services.find { it.uuid == serviceUUID.uuid }
//                        val customCharacteristic = service?.getCharacteristic(characteristicUUID)
//                        val response = localDeviceID.toByteArray(Charsets.UTF_8)
//                        handler.postDelayed({
//                            if (customCharacteristic != null && device != null) {
//                                // Notifica o dispositivo conectado, acionando onCharacteristicChanged no central
//                                gattServer.notifyCharacteristicChanged(
//                                    device,
//                                    customCharacteristic,
//                                    false
//                                )
//                            }
//                        }, 300)
//                        if (responseNeeded) {
//                            gattServer.sendResponse(
//                                device,
//                                requestId,
//                                BluetoothGatt.GATT_SUCCESS,
//                                offset,
//                                response
//                            )
//                        }
//                    }
//                } else {
//                    if (responseNeeded) {
//                        gattServer.sendResponse(
//                            device,
//                            requestId,
//                            BluetoothGatt.GATT_SUCCESS,
//                            offset,
//                            null
//                        )
//                    }
//                    stopAdvertising()
//                    startScanning()
//                }
//                Log.i(TAG, "✅ Resposta de escrita enviada")
//            } else {
//                if (responseNeeded) {
//                    gattServer.sendResponse(
//                        device,
//                        requestId,
//                        BluetoothGatt.GATT_FAILURE,
//                        offset,
//                        null
//                    )
//                }
//            }
//        }
//    }
//
//    // --- GATT Client (Papel Central) ---
//
//    // Callback para conexões como central (quando o Android se conecta a outro dispositivo)
//    private val gattClientCallback = object : BluetoothGattCallback() {
//        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
//            val deviceAddress = gatt.device.address
//            if (newState == BluetoothProfile.STATE_CONNECTED) {
//                Log.d(TAG, "GATT Client - Conectado a ${gatt.device.name} ($deviceAddress)")
//                gattClients[deviceAddress] = gatt
//                gatt.discoverServices()
//            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
//                Log.d(TAG, "GATT Client - Desconectado de ${gatt.device.name} ($deviceAddress)")
//                connectedDevices.remove(deviceAddress)
//                discoveredDevices.remove(gatt.device)
//                disconnectedDevices.add(gatt.device)
//                gattClients.remove(deviceAddress)
//                startReconnectionAttempts(gatt.device)
//            }
//        }
//
//        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
//            if (status == BluetoothGatt.GATT_SUCCESS) {
//                val service = gatt.services.find { it.uuid == serviceUUID.uuid }
//                if (service != null) {
//                    val characteristic = service.getCharacteristic(characteristicUUID)
//                    if (characteristic != null) {
//                        // --- Equivalente ao didDiscoverCharacteristicsFor ---
//                        Log.d(TAG, "🔍 Característica descoberta: ${characteristic.uuid}")
//                        // Habilita notificações para essa característica
//                        gatt.setCharacteristicNotification(characteristic, true)
//                        // Envia o próprio ID para o dispositivo conectado
//                        val data = localDeviceID.toByteArray(Charsets.UTF_8)
//                        characteristic.value = data
//                        val writeSuccess = gatt.writeCharacteristic(characteristic)
//                        Log.d(
//                            TAG,
//                            "📤 Enviando ID para o dispositivo conectado: $localDeviceID, sucesso: $writeSuccess"
//                        )
//                        // Registra o dispositivo como conectado
//                        connectedDevices[gatt.device.address] = localDeviceID
//                    }
//                }
//            } else {
//                Log.e(TAG, "❌ Erro ao descobrir serviços: status $status")
//            }
//        }
//
//        override fun onCharacteristicChanged(
//            gatt: BluetoothGatt,
//            characteristic: BluetoothGattCharacteristic
//        ) {
//            // --- Equivalente ao didUpdateValueFor ---
//            val data = characteristic.value
//            if (data == null) {
//                Log.e(TAG, "❌ Erro: Característica sem dados")
//                return
//            }
//            val remoteDeviceID = String(data, Charsets.UTF_8)
//            // Verifica se o ID recebido é válido e se já não está conectado
//            if (remoteDeviceID.isEmpty() || connectedDevices.values.contains(remoteDeviceID)) {
//                stopScanning()
//                startAdvertising()
//                Log.e(TAG, "⚠️ Dados inválidos ou dispositivo já conectado")
//                return
//            }
//            Log.d(TAG, "📥 Dados recebidos: $remoteDeviceID")
//            stopScanning()
//            startAdvertising()
//            Log.i(TAG, "📣 Parando de scanear e começando a anunciar")
//            // Atualiza a lista de dispositivos conectados
//            connectedDevices[gatt.device.address] = remoteDeviceID
//            // Envia a confirmação "CONNECTED" de volta
//            characteristic.value = "CONNECTED".toByteArray(Charsets.UTF_8)
//            val writeSuccess = gatt.writeCharacteristic(characteristic)
//            Log.d(TAG, "GATT Client - Escrevendo 'CONNECTED', sucesso: $writeSuccess")
//            // Após um breve delay, notifica a camada de serviço em tempo real (se aplicável)
//            handler.postDelayed({
//                Log.d(TAG, "Conectando ao dispositivo remoto: $remoteDeviceID")
//                // Exemplo: realTimeService?.connect(to = remoteDeviceID)
//            }, 300)
//        }
//
//        override fun onCharacteristicWrite(
//            gatt: BluetoothGatt,
//            characteristic: BluetoothGattCharacteristic,
//            status: Int
//        ) {
//            if (status == BluetoothGatt.GATT_SUCCESS) {
//                Log.d(TAG, "GATT Client - Escrita realizada com sucesso em ${gatt.device.address}")
//            } else {
//                Log.e(
//                    TAG,
//                    "GATT Client - Erro ao escrever em ${gatt.device.address}, status: $status"
//                )
//            }
//        }
//    }
//
//    // --- Advertising e Escaneamento ---
//
//    // Callback para Advertising (periférico)
//    private val advertiseCallback = object : AdvertiseCallback() {
//        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
//            Log.d(TAG, "Advertising iniciado com sucesso")
//        }
//
//        override fun onStartFailure(errorCode: Int) {
//            Log.e(TAG, "Advertising falhou com o código: $errorCode")
//        }
//    }
//
//    // Callback para escaneamento (central)
//    private val scanCallback = object : ScanCallback() {
//        override fun onScanResult(callbackType: Int, result: ScanResult) {
//            val device = result.device
//            if (device != null && !discoveredDevices.contains(device)) {
//                discoveredDevices.add(device)
//                Log.d(
//                    TAG,
//                    "Dispositivo descoberto: ${device.name ?: "Desconhecido"} - ${device.address}"
//                )
//                // Tenta conectar ao dispositivo como central
//                device.connectGatt(context, false, gattClientCallback)
//            }
//        }
//
//        override fun onBatchScanResults(results: MutableList<ScanResult>) {
//            results.forEach { result ->
//                val device = result.device
//                if (!discoveredDevices.contains(device)) {
//                    discoveredDevices.add(device)
//                    Log.d(
//                        TAG,
//                        "Dispositivo (batch) descoberto: ${device.name ?: "Desconhecido"} - ${device.address}"
//                    )
//                    device.connectGatt(context, false, gattClientCallback)
//                }
//            }
//        }
//
//        override fun onScanFailed(errorCode: Int) {
//            Log.e(TAG, "Falha no escaneamento com código: $errorCode")
//        }
//    }
//
//    /**
//     * Inicializa o GATT Server adicionando o serviço e a característica.
//     */
//    fun initializeGattServer() {
//        val service = BluetoothGattService(
//            serviceUUID.uuid,
//            BluetoothGattService.SERVICE_TYPE_PRIMARY
//        )
//
//        val properties = BluetoothGattCharacteristic.PROPERTY_READ or
//                BluetoothGattCharacteristic.PROPERTY_WRITE or
//                BluetoothGattCharacteristic.PROPERTY_NOTIFY
//        val permissions = BluetoothGattCharacteristic.PERMISSION_READ or
//                BluetoothGattCharacteristic.PERMISSION_WRITE
//
//        val characteristic =
//            BluetoothGattCharacteristic(characteristicUUID, properties, permissions)
//
//        // Descriptor para habilitar notificações
//        val configDescriptor = BluetoothGattDescriptor(
//            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
//            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
//        )
//        characteristic.addDescriptor(configDescriptor)
//        service.addCharacteristic(characteristic)
//
//        val serviceAdded = gattServer.addService(service)
//        Log.d(TAG, "GATT service adicionado: $serviceAdded")
//    }
//
//    /**
//     * Inicializa o Bluetooth: verifica permissões, estado e configura o GATT Server.
//     */
//    fun initializeBluetooth() {
//        if (!hasBluetoothPermissions()) {
//            Log.e(TAG, "Permissões Bluetooth não concedidas")
//            return
//        }
//        if (!isBluetoothEnabled()) {
//            Log.e(TAG, "Bluetooth está desativado")
//            return
//        }
//        Log.d(TAG, "Bluetooth pronto para uso")
//        // Configura o nome do dispositivo para nosso ID
//        bluetoothAdapter?.name = localDeviceID.formattedDeviceID
//        initializeGattServer()
//    }
//
//    /**
//     * Inicia o escaneamento de dispositivos BLE.
//     */
//    fun startScanning() {
//        if (!isBluetoothEnabled() || !hasBluetoothPermissions()) {
//            Log.e(TAG, "Bluetooth desligado ou sem permissões")
//            return
//        }
//        if (isScanning) {
//            Log.w(TAG, "Escaneamento já está em andamento")
//            return
//        }
//        discoveredDevices.clear()
//        isScanning = true
//
//        val scanFilters = listOf<ScanFilter>() // Adicione filtros se necessário
//        val scanSettings = ScanSettings.Builder()
//            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
//            .build()
//        bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)
//        Log.d(TAG, "Escaneamento BLE iniciado")
//        handler.postDelayed({ stopScanning() }, scanPeriod)
//    }
//
//    /**
//     * Para o escaneamento de dispositivos BLE.
//     */
//    fun stopScanning() {
//        if (isScanning) {
//            bluetoothLeScanner?.stopScan(scanCallback)
//            isScanning = false
//            Log.d(TAG, "Escaneamento BLE parado")
//        }
//    }
//
//    /**
//     * Inicia o advertising BLE.
//     */
//    fun startAdvertising() {
//        if (!isBluetoothEnabled()) {
//            Log.d(TAG, "Bluetooth não está ativo para advertising")
//            return
//        }
//        val settings = AdvertiseSettings.Builder()
//            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
//            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
//            .setConnectable(true)
//            .build()
//        val advertiseData = AdvertiseData.Builder()
//            .setIncludeDeviceName(true)
//            .addServiceUuid(serviceUUID)
//            .build()
//        bluetoothAdvertiser?.startAdvertising(settings, advertiseData, advertiseCallback)
//        Log.d(TAG, "Advertising iniciado com o ID: $localDeviceID")
//    }
//
//    /**
//     * Para o advertising BLE.
//     */
//    fun stopAdvertising() {
//        bluetoothAdvertiser?.stopAdvertising(advertiseCallback)
//        Log.d(TAG, "Advertising parado")
//    }
//
//    /**
//     * Desconecta todos os dispositivos conectados.
//     */
//    fun disconnectAll() {
//        discoveredDevices.forEach { device ->
//            gattClients[device.address]?.disconnect()
//            connectedDevices.remove(device.address)
//        }
//        discoveredDevices.clear()
//        Log.d(TAG, "Todos os dispositivos foram desconectados")
//    }
//
//    /**
//     * Tenta reconectar a um dispositivo desconectado, com tentativas limitadas.
//     */
//    private fun startReconnectionAttempts(device: BluetoothDevice) {
//        val deviceId = device.address
//        currentRetries[deviceId] = 0
//        reconnectionTimer?.cancel()
//        reconnectionTimer = Timer()
//        reconnectionTimer?.scheduleAtFixedRate(object : TimerTask() {
//            override fun run() {
//                val attempts = currentRetries[deviceId] ?: 0
//                if (attempts < maxRetries) {
//                    Log.d(
//                        TAG,
//                        "Tentativa ${attempts + 1} de reconexão para ${device.name ?: "Desconhecido"}"
//                    )
//                    currentRetries[deviceId] = attempts + 1
//                    device.connectGatt(context, false, gattClientCallback)
//                } else {
//                    Log.d(
//                        TAG,
//                        "Máximo de tentativas atingido para ${device.name ?: "Desconhecido"}"
//                    )
//                    currentRetries.remove(deviceId)
//                    reconnectionTimer?.cancel()
//                    reconnectionTimer = null
//                    if (connectedDevices.isEmpty()) {
//                        stopScanning()
//                        startAdvertising()
//                    }
//                }
//            }
//        }, 0, retryInterval)
//    }
//
//    /**
//     * Verifica se o Bluetooth está ativo.
//     */
//    fun isBluetoothEnabled(): Boolean {
//        return bluetoothAdapter?.isEnabled == true
//    }
//
//    /**
//     * Verifica se as permissões necessárias estão concedidas.
//     */
//    fun hasBluetoothPermissions(): Boolean {
//        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            arrayOf(
//                Manifest.permission.BLUETOOTH_SCAN,
//                Manifest.permission.BLUETOOTH_CONNECT,
//                Manifest.permission.BLUETOOTH_ADVERTISE
//            )
//        } else {
//            arrayOf(
//                Manifest.permission.BLUETOOTH,
//                Manifest.permission.BLUETOOTH_ADMIN,
//                Manifest.permission.ACCESS_FINE_LOCATION
//            )
//        }
//        return requiredPermissions.all {
//            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
//        }
//    }
//
//    companion object {
//        private const val TAG = "BluetoothService"
//    }
//}


//class BluetoothService(
//    private val context: Context,
//    private val realTimeService: RealTimeService,
//    private val bluetoothAdapter: BluetoothAdapter,
//) {
////    private val bluetoothManager: BluetoothManager =
////        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
//
//    private val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
//
//    private val serviceUUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890AB")
//    private val characteristicUUID: UUID = UUID.fromString("87654321-4321-4321-4321-BA0987654321")
//
//    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()
//    private val discoveredDevices = mutableListOf<BluetoothDevice>()
//
//    private var gattServer: BluetoothGattServer? = null
//    private var gattCharacteristic: BluetoothGattCharacteristic? = null
//
//    private var isScanning = false
//
//    private val handler = Handler(Looper.getMainLooper())
//    private val scanPeriod: Long = 10000 // 10 segundos para escaneamento
//
//    private val advertiseCallback = object : AdvertiseCallback() {
//        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
//            super.onStartSuccess(settingsInEffect)
//            Log.d(TAG, "✅ Advertising iniciado com sucesso")
//        }
//
//        override fun onStartFailure(errorCode: Int) {
//            super.onStartFailure(errorCode)
//            Log.e(TAG, "❌ Falha ao iniciar advertising: $errorCode")
//        }
//    }
//
//    init {
//        setupManagers()
//    }
//
//    private fun setupManagers() {
//        if (ActivityCompat.checkSelfPermission(
//                context, Manifest.permission.BLUETOOTH_CONNECT
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            Log.e(TAG, "❌ Permissão BLUETOOTH_CONNECT não concedida!")
//            return
//        }
//
//        if (context == null) {
//            Log.e(TAG, "❌ Erro: Contexto é null! Abortando inicialização do Bluetooth.")
//            return
//        }
//
//        if (!hasBluetoothConnectPermission()) {
//            Log.e(TAG, "❌ Permissão BLUETOOTH_CONNECT não concedida. Não é possível iniciar o GATT Server.")
//            return
//        }
//
//        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
//        if (bluetoothManager == null) {
//            Log.e(TAG, "❌ Erro ao obter BluetoothManager!")
//            return
//        }
//
//        if (gattCallback != null) {
//            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
//            if (gattServer == null) {
//                Log.e(TAG, "❌ Erro ao abrir o GATT Server!")
//                return
//            }
//        }
//
//        setupGattServer()
//        Log.d(TAG, "✅ BluetoothService inicializado com sucesso.")
//
//        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
//        setupGattServer()
//    }
//
//    /** Inicia o escaneamento de dispositivos */
//    @SuppressLint("MissingPermission")
//    fun startScanning() {
//        if (bluetoothAdapter?.isEnabled == true && bluetoothLeScanner != null && !isScanning) {
//            isScanning = true
//            handler.postDelayed({
//                stopScanning()
//            }, scanPeriod)
//
//            val scanFilters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUUID)).build())
//            val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
//
//            bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)
//            Log.d(TAG, "🔍 Iniciando escaneamento...")
//        } else {
//            Log.d(TAG, "⚠️ Bluetooth não está ativo ou já está escaneando.")
//        }
//    }
//
//    /** Para o escaneamento */
//    @SuppressLint("MissingPermission")
//    fun stopScanning() {
//        if (isScanning) {
//            bluetoothLeScanner?.stopScan(scanCallback)
//            isScanning = false
//            Log.d(TAG, "🛑 Escaneamento parado")
//        }
//    }
//
//    /** Inicia o advertising (disponibiliza o dispositivo para conexões) */
//    @SuppressLint("MissingPermission")
//    fun startAdvertising() {
//        Log.d(TAG, "📢 Iniciando Advertising...")
//        val settings = AdvertiseSettings.Builder()
//            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
//            .setConnectable(true)
//            .setTimeout(0)
//            .build()
//
//        val data = AdvertiseData.Builder()
//            .setIncludeDeviceName(true)
//            .addServiceUuid(ParcelUuid(serviceUUID))
//            .build()
//
//        bluetoothAdapter?.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
//    }
//
//    /** Configura o GATT Server */
//    private fun setupGattServer() {
//        val service = BluetoothGattService(serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
//
//        gattCharacteristic = BluetoothGattCharacteristic(
//            characteristicUUID,
//            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
//            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
//        )
//
//        service.addCharacteristic(gattCharacteristic)
//
//        // Verifica permissão antes de adicionar o serviço
//        if (!hasBluetoothConnectPermission()) {
//            Log.e(TAG, "❌ Permissão BLUETOOTH_CONNECT não concedida! Não é possível adicionar o serviço GATT.")
//            return
//        }
//
//        if (ActivityCompat.checkSelfPermission(
//                context,
//                Manifest.permission.BLUETOOTH_CONNECT
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            // TODO: Consider calling
//            //    ActivityCompat#requestPermissions
//            // here to request the missing permissions, and then overriding
//            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
//            //                                          int[] grantResults)
//            // to handle the case where the user grants the permission. See the documentation
//            // for ActivityCompat#requestPermissions for more details.
//            return
//        }
//        gattServer?.addService(service)
//        Log.d(TAG, "✅ Serviço GATT adicionado com sucesso!")
//    }
//
//    private fun hasBluetoothConnectPermission(): Boolean {
//        return ActivityCompat.checkSelfPermission(
//            context, Manifest.permission.BLUETOOTH_CONNECT
//        ) == PackageManager.PERMISSION_GRANTED
//    }
//
//    /** Callbacks do Bluetooth LE Scanner */
//    private val scanCallback = object : ScanCallback() {
//        @SuppressLint("MissingPermission")
//        fun onScanResult(callbackType: Int, result: ScanResult?) {
////            result?.device?.let { device ->
////                Log.d(TAG, "📡 Dispositivo encontrado: ${device.name ?: "Desconhecido"}")
////                if (!discoveredDevices.contains(device)) {
////                    discoveredDevices.add(device)
////                    connectToDevice(device)
////                }
//        }
//    }
//
//    /** Conectar a um dispositivo Bluetooth */
//    @SuppressLint("MissingPermission")
//    private fun connectToDevice(device: BluetoothDevice) {
//        Log.d(TAG, "🔗 Tentando conectar ao dispositivo ${device.name ?: "Desconhecido"}")
//        device.connectGatt(context, false, gattCallback)
//    }
//
//    /** Callbacks do GATT Server */
//    private val gattServerCallback = object : BluetoothGattServerCallback() {
//
//        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
//            if (ActivityCompat.checkSelfPermission(
//                    context, Manifest.permission.BLUETOOTH_CONNECT
//                ) != PackageManager.PERMISSION_GRANTED
//            ) {
//                Log.e(TAG, "❌ Permissão BLUETOOTH_CONNECT não concedida!")
//                return
//            }
//
//            if (newState == BluetoothProfile.STATE_CONNECTED && device != null) {
//                Log.d(TAG, "✅ Dispositivo conectado: ${device.name ?: "Desconhecido"}")
//                connectedDevices[device.address] = device
//            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && device != null) {
//                Log.d(TAG, "❌ Dispositivo desconectado: ${device.name ?: "Desconhecido"}")
//                connectedDevices.remove(device.address)
//                startScanning()
//            }
//        }
//
//        override fun onCharacteristicWriteRequest(
//            device: BluetoothDevice?,
//            requestId: Int,
//            characteristic: BluetoothGattCharacteristic?,
//            preparedWrite: Boolean,
//            responseNeeded: Boolean,
//            offset: Int, // ⚠️ Adicionei esse parâmetro que estava faltando
//            value: ByteArray?
//        ) {
//            val receivedData = value?.toString(Charsets.UTF_8) ?: "N/A"
//            Log.d(TAG, "📥 Dados recebidos: $receivedData")
//
//            if (ActivityCompat.checkSelfPermission(
//                    context, Manifest.permission.BLUETOOTH_CONNECT
//                ) != PackageManager.PERMISSION_GRANTED
//            ) {
//                Log.e(TAG, "❌ Permissão BLUETOOTH_CONNECT não concedida!")
//                return
//            }
//
//            if (responseNeeded && device != null) {
//                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
//                Log.d(TAG, "✅ Resposta enviada")
//            }
//        }
//    }
//
//
//    /** Callbacks do GATT Client */
//    private val gattCallback = object : BluetoothGattCallback() {
//        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
//            if (ActivityCompat.checkSelfPermission(
//                    context, Manifest.permission.BLUETOOTH_CONNECT
//                ) != PackageManager.PERMISSION_GRANTED
//            ) {
//                Log.e(TAG, "❌ Permissão BLUETOOTH_CONNECT não concedida!")
//                return
//            }
//
//            if (newState == BluetoothProfile.STATE_CONNECTED) {
//                Log.d(TAG, "✅ Conectado ao dispositivo ${gatt?.device?.name ?: "Desconhecido"}")
//                gatt?.discoverServices()
//            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
//                Log.d(TAG, "❌ Desconectado do dispositivo ${gatt?.device?.name ?: "Desconhecido"}")
//                gatt?.close()
//            }
//        }
//
//        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
//            gatt?.services?.forEach { service ->
//                Log.d(TAG, "🔍 Serviço descoberto: ${service.uuid}")
//                if (service.uuid == serviceUUID) {
//                    gattCharacteristic = service.getCharacteristic(characteristicUUID)
//                }
//            }
//        }
//    }
//
//    companion object {
//        private const val TAG = "BluetoothService"
//    }
//}


//class BluetoothService : Service() {
//
//    // Binder para vinculação com a Activity
//    private val binder = LocalBinder()
//
//    // Gerenciador e adaptador de Bluetooth
//    private lateinit var bluetoothManager: BluetoothManager
//    private var bluetoothAdapter: BluetoothAdapter? = null
//    private var bluetoothLeScanner: BluetoothLeScanner? = null
//
//    // Estado do scanner e lista de dispositivos encontrados
//    private var isScanning = false
//    private val scanResults = mutableListOf<BluetoothDevice>()
//
//    // Instância do GATT para conexões
//    private var bluetoothGatt: BluetoothGatt? = null
//
//    inner class LocalBinder : Binder() {
//        fun getService(): BluetoothService = this@BluetoothService
//    }
//
//    override fun onBind(intent: Intent?): IBinder = binder
//
//    override fun onCreate() {
//        super.onCreate()
//        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
//        bluetoothAdapter = bluetoothManager.adapter
//        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
//    }
//
//    /**
//     * Inicia o scan de dispositivos BLE.
//     * Opcionalmente, filtros podem ser adicionados na lista de ScanFilter.
//     */
//    fun startScan() {
//        if (!isScanning) {
//            scanResults.clear()
//            val scanFilters = listOf<ScanFilter>() // Adicione filtros, se necessário
//            val scanSettings = ScanSettings.Builder()
//                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
//                .build()
//            bluetoothLeScanner?.startScan(scanFilters, scanSettings, leScanCallback)
//            isScanning = true
//            Log.d("BluetoothService", "Iniciando scan BLE")
//        }
//    }
//
//    /**
//     * Interrompe o scan de dispositivos BLE.
//     */
//    fun stopScan() {
//        if (isScanning) {
//            bluetoothLeScanner?.stopScan(leScanCallback)
//            isScanning = false
//            Log.d("BluetoothService", "Scan BLE interrompido")
//        }
//    }
//
//    /**
//     * Callback para resultados de scan.
//     */
//    private val leScanCallback = object : ScanCallback() {
//        override fun onScanResult(callbackType: Int, result: ScanResult) {
//            result.device
//
//            result.device.let { device -> // Certifique-se de que `device` é acessado corretamente
//                if (!scanResults.contains(device)) {
//                    scanResults.add(device)
//                    sendBroadcast(Intent(ACTION_DEVICE_FOUND).apply {
//                        putExtra(EXTRA_DEVICE, device) // Possível erro aqui, vamos corrigir no próximo passo
//                    })
//                    Log.d("BluetoothService", "Dispositivo encontrado: ${device.address}")
//                }
//            }
//        }
//    }
//
//
//    /**
//     * Conecta a um dispositivo BLE usando o GATT.
//     * Lembre-se: dispositivos iOS só se conectam via BLE.
//     */
//    fun connectToDevice(device: BluetoothDevice) {
//        bluetoothGatt = device.connectGatt(this, false, gattCallback)
//        Log.d("BluetoothService", "Tentando conectar ao dispositivo: ${device.address}")
//    }
//
//    /**
//     * Callback para eventos do Bluetooth GATT.
//     */
//    private val gattCallback = object : BluetoothGattCallback() {
//        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
//            when (newState) {
//                BluetoothProfile.STATE_CONNECTED -> {
//                    Log.i("BluetoothService", "Conectado ao GATT. Descobrindo serviços...")
//                    gatt.discoverServices()
//                    sendBroadcast(Intent(ACTION_CONNECTED))
//                }
//                BluetoothProfile.STATE_DISCONNECTED -> {
//                    Log.i("BluetoothService", "Desconectado do GATT")
//                    sendBroadcast(Intent(ACTION_DISCONNECTED))
//                }
//                else -> {
//                    Log.w("BluetoothService", "Estado inesperado: $newState")
//                }
//            }
//        }
//
//        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
//            if (status == BluetoothGatt.GATT_SUCCESS) {
//                Log.d("BluetoothService", "Serviços descobertos com sucesso")
//                sendBroadcast(Intent(ACTION_SERVICES_DISCOVERED))
//            } else {
//                Log.w("BluetoothService", "Falha ao descobrir serviços. Status: $status")
//            }
//        }
//
//        // Outros callbacks (leitura, escrita, notificações) podem ser implementados aqui
//        override fun onCharacteristicRead(
//            gatt: BluetoothGatt,
//            characteristic: BluetoothGattCharacteristic,
//            status: Int
//        ) {
//            // Trate a leitura da característica conforme necessário
//        }
//
//        override fun onCharacteristicChanged(
//            gatt: BluetoothGatt,
//            characteristic: BluetoothGattCharacteristic
//        ) {
//            // Trate a notificação de mudança de característica
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        bluetoothGatt?.close()
//        bluetoothGatt = null
//        Log.d("BluetoothService", "Serviço destruído e conexão GATT fechada")
//    }
//
//    companion object {
//        const val ACTION_DEVICE_FOUND = "com.seuapp.ACTION_DEVICE_FOUND"
//        const val ACTION_CONNECTED = "com.seuapp.ACTION_CONNECTED"
//        const val ACTION_DISCONNECTED = "com.seuapp.ACTION_DISCONNECTED"
//        const val ACTION_SERVICES_DISCOVERED = "com.seuapp.ACTION_SERVICES_DISCOVERED"
//        const val EXTRA_DEVICE = "com.seuapp.EXTRA_DEVICE"
//    }
//}