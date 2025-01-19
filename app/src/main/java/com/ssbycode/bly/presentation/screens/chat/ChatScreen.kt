package com.ssbycode.bly.presentation.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavController

@Composable
fun ChatScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text("Chat Screen")
        Button(onClick = { navController.navigateUp() }) {
            Text("Voltar")
        }
    }
}