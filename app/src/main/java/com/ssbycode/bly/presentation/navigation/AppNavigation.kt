package com.ssbycode.bly.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ssbycode.bly.presentation.screens.chat.ChatScreen
import com.ssbycode.bly.presentation.screens.home.HomeScreen
import com.ssbycode.bly.domain.bluetooth.BluetoothService
import com.ssbycode.bly.domain.realTimeCommunication.RealTimeService
import com.ssbycode.bly.presentation.screens.chat.ChatViewModel

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Chat : Screen("chat")
}

@Composable
fun AppNavigation(
    realTimeService: RealTimeService,
    bluetoothService: BluetoothService
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                bluetoothService = bluetoothService,
                realTimeService = realTimeService,
                navController = navController
            )
        }
        composable(Screen.Chat.route) {
            // Criando o ViewModel corretamente
            val chatViewModel: ChatViewModel =
                viewModel(
                    factory = ChatViewModel.Factory(
                        bluetoothService,
                        realTimeService
                    )
                )

            ChatScreen(
                viewModel = chatViewModel,
                onDismiss = { navController.popBackStack() }
            )
        }
    }
}