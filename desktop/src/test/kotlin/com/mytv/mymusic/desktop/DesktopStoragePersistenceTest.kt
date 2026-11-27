package com.mytv.mymusic.desktop

import androidx.compose.ui.graphics.Color
import com.mytv.mymusic.desktop.state.CoreMusicCatalog
import com.mytv.mymusic.desktop.state.DesktopPaths
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.desktop.win.DpapiSecretStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 共享曲库存储（Room + DataStore）与 Win32 DPAPI 凭据库持久化闭环测试。
 *
 * 验证的核心契约（Q3 + Q5）：
 * 1. DPAPI 加密落盘（文件内不含明文 Cookie）且可完整解密，旧明文 `platform-cookies.txt` 自动迁移并删除；
 * 2. 收藏（含墓碑）、最近播放、自建歌单 CRUD（新建/加歌/重命名/删歌/软删）写入 `mymusic.db`，关闭重开后 100% 保留；
 * 3. `SettingsStore` 外观设置写入 DataStore，关闭重开后原样读回。
 */
class DesktopStoragePersistenceTest {

    private fun tempRoot(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "mymusic-persist-$tag-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun sampleTrack(id: String, title: String) = Track(
        id = "qq:$id",
        title = title,
        artist = "周杰伦",
        album = "叶惠美",
        durationMs = 269_000L,
        providerId = "qq",
        providerName = "QQ 音乐",
        coverUrl = "https://example.invalid/cover.jpg",
    )

    @Test
    fun `dpapi secret store encrypts credentials and migrates legacy cookie file`() {
        val dir = tempRoot("dpapi")
        try {
            val legacy = File(dir, "platform-cookies.txt")
            legacy.writeText("netease=MUSIC_U=secret_token_123\nqq=uin=998877\n", Charsets.UTF_8)

            val first = DpapiSecretStore(dir)
            assertEquals("MUSIC_U=secret_token_123", first.getCookie("netease"))
            assertEquals("uin=998877", first.getCookie("qq"))
            assertFalse("迁移完成后明文 Cookie 文件必须被删除", legacy.exists())

            first.putCookie("kugou", "token=kg_xyz")
            val vaultText = File(dir, "credentials.dpapi.json").readText(Charsets.UTF_8)
            assertFalse("凭据文件内不允许以明文存放 Cookie", vaultText.contains("secret_token_123"))
            assertFalse("凭据文件内不允许以明文存放 Cookie", vaultText.contains("kg_xyz"))

            // 模拟进程重启，重新打开保险库验证解密往返
            val reopened = DpapiSecretStore(dir)
            assertEquals("MUSIC_U=secret_token_123", reopened.getCookie("netease"))
            assertEquals("uin=998877", reopened.getCookie("qq"))
            assertEquals("token=kg_xyz", reopened.getCookie("kugou"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `favorites history and user playlists survive Room database reopen`() = runBlocking {
        val root = tempRoot("room")
        try {
            val paths = DesktopPaths(overrideRoot = root)
            assertTrue("传 overrideRoot 时应为独立便携目录", paths.isPortable)

            val t1 = sampleTrack("001", "晴天")
            val t2 = sampleTrack("002", "七里香")

            val first = CoreMusicCatalog(paths)
            try {
                // 收藏与历史
                assertTrue(first.toggleFavoritePersisted(t1))
                assertTrue(first.toggleFavoritePersisted(t2))
                // 取消 t2，检查墓碑生效
                assertFalse(first.toggleFavoritePersisted(t2))
                first.recordHistory(t1)
                first.recordHistory(t2)

                // 自建歌单 CRUD
                val pl = first.createUserPlaylist("深夜写代码")
                assertNotNull(pl)
                assertTrue(first.addTrackToPlaylist(pl!!.id, t1))
                assertTrue(first.addTrackToPlaylist(pl.id, t2))
                assertTrue(first.renameUserPlaylist(pl.id, "工作 BGM"))
                assertTrue(first.removeTrackFromPlaylist(pl.id, t2.id))

                // SettingsStore 写入
                first.settingsStore.theme("dark")
                first.settingsStore.amoled(true)
                first.settingsStore.customSeed(Color(0xFF3A5BA0).value.toInt())

                // 另建一个歌单并软删除
                val doomed = first.createUserPlaylist("临时歌单")!!
                assertTrue(first.deleteUserPlaylist(doomed.id))
            } finally {
                first.close()
            }

            // 重新打开同一个目录，断言所有数据完整保留
            val second = CoreMusicCatalog(paths)
            try {
                val lib = second.library()
                assertEquals(listOf("晴天"), lib.favorites.map { it.title })
                assertEquals(2, lib.history.size)
                assertEquals(1, lib.playlists.size)
                val pl = lib.playlists.first()
                assertEquals("工作 BGM", pl.name)
                assertEquals(1, pl.trackCount)

                val plTracks = second.playlistTracks(pl)
                assertEquals(listOf("晴天"), plTracks.map { it.title })

                // 校验取消收藏的墓碑存在（与 Android 云同步协议一致）
                val tombstones = second.storage.database.dao().tombstones(CoreMusicCatalog.FAVORITE_BUCKET)
                assertTrue(tombstones.any { it.key == t2.id })

                val savedSettings = second.settingsStore.state.first()
                assertEquals("dark", savedSettings.theme)
                assertTrue(savedSettings.amoled)
            } finally {
                second.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
