package com.ssbycode.bly.domain.communication

import kotlinx.coroutines.flow.Flow
import android.bluetooth.BluetoothDevice

interface BluetoothCommunication {
    val discoveredDevicesFlow: Flow<List<BluetoothDevice>>
    val connectedDeviceFlow: Flow<BluetoothDevice?>

    suspend fun startScanning()
    suspend fun stopScanning()
    suspend fun startAdvertising()
    suspend fun stopAdvertising()
}