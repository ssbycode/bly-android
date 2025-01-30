import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
                delay(300)
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
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x552196F3), Color(0x102196F3)),
                    center = Offset(bubbleRadius/2, bubbleRadius/2),
                    radius = bubbleRadius * 1.2f
                ),
                center = Offset(x + shadowOffset, y + shadowOffset),
                radius = bubbleRadius,
                alpha = 0.3f * (1 - easedProgress)
            )

            // Desenho da bolha principal
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.9f),
                        Color(0xFF90CAF9).copy(alpha = 0.6f),
                        Color(0xFF2196F3).copy(alpha = 0.3f)
                    ),
                    center = Offset(bubbleRadius/3, bubbleRadius/3),
                    radius = bubbleRadius * 1.5f
                ),
                center = Offset(x, y),
                radius = bubbleRadius,
                alpha = 0.8f - (easedProgress * 0.7f)
            )

            // Reflexo da luz
            drawCircle(
                color = Color.White.copy(alpha = 0.7f),
                center = Offset(
                    x - bubbleRadius/3,
                    y - bubbleRadius/3
                ),
                radius = bubbleRadius/4
            )
        }
    }
}

private fun createNewBubble(bubbles: MutableList<Bubble>, startTime: Long) {
    bubbles.add(
        Bubble(
            size = Random.nextFloat() * 65 + 45,  // 15-40dp
            startX = Random.nextFloat() * 100,    // 0-100% da largura
            driftAmplitude = Random.nextFloat() * 60 + 30,  // 30-90px
            duration = Random.nextInt(6000, 9000),// 3-6 segundos
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