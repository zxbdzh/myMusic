package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.ExpressiveChip

/** 音源 chip 行：横向滚动，窗口再窄也不会把右侧操作挤出去。 */
@Composable
fun ProviderChips(state: MusicUiState, store: MusicStore, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.providers.forEach { provider ->
            ExpressiveChip(
                label = provider.name,
                selected = state.providerId == provider.id,
                onClick = { store.selectProvider(provider.id) },
            )
        }
    }
}

/** "播放全部"：桌面上的主要动作按钮，放在每个详情页头部。 */
@Composable
fun PlayAllButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "播放全部") {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(40.dp)) {
        AppIconView(AppIcon.Play, MaterialTheme.colorScheme.onSecondaryContainer, size = 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** 行内小标题 + 数值，统计卡和详情页都用。 */
@Composable
fun MetricRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 给 RowScope 里插入空白的小工具（避免到处写 Spacer(Modifier.width(…))）。 */
@Composable
fun RowScope.SmallGap(size: Dp = 6.dp) {
    Spacer(Modifier.width(size))
}
