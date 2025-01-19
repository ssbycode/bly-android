package com.ssbycode.bly.domain.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import com.ssbycode.bly.domain.communication.BluetoothCommunication
import com.ssbycode.bly.domain.communication.RealTimeCommunication

class BluetoothService(
    private val context: Context,
    private val realTimeService: RealTimeCommunication
) : BluetoothCommunication {

    companion object {
        private const val SERVICE_UUID = "12345678-1234-1234-1234-1234567890AB"
        private const val CHARACTERISTIC_UUID = "87654321-4321-4321-4321-BA0987654321"
        private const val CHUNK_SIZE = 20
    }

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }
    private val bluetoothLeScanner: BluetoothLeScanner? by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }
    private val bluetoothGattServer: BluetoothGattServer? by lazy {
        bluetoothManager.openGattServer(context, gattServerCallback)
    }

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    override val discoveredDevicesFlow: Flow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    override val connectedDeviceFlow: Flow<BluetoothDevice?> = _connectedDevice.asStateFlow()

    private var receivedDataBuffer = mutableMapOf<String, ByteArray>()
    private var gattServer: BluetoothGattServer? = null
    private var gattServiceCharacteristic: BluetoothGattCharacteristic? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!_discoveredDevices.value.contains(device)) {
                _discoveredDevices.value = _discoveredDevices.value + device
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectedDevice.value = gatt.device
//                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectedDevice.value = null
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BluetoothService", "Chunk written successfully")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleReceivedChunk(value, gatt.device.address)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            handleReceivedChunk(value, device.address)
            if (responseNeeded) {
//                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    init {
        setupGattServer()
    }

    private fun setupGattServer() {
        val service = BluetoothGattService(
            UUID.fromString(SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        gattServiceCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(CHARACTERISTIC_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(gattServiceCharacteristic)
//        gattServer?.addService(service)
    }

    override suspend fun startScanning() {
        if (!hasRequiredPermissions()) {
            Log.e("BluetoothService", "Missing required Bluetooth permissions")
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

//        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    override suspend fun stopScanning() {
//        bluetoothLeScanner?.stopScan(scanCallback)
    }

    override suspend fun startAdvertising() {
        if (!hasRequiredPermissions()) return

        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val shortId = realTimeService.localDeviceID.take(10)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
            .build()

//        advertiser?.startAdvertising(settings, data, advertisingCallback)
    }

    override suspend fun stopAdvertising() {
        if (!bluetoothAdapter!!.isEnabled) {
            print("Bluetooth não está ativo para parar o advertising")
            return
        }
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser

//        advertiser?.stopAdvertising(advertisingCallback)

    }

    private fun handleReceivedChunk(data: ByteArray, deviceAddress: String) {
        if (data.size < 2) return

        val chunkIndex = data[0].toInt()
        val totalChunks = data[1].toInt()

        if (chunkIndex >= totalChunks || chunkIndex < 0 || totalChunks <= 0) {
            Log.e("BluetoothService", "Dados inválidos recebidos")
            return
        }

        val chunkData = data.sliceArray(2 until data.size)
        val key = deviceAddress

        receivedDataBuffer[key] = (receivedDataBuffer[key] ?: ByteArray(0)) + chunkData

        if (chunkIndex == totalChunks - 1) {
            val completeData = receivedDataBuffer[key] ?: return
            val cleanData = completeData.takeWhile { it != 0.toByte() }.toByteArray()

            val peerId = String(cleanData, Charsets.UTF_8).trim()
            Log.d("BluetoothService", "Complete peerId received: $peerId")
            realTimeService.connectTo(peerId)

            receivedDataBuffer.remove(key)
        }
    }

    private fun splitDataIntoChunks(data: ByteArray): List<ByteArray> {
        return data.asSequence()
            .chunked(CHUNK_SIZE)
            .map { it.toByteArray() }
            .toList()
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val advertisingCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("BluetoothService", "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BluetoothService", "Advertising failed to start with error: $errorCode")
        }
    }
}