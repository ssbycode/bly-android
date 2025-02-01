package com.ssbycode.bly.animation

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BubbleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String,
    elevation: Dp = 8.dp,
    bubbleColor: Color = Color(0x8864B5F6), // Cor mais transparente
    shineColor: Color = Color.White.copy(alpha = 0.4f)
) {
    val infiniteTransition = rememberInfiniteTransition()
    val density = LocalDensity.current

    // Animação de pulsação
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse"
    )

    // Animação do brilho
    val shineProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing)
        ), label = "shine"
    )

    // Animação da flutuação
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2000
                0f at 0 with LinearEasing
                0.25f at 500 with LinearEasing
                0f at 1000 with LinearEasing
                -0.25f at 1500 with LinearEasing
                0f at 2000 with LinearEasing
            }
        ), label = "float"
    )

    Button(
        onClick = onClick,
        modifier = modifier
            .height(56.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            }
            .drawWithCache {
                val brush = Brush.radialGradient(
                    colors = listOf(
                        bubbleColor.copy(alpha = 0.3f),
                        bubbleColor.copy(alpha = 0.2f),
                        bubbleColor.copy(alpha = 0.1f)
                    ),
                    center = Offset(size.width/3, size.height/3),
                    radius = size.minDimension * 1.5f
                )

                val shineBrush = Brush.linearGradient(
                    colors = listOf(
                        shineColor.copy(alpha = 0f),
                        shineColor,
                        shineColor.copy(alpha = 0f)
                    ),
                    start = Offset(-size.width * (shineProgress % 1), 0f),
                    end = Offset(size.width * (1 - (shineProgress % 1)), size.height)
                )

                val refractionBrush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.2f),
                        Color.White.copy(alpha = 0.1f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.7f, size.height * 0.3f),
                    radius = size.minDimension * 0.8f
                )

                onDrawBehind {
                    // Camada principal da bolha
                    drawCircle(
                        brush = brush,
                        radius = size.minDimension / 2,
                        center = Offset(size.width/2, size.height/2),
                        alpha = 0.6f
                    )

                    // Brilho principal
                    drawCircle(
                        brush = shineBrush,
                        radius = size.minDimension / 2,
                        center = Offset(size.width/2, size.height/2),
                        alpha = 0.3f
                    )

                    // Efeito de refração
                    drawCircle(
                        brush = refractionBrush,
                        radius = size.minDimension / 2,
                        center = Offset(size.width/2, size.height/2)
                    )

                    // Borda sutil
                    drawCircle(
                        color = Color.White.copy(alpha = 0.2f),
                        style = Stroke(width = 1.dp.toPx()),
                        radius = size.minDimension / 2 - 2.dp.toPx(),
                        center = Offset(size.width/2, size.height/2)
                    )
                }
            },
        shape = CircleShape,
       // border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White.copy(alpha = 0.8f)
        )
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            color = Color.White,
            modifier = Modifier
                .offset(y = floatOffset.dp * 2)
                .graphicsLayer {
                  //  alpha = 0.9f - (pulseScale - 1f) * 2f
                }
        )
    }
}