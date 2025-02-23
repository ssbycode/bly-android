import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize

@Composable
fun BubblePatternBackground(isDarkTheme: Boolean) {

    val Teal = Color(0xFF009688) // Cor Teal (hexadecimal: #009688)

    // Cores do gradiente baseadas no tema, com mais transparência
    val gradientColors = if (isDarkTheme) {
        listOf(
            Color.White.copy(alpha = 0.02f),  // Mais transparente
            Color.White.copy(alpha = 0.04f),  // Mais transparente
            Color.White.copy(alpha = 0.06f)   // Mais transparente
        )
    } else {
        listOf(
            Color.Blue.copy(alpha = 0.08f),    // Mais transparente
            Color.Cyan.copy(alpha = 0.05f),    // Mais transparente
            Teal.copy(alpha = 0.03f)          // Mais transparente
        )
    }

    // Tamanhos das bolhas
    val bubbleSizes = listOf(16.dp, 24.dp, 32.dp, 40.dp)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent) // Fundo transparente
    ) {
        // Espaçamento entre as bolhas
        val spacing = 80.dp.toPx()

        // Loop para desenhar as bolhas em um grid
        for (row in 0..(size.height / spacing).toInt()) {
            for (col in 0..(size.width / spacing).toInt()) {
                // Posição base para cada célula do grid
                val baseX = col * spacing
                val baseY = row * spacing

                // Número de bolhas por célula (1-2)
                val numberOfBubbles = (1..2).random()

                for (i in 0 until numberOfBubbles) {
                    // Tamanho aleatório da bolha
                    val randomSize = bubbleSizes.random().toPx()
                    val offsetX = (0..spacing.toInt()).random().toFloat()
                    val offsetY = (0..spacing.toInt()).random().toFloat()

                    // Posição da bolha
                    val bubblePosition = Offset(baseX + offsetX, baseY + offsetY)

                    // Gradiente linear para o stroke
                    val gradientBrush = Brush.linearGradient(
                        colors = gradientColors,
                        start = bubblePosition,
                        end = Offset(bubblePosition.x + randomSize, bubblePosition.y + randomSize)
                    )

                    // Desenhar a bolha com stroke
                    drawCircle(
                        brush = gradientBrush,
                        radius = randomSize / 2,
                        center = bubblePosition,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }
    }
}