package com.ssbycode.bly.data.bluetooth.repository

import android.bluetooth.BluetoothDevice

interface BluetoothRepository {
    suspend fun scanForDevices()
    suspend fun stopScan()
    suspend fun connectToDevice(device: BluetoothDevice)
}