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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.ssbycode.bly.domain.firebase.FirebaseConfig
import com.ssbycode.bly.domain.firebase.FirebaseManager
import androidx.navigation.NavController
import com.ssbycode.bly.domain.communication.BluetoothCommunication
import com.ssbycode.bly.domain.communication.RealTimeCommunication
import com.ssbycode.bly.domain.realTimeCommunication.RealTimeService
import com.ssbycode.bly.presentation.navigation.Screen
import com.ssbycode.bly.presentation.screens.chat.ChatScreen

@Composable
fun HomeScreen(
    realTimeManager: RealTimeCommunication,
    bluetoothManager: BluetoothCommunication,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    var showAlert by remember { mutableStateOf(false) }
    var alertMessage by remember { mutableStateOf("") }
    var newPeerId by remember { mutableStateOf("43C1A3BB-2AC0-4354-AFC8-450C8E7E471B") }//"020047A9-B62F-43D6-A430-7998E3A4A0FA") }

    val isConnected = realTimeManager.connectedDevicesFlow.collectAsState().value.isNotEmpty()
    val connectedDevicesCount = realTimeManager.connectedDevicesFlow.collectAsState().value.size

    val titleBubbleButton = if (isConnected) "Entrar na Bolha" else "Criar Bolha"

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Text(
                text = "Bly",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Main Button
            Button(
                onClick = {
                    if (isConnected) {
                        navController.navigate(Screen.Chat.route) // Navega para a tela de chat diretamente
                    } else {
                        if (newPeerId.isNotEmpty()) {
                            realTimeManager.connectTo(newPeerId)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = titleBubbleButton)
            }

            // Connection Controls
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = newPeerId,
                    onValueChange = { newPeerId = it },
                    label = { Text("Novo dispositivo") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(0.6f),
                    singleLine = true
                )

                if (isConnected) {
                    Button(
                        onClick = {
                            realTimeManager.disconnectAll()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Desconectar")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Local ID: ${realTimeManager.localDeviceID.take(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Conexões: $connectedDevicesCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Alert Dialog
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text("Connection Status") },
            text = { Text(alertMessage) },
            confirmButton = {
                TextButton(onClick = { showAlert = false }) {
                    Text("OK")
                }
            }
        )
    }
}