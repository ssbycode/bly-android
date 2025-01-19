// data/bluetooth/BluetoothCommunicationImpl.kt
package com.ssbycode.bly.data.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.ssbycode.bly.domain.communication.BluetoothCommunication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BluetoothCommunicationImpl(
    private val bluetoothAdapter: BluetoothAdapter
) : BluetoothCommunication {

    private val _discoveredDevicesFlow = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    override val discoveredDevicesFlow: StateFlow<List<BluetoothDevice>> = _discoveredDevicesFlow.asStateFlow()

    private val _connectedDeviceFlow = MutableStateFlow<BluetoothDevice?>(null)
    override val connectedDeviceFlow: StateFlow<BluetoothDevice?> = _connectedDeviceFlow.asStateFlow()

    override suspend fun startScanning() {
        bluetoothAdapter.startDiscovery()
    }

    override suspend fun stopScanning() {
        bluetoothAdapter.cancelDiscovery()
    }

    override suspend fun startAdvertising() {
//         Implementar lógica de advertising
    }

    override suspend fun stopAdvertising() {
//         Implementar lógica para parar advertising
    }
}