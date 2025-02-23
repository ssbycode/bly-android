package com.ssbycode.bly.presentation.screens.chat

import BubbleAnimation
import BubblePatternBackground
import android.text.Layout
import android.util.Log
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ssbycode.bly.data.realTimeCommunication.Message
import com.ssbycode.bly.presentation.screens.chat.components.UserBubble

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    var showExitDialog by remember { mutableStateOf(false) }
    val messages by viewModel.messages.collectAsState()
    val newMessage by viewModel.newMessage.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val isDarkTheme = isSystemInDarkTheme()



    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // BubblePatternBackground como plano de fundo
        BubblePatternBackground(isDarkTheme = isDarkTheme)

        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Header
            GroupHeader(
                isScanning = isScanning,
                connectedDevices = connectedDevices,
                onExitClick = { showExitDialog = true }
            )

            // Messages
            ChatMessages(
                messages = messages,
                modifier = Modifier.weight(1f)
            )

            // Input Bar
            MessageInputBar(
                message = newMessage,
                onMessageChange = { viewModel.updateNewMessage(it) },
                onSendClick = { viewModel.broadcast() },
                isEnabled = !viewModel.sendButtonDisabled
            )
        }

        if (showExitDialog) {
            ExitDialog(
                onDismiss = { showExitDialog = false },
                onConfirm = {
                    viewModel.disconnect()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun GroupHeader(
    isScanning: Boolean,
    connectedDevices: List<String>,
    onExitClick: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F2F2))
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExitClick) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                text = "Chat 🫧",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    letterSpacing = 2.sp // Espaçamento uniforme entre as letras
                )
            )

            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(connectedDevices) { deviceId ->
                UserBubble(userId = deviceId)
            }
        }
    }
}

@Composable
private fun ChatMessages(
    messages: List<Message>,
    modifier: Modifier = Modifier

) {

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    )
    {
        items(
            items = messages,
            key = { it.id }
        ) { messageInfo ->
            MessageBubble(
                message = messageInfo.content,
                isFromCurrentUser = messageInfo.isFromCurrentUser,
                senderPeerId = messageInfo.senderId,
                isLastInSequence = false
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: String,
    isFromCurrentUser: Boolean,
    senderPeerId: String,
    isLastInSequence: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isFromCurrentUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isFromCurrentUser)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(8.dp),
                color = if (isFromCurrentUser)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isLastInSequence) {
            Text(
                text = senderPeerId.formattedDeviceID,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun MessageInputBar(
    message: String,
    onMessageChange: (String) -> Unit,
    onSendClick: () -> Unit,
    isEnabled: Boolean
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F2F2))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 40.dp)
            .windowInsetsPadding(WindowInsets.ime),// Adiciona padding na parte inferior quando o teclado está visível,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = message,
            onValueChange = onMessageChange,
            modifier = Modifier
                .weight(1f)
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(25.dp)
                ),
            placeholder = { Text("Mensagem") },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )

        IconButton(
            onClick = {
                // Haptic feedback would go here
                onSendClick()
            },
            enabled = isEnabled
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowCircleUp,
                contentDescription = "Send",
                modifier = Modifier.size(32.dp),
                tint = if (isEnabled)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
//            Icon(
//                imageVector = Icons.Default.Send,
//                contentDescription = "Send",
//                modifier = Modifier.size(32.dp),
//                tint = if (isEnabled)
//                    MaterialTheme.colorScheme.primary
//                else
//                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
//            )
        }
    }
}

@Composable
private fun ExitDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sair da bolha? 🫧") },
        text = { Text("Você será desconectado desta conversa") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Sair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}


// Extension property
val String.formattedDeviceID: String
    get() = this.takeLast(4)