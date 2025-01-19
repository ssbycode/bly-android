package com.ssbycode.bly.presentation.screens.home

import com.ssbycode.bly.domain.bluetooth.BluetoothManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.bluetooth.BluetoothDevice
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.foundation.layout.Arrangement.SpaceBetween
import androidx.compose.foundation.lazy.items
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.ssbycode.bly.domain.firebase.FirebaseConfig
import com.ssbycode.bly.domain.firebase.FirebaseManager

@Composable
fun HomeScreen(
    onNavigateToOther: () -> Unit,
    modifier: Modifier = Modifier,
    bluetoothManager: BluetoothManager,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(bluetoothManager)
    ),
    context: Context

) {
    val discoveredDevices by viewModel.discoveredDevices.collectAsState(initial = emptyList())
    val connectedDevice by viewModel.connectedDevice.collectAsState(initial = null)
    val firebaseManager = FirebaseManager()
    // Inicializando Firebase
    FirebaseConfig.initialSetup(context)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Button(
            onClick = { firebaseManager.sendSignal(deviceID = "teste", type = "init", data = "", receiver = "enviador") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Procurar Dispositivos")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(discoveredDevices) { device ->
                DeviceItem(
                    device = device,
                    context = context,
                    isConnected = device.address == connectedDevice?.address,
                    onClick = { /* Implementar conexão */ }
                )
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: BluetoothDevice,
    context: Context,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = SpaceBetween,
            verticalAlignment = CenterVertically
        ) {
            Column {
                Text(
                    text = if (checkBluetoothPermission(context)) {
                        device.name ?: "Dispositivo Desconhecido"
                    } else {
                        "Dispositivo Desconhecido"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (checkBluetoothPermission(context)) {
                        device.address
                    } else {
                        "Endereço indisponível"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (isConnected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Conectado",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun checkBluetoothPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED
}