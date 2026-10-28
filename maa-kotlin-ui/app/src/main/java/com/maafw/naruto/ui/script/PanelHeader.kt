package com.maafw.naruto.ui.script

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 面板标题栏
 *  PanelHeader.kt，删除了锁定/主页按钮，仅保留 Tab 切换。
 */
enum class NarutoPanelTab(val title: String) {
    TASKS("任务"),
    LOG("日志")
}

@Composable
fun PanelHeader(
    selectedTab: NarutoPanelTab = NarutoPanelTab.TASKS,
    onTabSelected: (NarutoPanelTab) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NarutoPanelTab.values().forEach { tab ->
            Text(
                text = tab.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selectedTab == tab)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onTabSelected(tab) }
            )
        }
    }
}