package com.ssbycode.bly.domain.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssbycode.bly.domain.communication.BluetoothCommunication
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BluetoothManager(
    private val context: Context,
    private val bluetoothService: BluetoothCommunication
) : ViewModel() {

    // Mudando de LiveData para StateFlow
    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var observerJob: Job? = null

    init {
        setupObservers()
    }

    fun searchForDevices() {
        viewModelScope.launch {
            bluetoothService.startScanning()
        }
    }

    fun stopSearchingForDevices() {
        viewModelScope.launch {
            bluetoothService.stopScanning()
        }
    }

    fun startAdvertising() {
        viewModelScope.launch {
            bluetoothService.startAdvertising()
        }
    }

    fun stopAdvertising() {
        viewModelScope.launch {
            bluetoothService.stopAdvertising()
        }
    }

    private fun setupObservers() {
        observerJob?.cancel()
        observerJob = viewModelScope.launch(SupervisorJob()) {
            // Observa mudanças nos dispositivos descobertos
            launch {
                bluetoothService.discoveredDevicesFlow.collect { devices ->
                    _discoveredDevices.value = devices // Mudado de postValue para value
                }
            }

            // Observa mudanças no dispositivo conectado
            launch {
                bluetoothService.connectedDeviceFlow.collect { device ->
                    _connectedDevice.value = device // Mudado de postValue para value
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        observerJob?.cancel()
    }
}