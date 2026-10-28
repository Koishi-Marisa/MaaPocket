package com.maafw.naruto.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 复刻 Uiverse 复选框动画：背景色 0.3s 过渡 + 对勾 0.2s 生长。
 * 选中 = primary 背景 + 白色对勾生长；未选中 = surfaceVariant 背景。
 */
@Composable
internal fun AnimatedCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 25.dp,
) {
    val bgColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 300),
        label = "checkboxBg"
    )
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "checkProgress"
    )
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 6.25f))
            .background(bgColor)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val check = Path().apply {
                moveTo(w * 0.24f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.72f)
                lineTo(w * 0.78f, h * 0.28f)
            }
            val pm = PathMeasure().apply { setPath(check, false) }
            val len = pm.length
            if (progress > 0f && len > 0f) {
                val segment = Path()
                pm.getSegment(0f, len * progress, segment, true)
                drawPath(
                    segment,
                    color = Color.White,
                    style = Stroke(width = (size.value * 0.12f).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}