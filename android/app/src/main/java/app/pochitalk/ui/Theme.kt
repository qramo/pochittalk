package app.pochitalk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Web版 (worker.js の :root) と同じ配色。 */
object PochiColors {
    val Bg = Color(0xFF1A1E24)
    val Card = Color(0xFF252B36)
    val Panel = Color(0xFF14181D)
    val Accent = Color(0xFF3B82F6)
    val Tx = Color(0xFFEF4444)
    val Rx = Color(0xFF10B981)
    val Text = Color(0xFFF3F4F6)
    val Muted = Color(0xFF9CA3AF)
    val Border = Color(0xFF374151)
    val Sky = Color(0xFF38BDF8)
    val Amber = Color(0xFFFBBF24)
    val Chip = Color(0xFF1E293B)
    val ChipBorder = Color(0xFF334155)
    val LedOff = Color(0xFF374151)
}

@Composable
fun PochiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PochiColors.Accent,
            onPrimary = Color.White,
            background = PochiColors.Bg,
            onBackground = PochiColors.Text,
            surface = PochiColors.Card,
            onSurface = PochiColors.Text,
            surfaceVariant = PochiColors.Panel,
            onSurfaceVariant = PochiColors.Muted,
            outline = PochiColors.Border,
        ),
        content = content,
    )
}
