package com.maafw.naruto.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.ui.components.ListItemDivider
import com.maafw.naruto.ui.components.MaaTopAppBar
import com.maafw.naruto.ui.components.SettingRow
import com.maafw.naruto.ui.theme.MaaDesignTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 第三方通知设置页
 * 最前面：任务完成/出错两个独立开关；下面：各渠道卡片，点击选中并展开对应配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var notifySuccess by remember { mutableStateOf(SettingsRepository.isPushNotifySuccess(context)) }
    var notifyError by remember { mutableStateOf(SettingsRepository.isPushNotifyError(context)) }
    var notifyStart by remember { mutableStateOf(SettingsRepository.isPushNotifyStart(context)) }
    var pushChannel by remember { mutableStateOf(SettingsRepository.getPushChannel(context)) }

    val channels = listOf(
        "none" to "关闭",
        "miaotixing" to "喵提醒",
        "serverchan" to "Server酱",
        "dingtalk" to "钉钉",
        "smtp" to "SMTP 邮件",
        "webhook" to "自定义 Webhook"
    )

    Scaffold(
        topBar = {
            MaaTopAppBar(
                title = "第三方通知",
                navigationIcon = Icons.Filled.ArrowBack,
                onNavigationClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = MaaDesignTokens.Spacing.listHorizontal,
                vertical = MaaDesignTokens.Spacing.sm
            ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)
        ) {
            // 完成/出错独立开关（放在最前面）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SettingRow(
                            title = "任务开始时推送",
                            description = "任务开始执行时推送（默认关闭）",
                            trailing = {
                                Switch(
                                    checked = notifyStart,
                                    onCheckedChange = {
                                        notifyStart = it
                                        SettingsRepository.setPushNotifyStart(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "任务完成时推送",
                            description = "定时任务执行完毕后推送",
                            trailing = {
                                Switch(
                                    checked = notifySuccess,
                                    onCheckedChange = {
                                        notifySuccess = it
                                        SettingsRepository.setPushNotifySuccess(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "任务出错时推送",
                            description = "定时任务异常时推送",
                            trailing = {
                                Switch(
                                    checked = notifyError,
                                    onCheckedChange = {
                                        notifyError = it
                                        SettingsRepository.setPushNotifyError(context, it)
                                    }
                                )
                            }
                        )
                    }
                }
            }

            // 渠道卡片按钮
            items(channels) { (value, label) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            pushChannel = value
                            SettingsRepository.setPushChannel(context, value)
                        },
                    shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
                    colors = CardDefaults.cardColors(
                        containerColor = if (pushChannel == value)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (pushChannel == value) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (pushChannel == value) {
                            Text(
                                "已选",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // 选中渠道的配置区
                if (pushChannel == value && value != "none") {
                    ChannelConfig(value)
                }
            }

            // 测试推送
            item {
                if (pushChannel != "none") {
                    Button(
                        onClick = {
                            com.maafw.naruto.data.notify.PushDispatcher.test(context, pushChannel) { result ->
                                android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("发送测试推送")
                    }
                }
            }
        }
    }
}

/** 渠道配置输入区（按选中渠道显示） */
@Composable
private fun ChannelConfig(channel: String) {
    val context = LocalContext.current
    when (channel) {
        "miaotixing" -> PushTextField("喵提醒 喵码", remember { SettingsRepository.getPushMiaotixingToken(context) }.let { it },
            { SettingsRepository.setPushMiaotixingToken(context, it) })
        "serverchan" -> PushTextField("Server酱 SendKey", remember { SettingsRepository.getPushServerChanKey(context) }.let { it },
            { SettingsRepository.setPushServerChanKey(context, it) })
        "dingtalk" -> PushTextField("钉钉 access_token", remember { SettingsRepository.getPushDingTalkToken(context) }.let { it },
            { SettingsRepository.setPushDingTalkToken(context, it) })
        "smtp" -> {
            PushTextField("SMTP 主机", remember { SettingsRepository.getPushSmtpHost(context) }.let { it }, { SettingsRepository.setPushSmtpHost(context, it) })
            PushTextField("SMTP 端口(465/587)", remember { SettingsRepository.getPushSmtpPort(context).toString() }.let { it }, { SettingsRepository.setPushSmtpPort(context, it) })
            PushTextField("SMTP 账号", remember { SettingsRepository.getPushSmtpUser(context) }.let { it }, { SettingsRepository.setPushSmtpUser(context, it) })
            PushTextField("SMTP 密码/授权码", remember { SettingsRepository.getPushSmtpPass(context) }.let { it }, { SettingsRepository.setPushSmtpPass(context, it) })
            PushTextField("收件人", remember { SettingsRepository.getPushSmtpTo(context) }.let { it }, { SettingsRepository.setPushSmtpTo(context, it) })
        }
        "webhook" -> {
            PushTextField("Webhook URL", remember { SettingsRepository.getPushWebhookUrl(context) }.let { it }, { SettingsRepository.setPushWebhookUrl(context, it) })
            PushTextField("Body（{title}/{content}）", remember { SettingsRepository.getPushWebhookBody(context) }.let { it }, { SettingsRepository.setPushWebhookBody(context, it) })
        }
    }
}

@Composable
private fun PushTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 内部可变状态：输入实时更新并回调，避免输入内容丢失
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}