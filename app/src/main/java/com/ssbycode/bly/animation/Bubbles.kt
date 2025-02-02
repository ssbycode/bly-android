import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BubbleAnimation(modifier: Modifier = Modifier) {
    val bubbles = remember { mutableStateListOf<Bubble>() }
    val density = LocalDensity.current
    var animationTime by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        // Coroutine para atualização contínua do tempo de animação
        launch {
            while (true) {
                withInfiniteAnimationFrameMillis { time ->
                    animationTime = time
                }
            }
        }

        // Coroutine para gerenciar as bolhas
        launch {
            repeat(30) { createNewBubble(bubbles, animationTime) }

            while (true) {
                delay(1000)
                bubbles.removeAll { bubble ->
                    (animationTime - bubble.startTime) > bubble.duration
                }
                if (bubbles.size < 30) {
                    createNewBubble(bubbles, animationTime)
                }
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val shadowOffset = with(density) { 4.dp.toPx() }

        bubbles.forEach { bubble ->
            val progress = ((animationTime - bubble.startTime).toFloat() / bubble.duration).coerceIn(0f, 1f)

            // Cálculo da posição com easing
            val easedProgress = 1 - (1 - progress) * (1 - progress) // Ease-out quadrático

            // Posição vertical
            val y = canvasHeight * (1 - easedProgress)

            // Movimento lateral usando onda senoidal
            val x = (bubble.startX * canvasWidth / 100f) +
                    cos(easedProgress * 4 * PI).toFloat() * bubble.driftAmplitude

            val bubbleRadius = with(density) { bubble.size.toDp().toPx() }

            // Desenho da sombra
            drawCircle(
                style = Stroke(width = 0.8.dp.toPx()),
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0.8f, 0.7f, 1.0f).copy(alpha = 0.15f),  // Lilás
                        Color(0.7f, 0.6f, 0.95f).copy(alpha = 0.1f)    // Roxo
                    ),
                    center = Offset(bubbleRadius/2, bubbleRadius/2),
                    radius = bubbleRadius * 0.6f
                ),
                center = Offset(x, y),
                radius = bubbleRadius * 0.96f
            )

            // Corpo principal branco
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.98f),
                        Color.White.copy(alpha = 0.95f),
                        Color.White.copy(alpha = 0.9f)
                    ),
                    center = Offset(bubbleRadius/2, bubbleRadius/2),
                    radius = bubbleRadius * 0.6f
                ),
                center = Offset(x, y),
                radius = bubbleRadius
            )

            // Borda roxo-lilás transparente
            drawCircle(
                style = Stroke(width = 1.2.dp.toPx()),
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0.85f, 0.75f, 1.0f).copy(alpha = 0.35f), // Lilás claro
                        Color(0.75f, 0.65f, 0.97f).copy(alpha = 0.25f),// Roxo médio
                        Color(0.65f, 0.55f, 0.92f).copy(alpha = 0.15f) // Roxo escuro
                    ),
                    start = Offset(x - bubbleRadius, y - bubbleRadius),
                    end = Offset(x + bubbleRadius, y + bubbleRadius)
                ),
                center = Offset(x, y),
                radius = bubbleRadius
            )

            // Efeito de profundidade suave
            drawCircle(
                style = Stroke(width = bubbleRadius * 0.05f),
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.8f to Color.Transparent,
                        0.9f to Color(0.6f, 0.5f, 0.85f).copy(alpha = 0.1f),
                        1.0f to Color(0.5f, 0.4f, 0.8f).copy(alpha = 0.05f)
                    ).toList().toTypedArray(),
                    center = Offset(x, y),
                    radius = bubbleRadius * 1.1f
                ),
                center = Offset(x, y),
                radius = bubbleRadius
            )

            // Reflexo brilhante
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.9f),
                        Color.White.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = bubbleRadius/3
                ),
                center = Offset(
                    x - bubbleRadius/3.2f,
                    y - bubbleRadius/3.2f
                ),
                radius = bubbleRadius/5
            )
        }
    }
}


private fun createNewBubble(bubbles: MutableList<Bubble>, startTime: Long) {
    bubbles.add(
        Bubble(
            size = Random.nextFloat() * 45 + 25,  // 15-40dp
            startX = Random.nextFloat() * 100,    // 0-100% da largura
            driftAmplitude = Random.nextFloat() * 40 + 20,  // 30-90px
            duration = Random.nextInt(10000, 15000),// 3-6 segundos
            startTime = startTime
        )
    )
}

data class Bubble(
    val size: Float,
    val startX: Float,
    val driftAmplitude: Float,
    val duration: Int,
    val startTime: Long
)