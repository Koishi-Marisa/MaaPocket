package com.maapocket.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 手写一份最小配色，只作为 dynamicColor 不可用（Android 12 以下）或用户关掉动态取色时的兜底。
// 之所以不引 colors.xml：这些值只在 Compose 里用，走资源反而多一层 indirection。
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCBF5),
    onPrimary = Color(0xFF00325A),
    secondary = Color(0xFFB9C8DA),
    onSecondary = Color(0xFF243141),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF20618F),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF51606F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

/**
 * 极简 Material3 主题。刻意不接自定义字体 / 颜色资源：core 是三个 APK 共用的库模块，
 * 任何新增 res 都会同时改变三端，主题保持“零资源依赖”可避免这种耦合。
 */
@Composable
fun MaaPocketTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
