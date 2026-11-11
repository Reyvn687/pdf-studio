package az.pdfstudio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Indigo = Color(0xFF4F46E5)
val Cyan = Color(0xFF06B6D4)

private val Light = lightColorScheme(
    primary = Indigo, secondary = Cyan, tertiary = Color(0xFFFBBF24),
    background = Color(0xFFF6F7FB), surface = Color.White, surfaceVariant = Color(0xFFE9EBF5)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9AA0FF), secondary = Cyan, tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0F1020), surface = Color(0xFF191A2E), surfaceVariant = Color(0xFF23253C)
)

@Composable
fun PdfStudioTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
