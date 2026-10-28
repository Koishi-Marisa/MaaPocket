package com.maafw.naruto.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maafw.naruto.ui.theme.MaaDesignTokens

/**
 * 设置组件库
 *  presentation/components 系列（SettingRow/SectionHeader/CollapsibleSection/SettingsGroupCard/ListItemDivider）。
 */

/** 设置行：标题 + 描述 + 尾部控件（ SettingRow.kt） */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    verticalPadding: Dp = MaaDesignTokens.Spacing.listItemVertical,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
            )
            .padding(vertical = verticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = if (trailing != null) {
                Modifier
                    .weight(1f)
                    .padding(end = MaaDesignTokens.Spacing.lg)
            } else {
                Modifier
            },
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.rowTitleGap),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.6f),
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) descriptionColor else descriptionColor.copy(alpha = 0.6f),
                )
            }
        }
        trailing?.invoke()
    }
}

/** 分组卡片（ SettingsGroupCard） */
@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = MaaDesignTokens.Spacing.lg),
            content = content,
        )
    }
}

/** 小节标题（ SectionHeader.kt） */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = MaaDesignTokens.Spacing.sm),
    )
}

/** 可折叠小节（ CollapsibleSection.kt） */
@Composable
fun CollapsibleSection(
    title: String,
    modifier: Modifier = Modifier,
    sectionKey: String = title,
    initiallyExpanded: Boolean = false,  // 默认收起（减少每次进入的视觉噪音）
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    // 展开状态持久化（App 重启也记住，不再每次重置为全展开）
    var expanded by remember {
        mutableStateOf(com.maafw.naruto.data.settings.MaaFwSettingsStore.getBoolean("section_$sectionKey", initiallyExpanded))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
        role = Role.Button,
        onClickLabel = if (expanded) "收起" else "展开",
    ) {
        expanded = !expanded
        // 持久化展开状态（App 重启也记住）
        com.maafw.naruto.data.settings.MaaFwSettingsStore.put(context, "section_$sectionKey", expanded)
    }
                .padding(vertical = MaaDesignTokens.Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Rounded.KeyboardArrowUp
                } else {
                    Icons.Rounded.KeyboardArrowDown
                },
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(content = content)
        }
    }
}

/** 列表项分隔线（ ListItemDivider） */
@Composable
fun ListItemDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
) {
    Divider(
        modifier = modifier.padding(start = MaaDesignTokens.Separator.inset),
        thickness = MaaDesignTokens.Separator.thickness,
        color = color,
    )
}