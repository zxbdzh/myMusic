package com.mytv.mymusic.ui.player.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.data.share.ShareMode
import com.mytv.mymusic.data.share.SharePublishPhase
import com.mytv.mymusic.data.share.SharePublishState
import com.mytv.mymusic.ui.components.ConnectedToggleGroup

/**
 * 分享页的「在线听歌」区块：模式（整曲 / 片段）、发布状态与听歌链接。
 *
 * 没配置分享服务时整块不出现（[configured] 为 false），不在分享页催用户去配置。
 * 状态词与「我的分享」一致：上传中 / 准备中 / 可收听 / 失败。
 */
@Composable
internal fun ShareOnlineSection(
    publish: SharePublishState,
    configured: Boolean,
    clipAvailable: Boolean,
    durationLabel: String,
    mode: ShareMode,
    onMode: (ShareMode) -> Unit,
    onPublish: () -> Unit,
    onCopyLink: (String) -> Unit,
) {
    if (!configured) return
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = scheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("在线听歌", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(publish.phase.label, fontSize = 12.sp, color = scheme.onSurfaceVariant)
            }

            ConnectedToggleGroup(
                // 片段模式需要可靠时间戳：不可用时只留整曲，并把原因写在下面
                options = if (clipAvailable) listOf(ShareMode.Full, ShareMode.Clip) else listOf(ShareMode.Full),
                selected = mode,
                onSelect = onMode,
                label = { if (it == ShareMode.Full) "整曲" else "片段" },
                compact = true,
            )

            when (publish.phase) {
                SharePublishPhase.Uploading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("上传中… 离开这一页也会继续", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                }

                SharePublishPhase.Processing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("服务端准备中…", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                }

                SharePublishPhase.Ready -> {
                    Text(publish.url, fontSize = 12.sp, color = scheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onCopyLink(publish.url) }) { Text("复制链接") }
                    }
                    Text(durationLabel, fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
                }

                SharePublishPhase.Failed -> {
                    Text(publish.error.ifBlank { "上传失败" }, fontSize = 12.sp, color = scheme.error)
                    Button(onClick = onPublish) { Text("重试") }
                }

                else -> {
                    Text(durationLabel, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    Button(onClick = onPublish) {
                        Text(if (publish.phase == SharePublishPhase.Idle) "生成听歌链接" else "重新生成听歌链接")
                    }
                }
            }

            if (!clipAvailable) {
                Text("这首歌词没有时间轴，只能整曲分享。", fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
            }
        }
    }
}
