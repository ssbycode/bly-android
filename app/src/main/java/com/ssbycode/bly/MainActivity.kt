package com.ssbycode.bly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import android.Manifest
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.FirebaseApp
import com.ssbycode.bly.domain.firebase.FirebaseConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ssbycode.bly.domain.bluetooth.BluetoothManager
import com.ssbycode.bly.domain.bluetooth.BluetoothService
import com.ssbycode.bly.domain.communication.BluetoothCommunication
import com.ssbycode.bly.domain.communication.RealTimeCommunication
import com.ssbycode.bly.domain.communication.SignalingService
import com.ssbycode.bly.domain.firebase.FirebaseManager
import com.ssbycode.bly.presentation.navigation.AppNavigation
import com.ssbycode.bly.domain.realTimeCommunication.RealTimeManager
import com.ssbycode.bly.domain.realTimeCommunication.RealTimeService
import com.ssbycode.bly.presentation.screens.LoadingScreen

class MainActivity : ComponentActivity() {
    private lateinit var localDeviceID: String
    private lateinit var signalingService: SignalingService
    private lateinit var bluetoothManager: BluetoothCommunication
    private lateinit var realTimeManager: RealTimeCommunication

    // Adicione um estado para controlar quando os serviços estão prontos
    private var servicesInitialized by mutableStateOf(false)

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeServices()
            servicesInitialized = true
        } else {
            Log.e("MainActivity", "Bluetooth permissions not granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseConfig.initialSetup(context = this)

        // Verificar e solicitar permissões
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            )
        } else {
            bluetoothPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }

        setContent {
            MaterialTheme {
                // Verifica se os serviços estão inicializados antes de carregar a navegação
                if (servicesInitialized) {
                    AppNavigation(
                        context = this,
                        bluetoothManager = bluetoothManager,
                        realTimeManager = realTimeManager
                    )
                } else {
                    LoadingScreen() // Tela de carregamento enquanto inicializa
                }
            }
        }
    }

    private fun convertToUUID(androidId: String): String {
        // Garantir que temos 32 caracteres preenchendo com zeros se necessário
        val paddedId = androidId.padEnd(32, '0')

        // Quebrar em 5 grupos conforme padrão UUID
        val group1 = paddedId.substring(0, 8)           // 8 caracteres
        val group2 = paddedId.substring(8, 12)          // 4 caracteres
        val group3 = paddedId.substring(12, 16)         // 4 caracteres
        val group4 = paddedId.substring(16, 20)         // 4 caracteres
        val group5 = paddedId.substring(20, 32)         // 12 caracteres

        // Montar o UUID no formato padrão
        return "$group1-$group2-$group3-$group4-$group5"
    }

    private fun initializeServices() {
        try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).uppercase()
            val localDeviceID = convertToUUID(androidId)

            println("** LOCAL ID")
            println(localDeviceID)
            signalingService = FirebaseManager()

            realTimeManager = RealTimeService(
                context = this,
                signalingService = signalingService,
                localDeviceID = localDeviceID
            )

            bluetoothManager = BluetoothService(
                context = this,
                realTimeService = realTimeManager
            )

        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing services", e)
        }
    }
}