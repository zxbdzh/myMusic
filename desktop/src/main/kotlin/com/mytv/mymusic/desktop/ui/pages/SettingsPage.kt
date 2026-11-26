package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.core.CoreModule
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.theme.Appearance
import com.mytv.mymusic.desktop.theme.PaletteKind
import com.mytv.mymusic.desktop.theme.SeedSwatches
import com.mytv.mymusic.desktop.theme.ThemeMode
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.ExpressiveChip
import com.mytv.mymusic.desktop.ui.components.PageHeader
import com.mytv.mymusic.desktop.ui.components.PagePadding

/**
 * 设置页（对应 Android `ui/settings/SettingsScreen.kt` 的分组分段列表）。
 *
 * 桌面窗口宽，不做"点进去一页"的多级结构：四组全部摊在一列滚动里，
 * 改完立即生效（即改即存，和 Android 的设置语义一致）。
 * 内容按骨架期能真正生效的项挑：外观（配色/纯黑/对比度）、播放后端、快捷键、关于。
 * 均衡器、音源管理、账号登录、同步这些 P2 再做——它们的入口在 Android 是需要登录态的。
 */
@Composable
fun SettingsPage(state: MusicUiState, store: MusicStore) {
    val appearance = state.appearance
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageHeader(title = "设置", subtitle = "即改即存，没有保存按钮") }

        item {
            SettingsCard("外观") {
                SettingRow("主题") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            ExpressiveChip(
                                label = mode.label,
                                selected = appearance.mode == mode,
                                onClick = { store.setAppearance(appearance.copy(mode = mode)) },
                            )
                        }
                    }
                }
                SettingRow("配色风格") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaletteKind.entries.forEach { kind ->
                            ExpressiveChip(
                                label = kind.label,
                                selected = appearance.palette == kind,
                                onClick = { store.setAppearance(appearance.copy(palette = kind)) },
                            )
                        }
                    }
                }
                SettingRow("种子色") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SeedSwatches.forEach { color ->
                            val selected = appearance.seed == color
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .then(
                                        if (selected) {
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable { store.setAppearance(appearance.copy(seed = color)) },
                            )
                        }
                    }
                }
                SettingRow("纯黑背景") {
                    Switch(
                        checked = appearance.amoled,
                        onCheckedChange = { store.setAppearance(appearance.copy(amoled = it)) },
                    )
                }
                SettingRow("减少动效") {
                    Switch(
                        checked = appearance.reduceMotion,
                        onCheckedChange = { store.setAppearance(appearance.copy(reduceMotion = it)) },
                    )
                }
                SettingRow("对比度") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = appearance.contrast,
                            onValueChange = { store.setAppearance(appearance.copy(contrast = it)) },
                            valueRange = -1f..1f,
                            modifier = Modifier.width(200.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = String.format("%.1f", appearance.contrast),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            SettingsCard("播放后端") {
                SettingRow("当前后端") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.engine.ready) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary,
                                ),
                        )
                        Text(
                            text = state.engine.backend,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (state.engine.ready) "就绪" else "未就绪",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SettingRow("说明") {
                    Text(
                        text = state.engine.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(460.dp),
                    )
                }
                SettingRow("音量") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = state.volume,
                            onValueChange = store::setVolume,
                            modifier = Modifier.width(200.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "${(state.volume * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            SettingsCard("快捷键") {
                ShortcutRow("空格", "播放 / 暂停")
                ShortcutRow("← / →", "快退 / 快进 10 秒")
                ShortcutRow("Esc", "收起播放页、歌词与海报墙")
                ShortcutRow("Ctrl+L", "打开全屏歌词")
                ShortcutRow("Ctrl+P", "打开海报墙")
            }
        }

        item {
            SettingsCard("关于") {
                SettingRow("版本") {
                    Text("myMusic 1.1.0（PC 骨架）", style = MaterialTheme.typography.bodyMedium)
                }
                SettingRow("模块") {
                    Text(
                        ":core 契约 v${CoreModule.CONTRACT_VERSION} · :playback-core · :playback-vlc · :desktop",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingRow("技术栈") {
                    Text(
                        "Kotlin 2.4.20 · Compose Multiplatform 1.12.2 · material-kolor 6.0.0 · vlcj 4.12.1",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingRow("已播放") {
                    Text(
                        text = state.current?.let { "《${it.title}》${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}" }
                            ?: "未播放",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 设置分组卡：标题 + 若干行。 */
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppIconView(AppIcon.Settings, MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        content()
    }
}

/** 一行设置：左边标签、右边控件；控件宽度由内容决定。 */
@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        content()
    }
}

@Composable
private fun ShortcutRow(keys: String, action: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = keys,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = action,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
