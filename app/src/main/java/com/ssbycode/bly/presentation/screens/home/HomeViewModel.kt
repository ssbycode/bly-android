package com.ssbycode.bly.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssbycode.bly.domain.bluetooth.BluetoothManager
import kotlinx.coroutines.launch

class HomeViewModel(
    private val bluetoothManager: BluetoothManager
) : ViewModel() {

    val discoveredDevices = bluetoothManager.discoveredDevices
    val connectedDevice = bluetoothManager.connectedDevice

    fun searchForDevices() {
        viewModelScope.launch {
            bluetoothManager.searchForDevices()
        }
    }

    // Factory para criar o ViewModel com dependências
    class Factory(
        private val bluetoothManager: BluetoothManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                return HomeViewModel(bluetoothManager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}