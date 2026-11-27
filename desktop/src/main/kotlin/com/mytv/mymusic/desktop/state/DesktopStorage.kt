package com.mytv.mymusic.desktop.state

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mytv.mymusic.core.PlatformPaths
import com.mytv.mymusic.data.AppDatabase
import com.mytv.mymusic.data.SettingsStore
import com.mytv.mymusic.data.openAppDatabase
import com.mytv.mymusic.desktop.win.DpapiSecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.io.File

/**
 * 桌面共享曲库存储（Shared Library Store，落实 Q3）：
 *
 * 把 `:core` 里的三件持久化能力统一接到桌面端，与 Android 共享同一份 schema 与设置键：
 * 1. [database]：Room KMP（`mymusic.db`，18 张表 + 12 个迁移），存收藏、历史、自建歌单、本地曲库与同步基线；
 * 2. [settings]：DataStore Preferences（`mymusic.preferences_pb` + `lyric_memory.preferences_pb`），存外观/音效/播放设置；
 * 3. [secrets]：Win32 DPAPI 凭据保险库（`credentials.dpapi.json`），存各平台 Cookie 与云同步 Token。
 */
class DesktopStorage(
    val paths: PlatformPaths,
) : Closeable {

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val dbFile: File = File(paths.dataDir, "mymusic.db")
    val database: AppDatabase = openAppDatabase(dbFile)

    private val datastoreDir: File = File(paths.dataDir, "datastore").apply { mkdirs() }

    val settings: SettingsStore = SettingsStore(
        store = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(datastoreDir, "mymusic.preferences_pb") },
        ),
        lyricMemory = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(datastoreDir, "lyric_memory.preferences_pb") },
        ),
    )

    val secrets: DpapiSecretStore = DpapiSecretStore(paths.dataDir)

    override fun close() {
        runCatching { database.close() }
        storeScope.cancel()
    }
}
