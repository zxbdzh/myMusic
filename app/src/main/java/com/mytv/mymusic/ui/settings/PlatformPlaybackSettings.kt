package com.mytv.mymusic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.aggregate.PlatformPlaybackPreference
import com.mytv.mymusic.aggregate.PlaybackMethod
import com.mytv.mymusic.aggregate.PlaybackMode
import com.mytv.mymusic.aggregate.playbackMethodsFor
import com.mytv.mymusic.data.lx.LxSources
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.ProviderIcon

@Composable
internal fun PlatformPlaybackSettingsScreen(
    state: MusicUiState,
    actions: MusicActions,
    onBack: () -> Unit,
    embedded: Boolean,
    onOpenLx: () -> Unit,
) {
    val platforms = state.sources { it.search }
    val lxRecords by LxSources.records.collectAsState()
    val cardConfigured = state.appleCardConfigured
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var editingCard by rememberSaveable { mutableStateOf(false) }
    SettingsPage(SettingsSection.PlatformPlayback, embedded, onBack) {
        platforms.forEachIndexed { index, provider ->
            item(key = provider.id, contentType = "settingRow") {
                val preference = state.aggregation.platformPlayback[provider.id] ?: PlatformPlaybackPreference()
                ListRow(
                    title = provider.name,
                    summary = if (preference.mode == PlaybackMode.Auto) "自动"
                        else "${platformModeLabel(preference.mode)} · ${preference.method.label}",
                    index = index,
                    count = platforms.size,
                    onClick = { editing = provider.id },
                    leading = { ProviderIcon(provider.id, provider.name) },
                )
            }
        }
    }
    platforms.firstOrNull { it.id == editing }?.let { provider ->
        val saved = state.aggregation.platformPlayback[provider.id] ?: PlatformPlaybackPreference()
        val methods = playbackMethodsFor(provider.id)
        var mode by rememberSaveable(provider.id) { mutableStateOf(saved.mode) }
        var method by rememberSaveable(provider.id) { mutableStateOf(saved.method.takeIf { it in methods } ?: methods.first()) }
        val lxConfigured = remember(provider.id, lxRecords) { LxSources.supports(provider.id) }
        if (!editingCard) AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(provider.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnectedToggleGroup(PlaybackMode.entries, mode, { mode = it }, ::platformModeLabel, Modifier.fillMaxWidth())
                    Text(
                        when (mode) {
                            PlaybackMode.Auto -> "按全局播放与换源设置播放。"
                            PlaybackMode.Priority -> "先用选中的方式。失败时先试本平台，再尝试换平台。"
                            PlaybackMode.Pinned -> "只用选中的方式播整曲。失败时停在这首歌。"
                        },
                    )
                    if (mode != PlaybackMode.Auto) {
                        ConnectedToggleGroup(methods, method, { method = it }, { it.label }, Modifier.fillMaxWidth())
                    }
                    if (provider.id == "apple") {
                        TextButton(onClick = { editingCard = true }) {
                            Text(if (cardConfigured) "管理聆澜卡密" else "填写聆澜卡密")
                        }
                    }
                    if (mode != PlaybackMode.Auto && method == PlaybackMethod.Lx && !lxConfigured) {
                        TextButton(onClick = {
                            actions.setPlatformPlayback(provider.id, PlatformPlaybackPreference(mode, method))
                            editing = null
                            onOpenLx()
                        }) { Text("配置支持此平台的洛雪脚本") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.setPlatformPlayback(provider.id, PlatformPlaybackPreference(mode, method))
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
    if (editingCard) {
        var card by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingCard = false },
            title = { Text("聆澜卡密") },
            text = {
                OutlinedTextField(
                    value = card,
                    onValueChange = { card = it },
                    label = { Text(if (cardConfigured) "输入新卡密以替换" else "输入卡密") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(enabled = card.isNotBlank(), onClick = {
                    actions.saveAppleCard(card.trim())
                    editingCard = false
                }) { Text("保存") }
            },
            dismissButton = {
                if (cardConfigured) {
                    TextButton(onClick = {
                        actions.saveAppleCard("")
                        editingCard = false
                    }) { Text("移除卡密") }
                } else {
                    TextButton(onClick = { editingCard = false }) { Text("取消") }
                }
            },
        )
    }
}

private fun platformModeLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.Auto -> "自动"
    PlaybackMode.Priority -> "优先"
    PlaybackMode.Pinned -> "固定"
}
