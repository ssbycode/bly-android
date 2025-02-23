package com.ssbycode.bly

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.ssbycode.bly.presentation.navigation.AppNavigation

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)


        setContent {
            MaterialTheme {
                AppNavigation(
                    realTimeService = viewModel.realTimeService,
                    bluetoothService = viewModel.bluetoothService
                )
            }
        }
    }
}