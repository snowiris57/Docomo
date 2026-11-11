package io.github.snowiris57.reconnect.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import io.github.snowiris57.reconnect.Health

/** 意味を持つ色（テーマに関係なく一定） */
object SignalColors {
    val good = Color(0xFF22C55E)
    val warn = Color(0xFFF59E0B)
    val bad = Color(0xFFEF4444)
    val info = Color(0xFF3B82F6)

    fun at(q: Float): Color = when {
        q < 0.3f -> lerp(bad, warn, q / 0.3f)
        q < 0.64f -> lerp(warn, good, (q - 0.3f) / 0.34f)
        else -> good
    }

    private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))
}

@Composable
fun healthColor(h: Health): Color = when (h) {
    Health.OK -> SignalColors.good
    Health.BAD -> SignalColors.bad
    Health.UNSTABLE -> SignalColors.warn
    Health.PAUSED -> MaterialTheme.colorScheme.outline
    Health.RECOVERING -> SignalColors.info
    Health.STOPPED -> MaterialTheme.colorScheme.outline
}

fun healthLabel(h: Health): String = when (h) {
    Health.OK -> "正常"
    Health.BAD -> "異常"
    Health.UNSTABLE -> "不安定"
    Health.PAUSED -> "休止中"
    Health.RECOVERING -> "再接続中"
    Health.STOPPED -> "停止中"
}

private val BrandLight = lightColorScheme(
    primary = Color(0xFF006A60), onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2E4), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E2), onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF456179), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCE5FF), onTertiaryContainer = Color(0xFF001E31),
    background = Color(0xFFF4FBF8), onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF4FBF8), onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E1), onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEEF5F2),
    surfaceContainer = Color(0xFFE8EFEC), surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerHighest = Color(0xFFDDE4E1),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFF53DBC9), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFF9EF2E4),
    secondary = Color(0xFFB1CCC6), onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47), onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFADCAE6), onTertiary = Color(0xFF153349),
    tertiaryContainer = Color(0xFF2D4960), onTertiaryContainer = Color(0xFFCCE5FF),
    background = Color(0xFF0E1513), onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1513), onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF899390), outlineVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFF090F0E), surfaceContainerLow = Color(0xFF161D1C),
    surfaceContainer = Color(0xFF1A2120), surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF2F3635),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Android 12 以降は壁紙の色（Material You）、それ以前はブランドカラー */
@Composable
fun ReConnectTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
