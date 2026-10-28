package com.maafw.naruto.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maafw.naruto.R

/**
 * 底部导航栏
 *  的三栏结构：主页 / 定时任务 / 设置。
 * 三个图标全部使用液态玻璃游戏图标包。
 * 选中指示器（pill）带滑动动画，参考分段选择器。
 */
enum class MaaScreen(val title: String, val icon: @Composable () -> Unit) {
    HOME("主页", { Icon(painterResource(R.drawable.ic_home), contentDescription = null) }),
    SCRIPT("脚本", { Icon(painterResource(R.drawable.ic_script), contentDescription = null) }),
    SCHEDULE("定时任务", { Icon(painterResource(R.drawable.ic_schedule), contentDescription = null) }),
    SETTINGS("设置", { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) })
}

@Composable
fun MaaBottomBar(
    current: MaaScreen,
    onSelect: (MaaScreen) -> Unit
) {
    val items = MaaScreen.values()
    val selectedIndex = items.indexOf(current).coerceAtLeast(0)
    val barHeight = 88.dp
    // 选中胶囊：宽占列 80%，高覆盖图标+文字（矩形圆角）
    val pillWidthRatio = 0.8f
    val pillHeight = 60.dp
    val pillColor = MaterialTheme.colorScheme.secondaryContainer
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectedTextColor = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val itemWidth = maxWidth / items.size
            val pillWidth = itemWidth * pillWidthRatio
            val pillOffset = itemWidth * selectedIndex + (itemWidth - pillWidth) / 2
            val animatedOffset by animateDpAsState(
                pillOffset,
                tween(300, easing = FastOutSlowInEasing),
                label = "navPill"
            )
            // 选中指示器（矩形圆角）：CenterStart 垂直居中覆盖图标+文字，水平滑动 offset 对齐 item
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = animatedOffset)
                    .width(pillWidth)
                    .height(pillHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .background(pillColor)
            )
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { index, screen ->
                    val selected = index == selectedIndex
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = { onSelect(screen) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                screen.icon()
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                screen.title,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) selectedTextColor else textColor
                            )
                        }
                    }
                }
            }
        }
    }
}