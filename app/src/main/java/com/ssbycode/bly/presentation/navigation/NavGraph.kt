package com.ssbycode.bly.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ssbycode.bly.presentation.screens.chat.ChatScreen
import com.ssbycode.bly.presentation.screens.home.HomeScreen
import com.ssbycode.bly.domain.bluetooth.BluetoothManager
import android.content.Context
import android.bluetooth.BluetoothAdapter
import androidx.compose.runtime.remember
import com.ssbycode.bly.data.bluetooth.BluetoothCommunicationImpl
import android.bluetooth.BluetoothManager as AndroidBluetoothManager

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Chat : Screen("chat")
}

@Composable
fun AppNavigation(
    context: Context
) {
    val navController = rememberNavController()

    // Criando instância do BluetoothManager
    val bluetoothManager = remember {
        val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as AndroidBluetoothManager).adapter
        val bluetoothService = BluetoothCommunicationImpl(bluetoothAdapter)
        BluetoothManager(context, bluetoothService)
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToOther = {
                    navController.navigate(Screen.Chat.route)
                },
                bluetoothManager = bluetoothManager,
                context = context
            )
        }

        composable(Screen.Chat.route) {
            ChatScreen(navController = navController)
        }
    }
}