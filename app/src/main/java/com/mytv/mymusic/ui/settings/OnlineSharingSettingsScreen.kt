package com.mytv.mymusic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.share.normalizeShareServerUrl
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlinx.coroutines.launch

/**
 * 设置 › 在线分享：自建分享服务的地址与令牌。
 *
 * 地址只接受 HTTPS 的 origin（本地调试允许 `http://localhost`）；令牌按 origin 存进本机
 * 加密存储，不进同步文件，也不打日志。两端连同一个自托管实例，就是「同源复用」。
 */
@Composable
internal fun OnlineSharingSettingsScreen(
    state: MusicUiState,
    actions: MusicActions,
    onBack: () -> Unit,
    embedded: Boolean,
) {
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(20.dp)
    val savedUrl = state.share.serverUrl

    var draftUrl by remember(savedUrl) { mutableStateOf(savedUrl) }
    var urlError by remember(savedUrl) { mutableStateOf("") }
    var draftToken by remember { mutableStateOf("") }
    var tokenStatus by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    // null = 还在查；true/false = 当前地址上是否已经保存过令牌
    var tokenSaved by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(savedUrl) {
        tokenSaved = actions.hasShareToken()
        tokenStatus = ""
    }

    SettingsPage(SettingsSection.Sharing, embedded, onBack) {
        item(key = "intro") {
            SettingsNote(
                "听歌链接由你自己的分享服务提供：歌曲音频会上传一份副本，链接 24 小时无人打开后过期，" +
                    "你也可以在「我的分享」里撤销。令牌只保存在本机。",
            )
        }

        item(key = "address") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draftUrl,
                    onValueChange = { draftUrl = it; urlError = "" },
                    label = { Text("分享服务地址") },
                    placeholder = { Text("https://share.example.com") },
                    singleLine = true,
                    isError = urlError.isNotBlank(),
                    supportingText = if (urlError.isNotBlank()) {
                        { Text(urlError) }
                    } else {
                        { Text("填 origin 就行，不要带路径；本地调试可用 http://localhost:3000") }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val normalized = normalizeShareServerUrl(draftUrl)
                            if (normalized == null) {
                                urlError = "只接受 HTTPS 的 origin（本地调试可用 http://localhost）"
                            } else {
                                actions.setSharePrefs { it.copy(serverUrl = normalized) }
                                draftUrl = normalized
                                urlError = ""
                            }
                        },
                        shape = shape,
                        modifier = Modifier.height(52.dp).tvFocusRing(shape),
                    ) {
                        Text("保存地址")
                    }
                }
            }
        }

        item(key = "token") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draftToken,
                    onValueChange = { draftToken = it; tokenStatus = "" },
                    label = { Text("分享服务令牌") },
                    placeholder = { Text(if (tokenSaved == true) "已保存，留空表示不修改" else "服务端的管理令牌") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            saving = true
                            scope.launch {
                                actions.saveShareToken(draftToken)
                                draftToken = ""
                                tokenSaved = actions.hasShareToken()
                                tokenStatus = "令牌已加密保存在本机"
                                saving = false
                            }
                        },
                        enabled = !saving && savedUrl.isNotBlank() && draftToken.isNotBlank(),
                        shape = shape,
                        modifier = Modifier.height(52.dp).tvFocusRing(shape),
                    ) {
                        Text("保存令牌")
                    }
                    Button(
                        onClick = {
                            saving = true
                            scope.launch {
                                actions.removeShareToken()
                                tokenSaved = actions.hasShareToken()
                                tokenStatus = "已移除本机保存的令牌"
                                saving = false
                            }
                        },
                        enabled = !saving && savedUrl.isNotBlank() && tokenSaved == true,
                        shape = shape,
                        modifier = Modifier.height(52.dp).tvFocusRing(shape),
                    ) {
                        Text("移除令牌")
                    }
                }
                Text(
                    text = tokenStatus.ifBlank {
                        when (tokenSaved) {
                            null -> "正在检查本机令牌…"
                            true -> "当前地址已保存令牌"
                            false -> if (savedUrl.isBlank()) "先保存服务地址，再填令牌" else "当前地址还没有保存令牌"
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
