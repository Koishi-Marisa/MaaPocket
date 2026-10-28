package com.maafw.naruto.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 聚光灯操作引导（Spotlight / Coach Marks）
 * 专业 App 同款：暗色遮罩 + 目标区域高亮挖洞 + 呼吸描边 + 底部引导卡（进度点/跳过/上一步/下一步）。
 */

data class GuideStep(
    val key: String,
    val title: String,
    val description: String
)

/**
 * 全局操作引导控制器
 * 由 MainActivity 持有并在 Scaffold 之外渲染，避免被底部导航栏遮挡、坐标错位。
 */
class GuideController {
    val targets = androidx.compose.runtime.mutableStateMapOf<String, Rect>()
    var steps by mutableStateOf<List<GuideStep>>(emptyList())
        private set
    var stepIndex by mutableStateOf(-1)
        private set
    var onFinished: (() -> Unit)? = null

    fun start(steps: List<GuideStep>) {
        this.steps = steps
        this.stepIndex = 0
    }

    fun next() {
        if (steps.isEmpty()) return
        if (stepIndex >= steps.size - 1) dismiss() else stepIndex++
    }

    fun prev() {
        if (stepIndex > 0) stepIndex--
    }

    fun dismiss() {
        stepIndex = -1
        steps = emptyList()
        targets.clear()
        onFinished?.invoke()
    }

    val isActive: Boolean get() = stepIndex >= 0 && steps.isNotEmpty()
}

@Composable
fun SpotlightGuide(
    steps: List<GuideStep>,
    stepIndex: Int,
    targets: Map<String, Rect>,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty() || stepIndex < 0 || stepIndex >= steps.size) return
    val step = steps[stepIndex]
    val target = targets[step.key]
    val colorScheme = MaterialTheme.colorScheme

    // 目标区域呼吸描边动画
    val infinite = rememberInfiniteTransition()
    val pulse by infinite.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse)
    )

    Box(modifier = modifier.fillMaxSize()) {
        // 暗色遮罩 + 目标挖洞（Path EvenOdd：全屏矩形 + 目标圆角矩形，洞区域不填充）
        if (target != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                            addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(size.width, size.height)))
                            addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    left = target.left,
                                    top = target.top,
                                    right = target.right,
                                    bottom = target.bottom,
                                    radiusX = 20.dp.toPx(),
                                    radiusY = 20.dp.toPx()
                                )
                            )
                        }
                        drawPath(
                            path = path,
                            color = Color.Black.copy(alpha = 0.62f)
                        )
                    }
            )
            // 呼吸高亮描边
            Box(
                Modifier
                    .offset(x = target.left.dp, y = target.top.dp)
                    .size(target.width.dp, target.height.dp)
                    .drawBehind {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.9f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx() * pulse),
                            cornerRadius = CornerRadius(20.dp.toPx())
                        )
                    }
            )
        }

        // 引导卡：垂直居中偏上显示，避免被底部导航栏遮挡
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 96.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // 步骤进度点
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    steps.forEachIndexed { index, _ ->
                        val active = index == stepIndex
                        Box(
                            Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (active) 10.dp else 8.dp)
                                .background(
                                    if (active) colorScheme.primary else colorScheme.outlineVariant,
                                    CircleShape
                                )
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    step.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    step.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onSkip) {
                        Text("跳过", color = colorScheme.onSurfaceVariant)
                    }
                    if (stepIndex > 0) {
                        TextButton(onClick = onPrev) { Text("上一步") }
                    } else {
                        Spacer(Modifier.width(0.dp))
                    }
                    TextButton(onClick = onNext) {
                        Text(
                            if (stepIndex >= steps.size - 1) "完成" else "下一步（${stepIndex + 1}/${steps.size}）",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 目标坐标收集器：挂在目标组件上，把全局坐标写入 map 。
 * 用法：val targets = remember { mutableStateMapOf<String, Rect>() }
 *       Modifier.then(rememberGuideTarget("key", targets))
 */
fun rememberGuideTarget(
    key: String,
    targets: SnapshotStateMap<String, Rect>
): Modifier {
    return Modifier.onGloballyPositioned { coords ->
        val rect = coords.boundsInRoot()
        if (targets[key] != rect) {
            targets[key] = rect
        }
    }
}