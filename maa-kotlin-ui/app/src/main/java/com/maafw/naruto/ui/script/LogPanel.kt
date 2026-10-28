package com.maafw.naruto.ui.script

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 运行日志面板
 *  LogPanel.kt，将 LogItem 简化为 String：
 * 保留时间戳行 + 自动滚动 + 手动滚动后暂停自动跟随 + 回到底部按钮 + 清空。
 */
@Composable
fun LogPanel(
    modifier: Modifier = Modifier,
    logs: List<String>,
    onClearLogs: () -> Unit,
    showCopy: Boolean = false,
) {
    val listState = rememberLazyListState()
    var isAutoScroll by remember { mutableStateOf(true) }
    // UI 层模式：false=运行日志(全部)，true=脚本提示(仅 focus 文案，含 [color: 标签的行)
    var focusOnly by remember { mutableStateOf(false) }

    // 连续相同行合并计数：显示 x2 / x3（仅 UI 展示，不改动数据）
    val merged = remember(logs, focusOnly) {
        val src = if (focusOnly) logs.filter { it.contains("[color:") } else logs
        val result = mutableListOf<Pair<String, Int>>()
        for (l in src) {
            if (result.isNotEmpty() && result.last().first == l) {
                result[result.size - 1] = result.last().first to (result.last().second + 1)
            } else {
                result.add(l to 1)
            }
        }
        result
    }

    LaunchedEffect(merged.size, isAutoScroll) {
        if (isAutoScroll && merged.isNotEmpty()) {
            listState.scrollToItem(merged.size - 1)
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            isAutoScroll = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "运行日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                // 图标切换按钮（左对齐，紧跟标题）：点击在「运行日志」与「脚本提示(focus)」之间切换
                IconButton(onClick = { focusOnly = !focusOnly }) {
                    Icon(
                        imageVector = if (focusOnly) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (focusOnly) "返回运行日志" else "查看脚本提示",
                        tint = if (focusOnly) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val context = LocalContext.current
                if (showCopy) {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("MAAFW 运行日志", logs.joinToString("\n")))
                    }) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "复制日志",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onClearLogs) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "清空",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(merged) { index, (content, count) ->
                    LogLine(index = index, content = content, repeat = count)
                }
            }

            if (listState.canScrollForward && merged.isNotEmpty()) {
                IconButton(
                    onClick = { isAutoScroll = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "回到底部",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LogLine(
    index: Int,
    content: String,
    repeat: Int = 1,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = String.format("%02d", index + 1),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1,
            modifier = Modifier.width(28.dp)
        )
        Text(
            text = coloredLog(content),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (repeat > 1) {
            Text(
                text = " x$repeat",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

/**
 * 解析 MaaFramework 的 [color:xxx]...[/color] 颜色标签，渲染成带色 AnnotatedString。
 * - 颜色名大小写不敏感（deepskyblue / DeepSkyBlue 均可）；
 * - 支持 CSS 颜色名（自建映射表优先，缺漏用 Color.parseColor 兜底）与 #RRGGBB / #AARRGGBB；
 * - 解析失败的颜色也会剥离标签，按默认色显示文字（绝不让标签原文外泄）。
 */
private val colorNameMap: Map<String, Color> by lazy {
    // 常用 CSS 颜色名 -> Compose Color（MaaFramework 资源常用色全覆盖）
    mapOf(
        "black" to Color(0xFF000000), "white" to Color(0xFFFFFFFF),
        "red" to Color(0xFFFF0000), "green" to Color(0xFF008000), "blue" to Color(0xFF0000FF),
        "yellow" to Color(0xFFFFFF00), "cyan" to Color(0xFF00FFFF), "aqua" to Color(0xFF00FFFF),
        "magenta" to Color(0xFFFF00FF), "fuchsia" to Color(0xFFFF00FF), "gray" to Color(0xFF808080),
        "grey" to Color(0xFF808080), "orange" to Color(0xFFFFA500), "pink" to Color(0xFFFFC0CB),
        "purple" to Color(0xFF800080), "brown" to Color(0xFFA52A2A), "gold" to Color(0xFFFFD700),
        "lime" to Color(0xFF00FF00), "navy" to Color(0xFF000080), "teal" to Color(0xFF008080),
        "olive" to Color(0xFF808000), "maroon" to Color(0xFF800000), "silver" to Color(0xFFC0C0C0),
        "indigo" to Color(0xFF4B0082), "violet" to Color(0xFFEE82EE),
        "deepskyblue" to Color(0xFF00BFFF), "skyblue" to Color(0xFF87CEEB),
        "dodgerblue" to Color(0xFF1E90FF), "royalblue" to Color(0xFF4169E1),
        "steelblue" to Color(0xFF4682B4), "lightblue" to Color(0xFFADD8E6),
        "darkblue" to Color(0xFF00008B), "cornflowerblue" to Color(0xFF6495ED),
        "turquoise" to Color(0xFF40E0D0), "mediumseagreen" to Color(0xFF3CB371),
        "forestgreen" to Color(0xFF228B22), "lawngreen" to Color(0xFF7CFC00),
        "chartreuse" to Color(0xFF7FFF00), "seagreen" to Color(0xFF2E8B57),
        "springgreen" to Color(0xFF00FF7F), "greenyellow" to Color(0xFFADFF2F),
        "yellowgreen" to Color(0xFF9ACD32), "olivedrab" to Color(0xFF6B8E23),
        "hotpink" to Color(0xFFFF69B4), "deeppink" to Color(0xFFFF1493),
        "orangered" to Color(0xFFFF4500), "darkorange" to Color(0xFFFF8C00),
        "coral" to Color(0xFFFF7F50), "crimson" to Color(0xFFDC143C),
        "salmon" to Color(0xFFFA8072), "tomato" to Color(0xFFFF6347),
        "khaki" to Color(0xFFF0E68C), "beige" to Color(0xFFF5F5DC),
        "ivory" to Color(0xFFFFFFF0), "wheat" to Color(0xFFF5DEB3),
        "tan" to Color(0xFFD2B48C), "chocolate" to Color(0xFFD2691E),
        "rebeccapurple" to Color(0xFF663399), "slateblue" to Color(0xFF6A5ACD),
        "mediumpurple" to Color(0xFF9370DB), "lightcoral" to Color(0xFFF08080),
        "darkred" to Color(0xFF8B0000), "darkgreen" to Color(0xFF006400),
        "darkcyan" to Color(0xFF008B8B), "darkmagenta" to Color(0xFF8B008B),
        "darkviolet" to Color(0xFF9400D3), "darkorchid" to Color(0xFF9932CC),
        "lightgreen" to Color(0xFF90EE90), "palegreen" to Color(0xFF98FB98),
        "aquamarine" to Color(0xFF7FFFD4), "lightcyan" to Color(0xFFE0FFFF),
        "lightyellow" to Color(0xFFFFFFE0), "lightgray" to Color(0xFFD3D3D3),
        "lightgrey" to Color(0xFFD3D3D3), "darkgray" to Color(0xFFA9A9A9),
        "darkgrey" to Color(0xFFA9A9A9), "slategray" to Color(0xFF708090),
        "slategrey" to Color(0xFF708090), "mediumslateblue" to Color(0xFF7B68EE),
        "palevioletred" to Color(0xFFDB7093), "plum" to Color(0xFFDDA0DD),
        "orchid" to Color(0xFFDA70D6), "lavender" to Color(0xFFE6E6FA),
        "mistyrose" to Color(0xFFFFE4E1), "peachpuff" to Color(0xFFFFDAB9),
        "navajowhite" to Color(0xFFFFDEAD), "goldenrod" to Color(0xFFDAA520),
        "mediumturquoise" to Color(0xFF48D1CC), "darkturquoise" to Color(0xFF00CED1),
        "paleturquoise" to Color(0xFFAFEEEE), "mediumaquamarine" to Color(0xFF66CDAA),
        "darkseagreen" to Color(0xFF8FBC8F), "mediumspringgreen" to Color(0xFF00FA9A),
        "lightsalmon" to Color(0xFFFFA07A), "darksalmon" to Color(0xFFE9967A),
        "lightgoldenrodyellow" to Color(0xFFFAFAD2), "saddlebrown" to Color(0xFF8B4513),
        "darkkhaki" to Color(0xFFBDB76B), "darkgoldenrod" to Color(0xFFB8860B),
    )
}

private fun parseMaaColor(colorName: String): Color? {
    val trimmed = colorName.trim()
    // 1) #RRGGBB / #AARRGGBB / #RGB
    if (trimmed.startsWith("#")) {
        return runCatching { Color(android.graphics.Color.parseColor(trimmed)) }.getOrNull()
    }
    // 2) 自建映射（小写匹配）
    colorNameMap[trimmed.lowercase()]?.let { return it }
    // 3) 兜底：Android Color.parseColor（支持全部 CSS 颜色名，大小写不敏感）
    return runCatching { Color(android.graphics.Color.parseColor(trimmed)) }.getOrNull()
}

private fun coloredLog(text: String): AnnotatedString {
        val builder = AnnotatedString.Builder()
        // U-3 增强：无 [color:] 标签时按关键词整体着色（错误红 / 成功绿 / 进行中橙 / 默认）
        val defaultColor = when {
            text.contains("失败") || text.contains("异常") || text.contains("错误")
                || text.contains("死亡") || text.contains("超时")
                || text.contains("注意") -> Color(0xFFE57373) // 红
            text.contains("完成") || text.contains("成功") || text.contains("就绪")
                || text.contains("已连接") -> Color(0xFF81C784) // 绿
            text.contains("正在") || text.contains("开始") || text.contains("等待")
                || text.contains("运行中") -> Color(0xFFFFB74D) // 橙
            else -> null
        }
        if (defaultColor != null) {
            builder.withStyle(SpanStyle(color = defaultColor)) { append(text) }
            return builder.toAnnotatedString()
        }
        // 兼容 [color:xxx] 与 [color=xxx]
        val pattern = Regex("\\[color[:=]([^\\]\\[]+)\\](.*?)\\[/color\\]")
        var lastIndex = 0
    for (match in pattern.findAll(text)) {
        builder.append(text.substring(lastIndex, match.range.first))
        val colorName = match.groupValues[1].trim()
        val inner = match.groupValues[2]
        val color = parseMaaColor(colorName)
        if (color != null) {
            builder.withStyle(SpanStyle(color = color)) { append(inner) }
        } else {
            // 解析失败也剥离标签，按默认色显示文字
            builder.append(inner)
        }
        lastIndex = match.range.last + 1
    }
    builder.append(text.substring(lastIndex))
    return builder.toAnnotatedString()
}