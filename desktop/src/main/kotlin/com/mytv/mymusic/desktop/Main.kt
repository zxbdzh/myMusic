package com.mytv.mymusic.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.mytv.mymusic.core.CoreModule
import com.mytv.mymusic.playback.vlc.VlcPlaybackEngine

/**
 * 桌面版入口（骨架）。
 *
 * 这一版刻意只有窗口骨架：真正的 164 个 ui 文件重写分批在 docs/pc/ARCHITECTURE.md
 * 「UI 重写分批」里排期，先跑通"模块能被装配起来、MSI 能打出来"这条链路，
 * 避免把 Android UI 直接照搬后再回头补分层。
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "myMusic · PC（骨架）",
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                SkeletonHome()
            }
        }
    }
}

@Composable
private fun SkeletonHome() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("myMusic 桌面版 · 架构骨架", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Android 端 (:app) 与本模块共用 :core 与 :playback-core，播放后端换成 libVLC。",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(8.dp))

        ModuleRow(":core", "领域模型 / 平台接口 / Provider 取流", CoreModule.CONTRACT_VERSION.toString())
        ModuleRow(":playback-core", "队列、时钟、节拍与交叉淡入状态机", "-")
        // 只实例化类型，不构造 native：factory 是 lazy 的，这里不会去加载 libvlc.dll
        ModuleRow(":playback-vlc", VlcPlaybackEngine::class.java.simpleName, "-")
        ModuleRow(":desktop", "Compose Multiplatform 窗口与导航", "-")

        Spacer(Modifier.height(8.dp))
        Text(
            "下一步：按 docs/pc/ARCHITECTURE.md 的分批计划，把 ui/ 的 164 个文件迁成桌面布局，" +
                "并把 data/native 的 43 个平台实现接进 :core。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ModuleRow(module: String, role: String, version: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(module, style = MaterialTheme.typography.titleMedium)
            Text(role, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(version, style = MaterialTheme.typography.labelMedium)
        }
    }
}
