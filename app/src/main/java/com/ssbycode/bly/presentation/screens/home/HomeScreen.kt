package com.ssbycode.bly.presentation.screens.home

import BubbleAnimation
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import com.ssbycode.bly.domain.firebase.FirebaseConfig
import com.ssbycode.bly.domain.firebase.FirebaseManager
import androidx.navigation.NavController
import com.ssbycode.bly.animation.BubbleButton
import com.ssbycode.bly.domain.bluetooth.BluetoothService
import com.ssbycode.bly.domain.realTimeCommunication.RealTimeService
import com.ssbycode.bly.presentation.navigation.Screen
import com.ssbycode.bly.presentation.screens.chat.ChatScreen
import kotlinx.coroutines.flow.take

@Composable
fun HomeScreen(
    bluetoothService: BluetoothService,
    realTimeService: RealTimeService,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    var showAlert by remember { mutableStateOf(false) }
    var alertMessage by remember { mutableStateOf("") }
    var newPeerId by remember { mutableStateOf("959298FD-6A7C-494F-B4B0-9417CCEA626D") }//"020047A9-B62F-43D6-A430-7998E3A4A0FA") }

    val isConnected = realTimeService.connectedDevices.collectAsState().value.isNotEmpty()
    val connectedDevicesCount = realTimeService.connectedDevices.collectAsState().value.size

    val titleBubbleButton = if (isConnected) "Entrar na Bolha" else "Criar Bolha"

    // Inicia o anúncio Bluetooth quando a HomeScreen é exibida
    LaunchedEffect(Unit) {
        bluetoothService.startAdvertising()
    }

    Box(modifier = modifier.fillMaxSize()) {

        BubbleAnimation(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.7f)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Text(

                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center, // Alinha o texto à direita
                text = "Bly",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold

            )

            // Main Button
            BubbleButton(
                onClick = {
                    if (isConnected) {
                        navController.navigate(Screen.Chat.route)
                    } else {
                        if (newPeerId.isNotEmpty()) {
                            realTimeService.connectTo(newPeerId)
                        }
                    }
                },
                modifier = Modifier
                    .padding(top = 250.dp)
                    .fillMaxWidth()
                    .height(200.dp),
                text = titleBubbleButton,
                bubbleColor = Color(0x7E2196F3), // Mais transparente
                shineColor = Color.White.copy(alpha = 0.3f),

                )

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
                            realTimeService.disconnectAll()
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
                        text = "Local ID: ${"XXXX"}",
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