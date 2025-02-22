package com.ssbycode.bly

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.ssbycode.bly.domain.bluetooth.BluetoothService
import com.ssbycode.bly.domain.communication.SignalingService
import com.ssbycode.bly.domain.firebase.FirebaseManager
import com.ssbycode.bly.domain.realTimeCommunication.RealTimeService

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val signalingService: SignalingService
    val bluetoothService: BluetoothService
    val realTimeService: RealTimeService
    val localDeviceID: String

    init {
        // Obtém o contexto corretamente
        val context: Context = getApplication<Application>().applicationContext

        // Obtém o Android ID de forma segura
        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.uppercase() ?: "0000000000000000" // Evita NullPointerException

        localDeviceID = convertToUUID(androidId)

        // Inicializa os serviços
        signalingService = FirebaseManager(localDeviceID = localDeviceID)
        bluetoothService = BluetoothService(context)
        realTimeService = RealTimeService(
            context = context,
            signalingService = signalingService,
            localDeviceID = localDeviceID
        )

        // Verifica permissões e inicializa Bluetooth
        if (bluetoothService.hasBluetoothPermissions()) {
            bluetoothService.initializeBluetooth()
        } else {
            Log.e("MainViewModel", "Sem permissões Bluetooth.")
        }
    }

    /**
     * Converte o Android ID para UUID
     */
    private fun convertToUUID(androidId: String): String {
        val paddedId = androidId.padEnd(32, '0') // Evita IndexOutOfBoundsException
        return "${paddedId.substring(0, 8)}-" +
                "${paddedId.substring(8, 12)}-" +
                "${paddedId.substring(12, 16)}-" +
                "${paddedId.substring(16, 20)}-" +
                "${paddedId.substring(20, 32)}"
    }
}