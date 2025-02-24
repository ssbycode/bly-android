package com.ssbycode.bly.domain.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
//import android.content.Context
import android.content.pm.PackageManager
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
//import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.*
import com.ssbycode.bly.domain.realTimeCommunication.RealTimeService

import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

val String.formattedDeviceID: String
    get() = this.split("-").firstOrNull() ?: this


class BluetoothService(
    private val context: Context,
    private val localDeviceID: String
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val bluetoothAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    // UUIDs para o serviço GATT e característica
    private val serviceUUID: ParcelUuid = ParcelUuid.fromString("12345678-1234-1234-1234-1234567890ab")
    private val characteristicUUID: UUID = UUID.fromString("87654321-4321-4321-4321-BA0987654321")

    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val scanPeriod: Long = 10000 // 10 segundos para escaneamento

    // Callback do GATT server
    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: $newState")
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            Log.d(TAG, "Service added: ${service?.uuid}")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            // Exemplo: responde à leitura com sucesso (ajuste conforme sua lógica)
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
            // Exemplo: processa a escrita e responde, se necessário
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    // Inicializa o GATT server usando o contexto
    private val gattServer: BluetoothGattServer by lazy {
        bluetoothManager?.openGattServer(context, gattCallback)
            ?: throw IllegalStateException("Não foi possível abrir o GATT Server")
    }

    /**
     * Inicializa o GATT server, adicionando o serviço e a característica com seus respectivos
     * UUIDs. Aqui também adicionamos um descriptor para suportar notificações.
     */
    fun initializeGattServer() {
        val service = BluetoothGattService(serviceUUID.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Propriedades e permissões para a característica
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
        Log.d(TAG, "Serviço GATT adicionado: $serviceAdded")
    }

    /**
     * Callback para o anúncio BLE.
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "✅ Advertising iniciado com sucesso")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "❌ Falha ao iniciar advertising: $errorCode")
        }
    }

    /**
     * Callback para o escaneamento BLE.
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice? = result.device
            if (device != null && !discoveredDevices.contains(device)) {
                discoveredDevices.add(device)
                Log.d(
                    TAG,
                    "📡 Dispositivo encontrado: ${device.name ?: "Desconhecido"} - ${device.address}"
                )
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                val device = result.device
                if (!discoveredDevices.contains(device)) {
                    discoveredDevices.add(device)
                    Log.d(
                        TAG,
                        "📡 (Batch) Dispositivo encontrado: ${device.name ?: "Desconhecido"} - ${device.address}"
                    )
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMessage = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "⚠️ O escaneamento já foi iniciado anteriormente."
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "❌ Falha ao registrar a aplicação para escanear."
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "❌ Erro interno desconhecido ao iniciar o escaneamento."
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "❌ O dispositivo não suporta essa funcionalidade de escaneamento."
                ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "⚠️ Poucos recursos disponíveis para escanear."
                ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "⚠️ O escaneamento está sendo iniciado com muita frequência. Aguarde um pouco."
                else -> "❌ Erro desconhecido ao tentar escanear."
            }
            Log.e(TAG, errorMessage)
        }
    }

    /**
     * Verifica se o Bluetooth está disponível e ativado.
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * Verifica se todas as permissões Bluetooth necessárias estão concedidas.
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
     * Inicializa o Bluetooth se as permissões forem concedidas e o Bluetooth estiver ativado.
     * Aqui também inicializamos o GATT server com o serviço e característica.
     */
    fun initializeBluetooth() {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Permissões Bluetooth não concedidas.")
            return
        }

        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth está desativado.")
            return
        }

        Log.d(TAG, "Bluetooth está pronto para uso.")
        // Configura o nome do dispositivo, se necessário
        bluetoothAdapter?.name = localDeviceID.formattedDeviceID//Build.MANUFACTURER

        // Inicializa o GATT server e adiciona o serviço
        initializeGattServer()
    }

    /**
     * Inicia o escaneamento de dispositivos BLE.
     */
    fun startScanning() {
        if (!isBluetoothEnabled() || !hasBluetoothPermissions()) {
            Log.e(TAG, "❌ Bluetooth desligado ou sem permissões")
            return
        }

        if (isScanning) {
            Log.w(TAG, "⚠️ O escaneamento já está em andamento.")
            return
        }

        discoveredDevices.clear()
        isScanning = true

        val scanFilters = listOf<ScanFilter>() // Pode adicionar filtros específicos
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)
        Log.d(TAG, "🔍 Iniciando escaneamento BLE...")

        // Para automaticamente após 'scanPeriod' milissegundos
        handler.postDelayed({ stopScanning() }, scanPeriod)
    }

    fun stopScanning() {
        if (isScanning) {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "🛑 Escaneamento parado")
        }
    }

    /**
     * Inicia o advertising BLE.
     */
    fun startAdvertising() {
        if (!isBluetoothEnabled()) {
            Log.d(TAG, "❌ Bluetooth não está ativo para advertising")
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
    }

    companion object {
        private const val TAG = "BluetoothService"
    }
}


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