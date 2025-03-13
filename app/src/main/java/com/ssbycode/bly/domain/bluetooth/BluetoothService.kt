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
import com.ssbycode.bly.domain.realTimeCommunication.RealTimeService
import com.ssbycode.bly.formattedDeviceID

class BluetoothService(
    private val context: Context,
    private var realTimeService: RealTimeService?,
    private val localDeviceID: String
) {
    // Gerenciadores e adaptadores Bluetooth
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val bluetoothAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    // UUIDs para serviço e característica GATT
    private val serviceUUID: ParcelUuid =
        ParcelUuid.fromString("12345678-1234-1234-1234-1234567890AB")
    private val characteristicUUID: UUID =
        UUID.fromString("87654321-4321-4321-4321-BA0987654321")

    // Mapas e listas para gerenciar dispositivos
    val connectedDevices: MutableMap<String, String> = mutableMapOf()
    val discoveredDevices: MutableList<BluetoothDevice> = mutableListOf()
    // Armazena o ID recebido (não o endereço) para comparação
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
        override fun onConnectionStateChange(
            device: BluetoothDevice?,
            status: Int,
            newState: Int
        ) {
            Log.d(TAG, "GATT Server - Estado da conexão: $newState para ${device?.address} (status: $status)")

            val stateString = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN"
            }

            Log.d(TAG, "GATT Server - Estado detalhado: $stateString")

            if (newState == BluetoothProfile.STATE_CONNECTED && device != null) {
                Log.d(TAG, "GATT Server - Dispositivo conectado, esperando requisições...")
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            Log.d(TAG, "GATT Server - Serviço adicionado: ${service?.uuid} com status: $status")
            service?.characteristics?.forEach { characteristic ->
                Log.d(TAG, "GATT Server - Característica: ${characteristic.uuid} com propriedades: ${characteristic.properties}")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.d(TAG, "GATT Server - Solicitação de leitura de ${device?.address} para característica ${characteristic?.uuid}")

            val response = localDeviceID.toByteArray(Charsets.UTF_8)
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
            Log.d(TAG, "GATT Server - Resposta enviada: $localDeviceID")
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
            Log.d(TAG, "GATT Server - Solicitação de escrita recebida de ${device?.address}")
            Log.d(TAG, "GATT Server - Characteristic UUID: ${characteristic?.uuid}")
            Log.d(TAG, "GATT Server - ResponseNeeded: $responseNeeded, PreparedWrite: $preparedWrite")
            Log.d(TAG, "GATT Server - Valor: ${value?.let { String(it, Charsets.UTF_8) } ?: "null"}")

            if (value != null) {
                val receivedString = String(value, Charsets.UTF_8)
                Log.d(TAG, "GATT Server - Recebeu escrita: $receivedString de ${device?.address}")

                if (receivedString != "CONNECTED") {
                    // Verifica se o ID já foi recebido
                    if (candidateDevices.contains(receivedString)) {
                        Log.d(TAG, "Dispositivo já conectado, ignorando...")
                        if (responseNeeded) {
                            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                        }
                    } else {
                        Log.i(TAG, "🔗 Novo dispositivo remoto enviou ID: $receivedString")
                        // Armazena o ID recebido
                        candidateDevices.add(receivedString)

                        if (responseNeeded) {
                            // Envia o nosso ID como resposta
                            val response = localDeviceID.toByteArray(Charsets.UTF_8)
                            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
                            Log.d(TAG, "GATT Server - Enviou ID local como resposta: $localDeviceID")
                        }

                        // Após um breve delay, envia uma notificação com o nosso ID
                        handler.postDelayed({
                            val service = gattServer.services.find { it.uuid == serviceUUID.uuid }
                            val customCharacteristic = service?.getCharacteristic(characteristicUUID)
                            if (customCharacteristic != null && device != null) {
                                customCharacteristic.value = localDeviceID.toByteArray(Charsets.UTF_8)
                                val success = gattServer.notifyCharacteristicChanged(device, customCharacteristic, false)
                                Log.d(TAG, "GATT Server - Notificação enviada: $success")
                            }
                        }, 300)
                    }
                } else {
                    // Caso receba "CONNECTED", responde e muda para modo de scan
                    if (responseNeeded) {
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    }
                    Log.d(TAG, "GATT Server - Recebeu confirmação CONNECTED, mudando para modo de scan")
                    stopAdvertising()
                    startScanning()
                }
            } else {
                Log.e(TAG, "GATT Server - Valor nulo recebido na solicitação de escrita")
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            Log.d(TAG, "GATT Server - Solicitação de leitura de descritor de ${device?.address}")
            if (descriptor?.uuid == UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) {
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.d(TAG, "GATT Server - Solicitação de escrita de descritor de ${device?.address}")
            Log.d(TAG, "GATT Server - Valor do descritor: ${value?.contentToString()}")
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            Log.d(TAG, "GATT Server - Notificação enviada para ${device?.address} com status: $status")
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            Log.d(TAG, "GATT Server - MTU alterado para ${device?.address}: $mtu")
        }
    }

    // Inicializa o GATT Server usando o contexto – certifique-se de chamar initializeGattServer() antes de iniciar advertising/scan
    private var gattServer: BluetoothGattServer =
        bluetoothManager.openGattServer(context, gattServerCallback)
            ?: throw IllegalStateException("Não foi possível abrir o GATT Server")

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
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE

        val permissions = BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE

        val characteristic = BluetoothGattCharacteristic(characteristicUUID, properties, permissions)

        // Adiciona o descritor para habilitar notificações
        val configDescriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(configDescriptor)
        service.addCharacteristic(characteristic)

        // Remove serviços já existentes para evitar conflitos
        if (gattServer.services.isNotEmpty()) {
            gattServer.services.forEach { existingService ->
                gattServer.removeService(existingService)
            }
        }

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
                        Log.d(TAG, "🔍 Característica descoberta: ${characteristic.uuid}")
                        // Habilita notificações para a característica
                        gatt.setCharacteristicNotification(characteristic, true)
                        // Envia nosso ID para o dispositivo conectado
                        val data = localDeviceID.toByteArray(Charsets.UTF_8)
                        characteristic.value = data
                        val writeSuccess = gatt.writeCharacteristic(characteristic)
                        Log.d(TAG, "📤 Enviando ID para o dispositivo conectado: $localDeviceID, sucesso: $writeSuccess")
                        connectedDevices[gatt.device.address] = localDeviceID
                    }
                }
            } else {
                Log.e(TAG, "❌ Erro ao descobrir serviços: status $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (data == null) {
                Log.e(TAG, "❌ Erro: Característica sem dados")
                return
            }
            val remoteDeviceID = String(data, Charsets.UTF_8)
            if (remoteDeviceID.isEmpty() || connectedDevices.values.contains(remoteDeviceID)) {
                stopScanning()
                startAdvertising()
                Log.e(TAG, "⚠️ Dados inválidos ou dispositivo já conectado")
                return
            }
            Log.d(TAG, "📥 Dados recebidos: $remoteDeviceID")
            stopScanning()
            startAdvertising()
            Log.i(TAG, "📣 Parando de scanear e começando a anunciar")
            connectedDevices[gatt.device.address] = remoteDeviceID
            // Envia a confirmação "CONNECTED" de volta
            characteristic.value = "CONNECTED".toByteArray(Charsets.UTF_8)
            val writeSuccess = gatt.writeCharacteristic(characteristic)
            Log.d(TAG, "GATT Client - Escrevendo 'CONNECTED', sucesso: $writeSuccess")
            handler.postDelayed({
                Log.d(TAG, "Conectando ao dispositivo remoto: $remoteDeviceID")
                realTimeService?.connectTo(remoteDeviceID)
            }, 300)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "GATT Client - Escrita realizada com sucesso em ${gatt.device.address}")
            } else {
                Log.e(TAG, "GATT Client - Erro ao escrever em ${gatt.device.address}, status: $status")
            }
        }
    }

    // --- Advertising e Escaneamento ---

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising iniciado com sucesso")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising falhou com o código: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device != null && !discoveredDevices.contains(device)) {
                discoveredDevices.add(device)
                Log.d(TAG, "Dispositivo descoberto: ${device.name ?: "Desconhecido"} - ${device.address}")
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

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(serviceUUID)
            .build()

        val scanFilters = listOf(scanFilter)
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)
        Log.d(TAG, "Escaneamento BLE iniciado")
        handler.postDelayed({ stopScanning() }, scanPeriod)
    }

    fun stopScanning() {
        if (isScanning) {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "Escaneamento BLE parado")
        }
    }

    fun startAdvertising() {
        if (!isBluetoothEnabled()) {
            Log.d(TAG, "Bluetooth não está ativo para advertising")
            return
        }

        // Verifica se o advertising é suportado
        if (bluetoothAdvertiser == null) {
            Log.e(TAG, "Dispositivo não suporta modo periférico BLE")
            return
        }

        // Para qualquer advertising já em andamento
        try {
            bluetoothAdvertiser.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar advertising existente", e)
        }

        // Define o nome do dispositivo usando o localDeviceID (utilizando a formatação se disponível)
        try {
            bluetoothAdapter?.name = localDeviceID.formattedDeviceID
            Log.d(TAG, "Nome do dispositivo definido como: ${bluetoothAdapter?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao definir nome do dispositivo", e)
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(serviceUUID)
            .build()

        try {
            bluetoothAdvertiser.startAdvertising(settings, advertiseData, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar advertising", e)
        }
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

    /**
     * Reinicializa o GATT Server para garantir um estado limpo.
     */
    private fun ensureServiceAndCharacteristicUpdated() {
        try {
            // Fecha o servidor atual
            gattServer.close()

            // Abre um novo GATT Server
            val newGattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            if (newGattServer != null) {
                gattServer = newGattServer
                // Reconfigura o serviço e característica
                initializeGattServer()
                Log.d(TAG, "GATT Server reinicializado com sucesso")
            } else {
                Log.e(TAG, "Falha ao reabrir GATT Server")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao reinicializar GATT Server", e)
        }
    }

    /**
     * Inicializa o Bluetooth, configurando o nome do dispositivo e o GATT Server.
     * Deve ser chamado antes de iniciar o advertising.
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

        // Define o nome do dispositivo utilizando a formatação, se disponível
        bluetoothAdapter?.name = localDeviceID.formattedDeviceID
        Log.d(TAG, "Nome do dispositivo definido como: ${bluetoothAdapter?.name}")

        // Reinicializa o GATT Server para um estado limpo
        ensureServiceAndCharacteristicUpdated()
    }

    companion object {
        private const val TAG = "BluetoothService"
    }
}