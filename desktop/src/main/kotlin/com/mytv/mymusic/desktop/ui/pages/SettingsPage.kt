package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.mytv.mymusic.desktop.ui.components.PcVerticalScrollbar
import com.mytv.mymusic.playback.core.CrossfadeMode
import com.mytv.mymusic.playback.core.EqPresets

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
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
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
            AudioEffectsCard(store)
        }

        item {
            ProviderCredentialsCard(state, store)
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
                    Text("myMusic 0.0.1（Windows PC 版）", style = MaterialTheme.typography.bodyMedium)
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
        if (listState.canScrollBackward || listState.canScrollForward) {
            PcVerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
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

/**
 * 音效与播放控制卡片：直接绑定 `:playback-vlc` 透出的均衡器、交叉淡化与定时关闭，
 * 改动实时生效；原生后端未就绪（或离屏无声模式）时明示不可用原因。
 */
@Composable
private fun AudioEffectsCard(store: MusicStore) {
    val eq = store.equalizer
    val xfade = store.crossfade
    val timer = store.sleepTimer
    SettingsCard("音效与播放控制") {
        if (eq == null || xfade == null || timer == null) {
            Text(
                text = "当前音频后端未提供均衡器、交叉淡化与定时关闭（需要 libVLC 运行库就绪）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsCard
        }

        val eqState by eq.state.collectAsState()
        val xfadeState by xfade.settings.collectAsState()
        val timerState by timer.state.collectAsState()

        SettingRow("10 段均衡器") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(
                    checked = eqState.enabled,
                    onCheckedChange = eq::setEnabled,
                )
                Text(
                    text = if (eqState.enabled) "已开启（${EqPresets.byId(eqState.presetId)?.label ?: "自定义"}）" else "已关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingRow("音效预设") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqPresets.all.forEach { preset ->
                    ExpressiveChip(
                        label = preset.label,
                        selected = eqState.presetId == preset.id,
                        onClick = {
                            if (!eqState.enabled) eq.setEnabled(true)
                            eq.selectPreset(preset.id)
                        },
                    )
                }
            }
        }

        SettingRow("自动接歌") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(
                    checked = xfadeState.enabled,
                    onCheckedChange = { enabled -> xfade.configure(xfadeState.copy(enabled = enabled)) },
                )
                CrossfadeMode.entries.forEach { mode ->
                    ExpressiveChip(
                        label = mode.label,
                        selected = xfadeState.mode == mode,
                        onClick = {
                            xfade.configure(xfadeState.copy(enabled = mode != CrossfadeMode.None, mode = mode))
                        },
                    )
                }
            }
        }
        if (xfadeState.enabled && xfadeState.mode != CrossfadeMode.None) {
            SettingRow("淡化时长") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = xfadeState.seconds.toFloat(),
                        onValueChange = { sec -> xfade.configure(xfadeState.copy(seconds = sec.toInt().coerceIn(1, 15))) },
                        valueRange = 1f..15f,
                        steps = 13,
                        modifier = Modifier.width(200.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${xfadeState.seconds} 秒",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingRow("定时关闭") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpressiveChip(
                    label = "关闭",
                    selected = !timerState.active,
                    onClick = timer::cancel,
                )
                listOf(15, 30, 45, 60).forEach { mins ->
                    val selected = timerState.active && !timerState.endOfTrack && timerState.totalMs == mins * 60_000L
                    ExpressiveChip(
                        label = "${mins} 分钟",
                        selected = selected,
                        onClick = { timer.start(mins) },
                    )
                }
                ExpressiveChip(
                    label = "播完本曲",
                    selected = timerState.active && timerState.endOfTrack,
                    onClick = timer::stopAfterCurrent,
                )
            }
        }
    }
}

/**
 * 音源账号凭据（DPAPI 加密保险库，落实 Q5）：
 * 选中平台后可粘贴 Cookie 或一键清除，保存后经 Win32 DPAPI 加密写入 `credentials.dpapi.json`，
 * 绝不以明文形式留在磁盘。
 */
@Composable
private fun ProviderCredentialsCard(state: MusicUiState, store: MusicStore) {
    val providers = state.providers.ifEmpty { return }
    var selectedId by remember(providers) { mutableStateOf(providers.first().id) }
    var cookieInput by remember(selectedId) { mutableStateOf(store.getProviderCookie(selectedId)) }
    val hasSaved = store.getProviderCookie(selectedId).isNotBlank()

    SettingsCard("音源账号与凭据（DPAPI 加密）") {
        SettingRow("选择平台") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                providers.take(7).forEach { p ->
                    val saved = store.getProviderCookie(p.id).isNotBlank()
                    ExpressiveChip(
                        label = if (saved) "${p.name} ✓" else p.name,
                        selected = selectedId == p.id,
                        onClick = { selectedId = p.id },
                    )
                }
            }
        }
        SettingRow("Cookie 凭据") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (cookieInput.isEmpty()) {
                        Text(
                            text = "粘贴该平台 Cookie 后点保存（写入本机 Windows DPAPI 加密库）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    BasicTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ExpressiveChip(
                    label = "保存",
                    selected = true,
                    onClick = { store.setProviderCookie(selectedId, cookieInput.trim()) },
                )
                if (hasSaved || cookieInput.isNotBlank()) {
                    ExpressiveChip(
                        label = "清除",
                        selected = false,
                        onClick = {
                            cookieInput = ""
                            store.setProviderCookie(selectedId, "")
                        },
                    )
                }
            }
        }
    }
}
