package com.maafw.naruto.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maafw.naruto.ui.theme.MaaDesignTokens

/**
 * 首次启动引导页
 * 3 步：欢迎 / 权限（可实际申请）/ 使用说明。完成后由调用方写入"已引导"标记。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onRequestPermissions: () -> Unit = {},
) {
    var step by remember { mutableStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // 步骤指示点
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(5.dp)
                            )
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            when (step) {
                0 -> StepWelcome()
                1 -> StepPermission(
                    onRequestShizuku = { /* 由用户后续在主页授权 */ },
                    onRequestAll = onRequestPermissions,
                )
                2 -> StepUsage()
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    if (step < 2) step++ else onFinish()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.button)
            ) {
                Text(if (step < 2) "下一步" else "开始使用")
            }
            if (step > 0) {
                TextButton(onClick = { step-- }) {
                    Text("上一步")
                }
            }
        }
    }
}

@Composable
private fun StepWelcome() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "MAAFW 火影忍者",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "欢迎使用火影忍者手游自动化脚本",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "基于 MaaFramework 的安卓自动化助手\n" +
                "自动完成日常任务、决斗场、秘境等玩法\n" +
                "支持定时任务与后台唤醒，挂机解放双手",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StepPermission(onRequestShizuku: () -> Unit, onRequestAll: () -> Unit) {
    val context = LocalContext.current
    Column {
        Text(
            "权限说明",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "建议先点击下方「一键申请」，逐项授予必要权限，保证定时任务与后台运行可靠。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRequestAll,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.button)
        ) {
            Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("一键申请必要权限")
        }
        Spacer(Modifier.height(12.dp))
        PermissionItem(
            icon = Icons.Filled.PlayArrow,
            title = "Shizuku 授权（必需）",
            desc = "用于创建虚拟屏幕、注入触摸、运行引擎。需要先安装并启动 Shizuku，然后在本应用授权。"
        )
        PermissionItem(
            icon = Icons.Filled.Schedule,
            title = "通知 / 闹钟权限",
            desc = "定时任务触发与完成提醒需要通知权限、精确闹钟权限。"
        )
        PermissionItem(
            icon = Icons.Filled.Settings,
            title = "电池优化 / 自启动",
            desc = "建议允许忽略电池优化、开启自启动，保证定时任务后台可靠执行。"
        )
        PermissionItem(
            icon = Icons.Filled.Lock,
            title = "无障碍防杀（推荐）",
            desc = if (android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )?.contains("com.maafw.naruto/.service.KeepAliveAccessibilityService") == true
            ) "已启用：后台挂机时 App 进程受系统保护，引擎任务更稳定。"
            else "后台挂机防杀关键：点击前往无障碍设置开启（不读取屏幕内容，仅防杀）。"
        )
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("去开启无障碍防杀")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "授权后仍可随时在设置页调整；Shizuku 需在主页「检查 / 请求 Shizuku」完成。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepUsage() {
    Column {
        Text(
            "使用步骤",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        UsageItem(1, "授权 Shizuku", "安装并启动 Shizuku，主页点击「检查 / 请求 Shizuku」完成授权。")
        UsageItem(2, "配置任务", "进入「脚本」页，编辑任务列表；点「任务配置」可新建多套方案。")
        UsageItem(3, "开始运行", "点击「开始任务」，虚拟屏显示游戏画面，可全屏预览与触摸操作。")
        UsageItem(4, "定时执行", "「定时任务」页新建策略；开启「后台唤醒」后锁屏也能自动执行。")
        UsageItem(5, "通知推送", "设置页可配置喵提醒/Server酱/钉钉等，任务完成/出错时通知你。")
    }
}

@Composable
private fun PermissionItem(icon: ImageVector, title: String, desc: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UsageItem(index: Int, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$index",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}