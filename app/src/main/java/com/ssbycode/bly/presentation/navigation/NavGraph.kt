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
import com.ssbycode.bly.domain.communication.BluetoothCommunication
import com.ssbycode.bly.domain.communication.RealTimeCommunication
import com.ssbycode.bly.domain.realTimeCommunication.RealTimeViewModel
import android.bluetooth.BluetoothManager as AndroidBluetoothManager

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Chat : Screen("chat")
}

@Composable
fun AppNavigation(
    context: Context,
    bluetoothManager: BluetoothCommunication,
    realTimeViewModel: RealTimeViewModel
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            HomeScreen(
                realTimeViewModel = realTimeViewModel,
                bluetoothManager = bluetoothManager,
                navController = navController
            )
        }
        composable(Screen.Chat.route) {
            ChatScreen(
                realTimeViewModel = realTimeViewModel,
                navController = navController
            )
        }
    }
}