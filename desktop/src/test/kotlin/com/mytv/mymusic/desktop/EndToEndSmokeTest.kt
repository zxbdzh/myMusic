package com.mytv.mymusic.desktop

import com.mytv.mymusic.desktop.state.CoreMusicCatalog
import com.mytv.mymusic.desktop.state.DefaultMusicStore
import com.mytv.mymusic.desktop.state.DesktopPaths
import com.mytv.mymusic.desktop.state.SearchKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 端到端冒烟：真实网络 → 真实 provider → 搜索 → 播放，验证整条链没断。
 *
 * 与页面截图（`--screenshot`）的区别：截图只证明"画得出来"，这里证明**状态真的在动**
 * ——搜到的歌真能取流、取流后进度真的往前走。
 *
 * 环境没有可用网络时用 [assumeTrue] 跳过，而不是让 CI 红。音频后端不作要求：
 * 没有 libVLC 时走模拟输出，它按真实时间推进进度，同样能验证"播放态成立"。
 */
class EndToEndSmokeTest {

    private fun newStore(): DefaultMusicStore =
        DefaultMusicStore(
            catalog = CoreMusicCatalog(DesktopPaths()),
            // 冒烟测试验证搜索与状态推进链路，不依赖声卡与音频外链带宽
            audioFactory = com.mytv.mymusic.desktop.state.audio.AudioOutputs::simulated,
        )

    /** 轮询等待状态条件，超时返回最后一次读到的值。 */
    private suspend fun awaitState(
        store: DefaultMusicStore,
        timeoutMs: Long = 20_000,
        predicate: (com.mytv.mymusic.desktop.state.MusicUiState) -> Boolean,
    ): com.mytv.mymusic.desktop.state.MusicUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = store.state.value
            if (predicate(state)) return state
            delay(200)
        }
        return store.state.value
    }

    @Test
    fun `search reaches real providers and playing moves the progress`() = runBlocking {
        val store = newStore()
        try {
            store.bootstrap()
            // 平台列表来自 :core 的注册表；桌面没有 apple/spotify/mhub 的工厂，属预期缺席
            val providers = awaitState(store) { it.providers.isNotEmpty() }.providers
            assumeTrue("没有可用网络：平台列表为空", providers.isNotEmpty())
            println("[smoke] 平台 ${providers.size} 个：${providers.joinToString { it.id }}")

            // 逐平台打一遍，看清是"整条链断了"还是"某些平台不通"——聚合搜索会吞掉单平台失败
            val catalog = CoreMusicCatalog(DesktopPaths())
            val perProvider = providers.map { info ->
                val hits = runCatching { catalog.search(info.id, "晴天", SearchKind.Song) }.getOrElse {
                    println("[smoke] ${info.id} 抛异常：${it.message}")
                    emptyList()
                }
                println("[smoke] ${info.id}: ${hits.size} 条")
                info.id to hits
            }
            val working = perProvider.filter { it.second.isNotEmpty() }.toMap()
            println("[smoke] 可用平台：${working.keys}")
            assumeTrue("所有平台搜索都为空（网络或上游限制）", working.isNotEmpty())

            // 用聚合模式（空 providerId = 全部音源）跑正式链路
            store.selectProvider("")
            store.updateQuery("晴天")
            store.submitSearch()

            val searched = awaitState(store, timeoutMs = 45_000) { it.search.results.isNotEmpty() || it.search.error.isNotBlank() }
            println("[smoke] 聚合搜索 ${searched.search.results.size} 条，error=${searched.search.error}")

            // 聚合没结果时退回单平台结果，保证"播放链路"这段仍被验证
            val queue = searched.search.results.ifEmpty { working.values.flatten() }
            val first = queue.first()
            println("[smoke] 首条：${first.title} - ${first.artist}（${first.providerId}）")

            store.playTrack(first, queue)
            val playing = awaitState(store) { it.current != null && it.playing }
            assertTrue("播放态没有建立", playing.current != null)

            // 真实播放与模拟输出都会推进；等 3 秒足以看出差别（不给 CI 太长时间）
            delay(3_000)
            val advanced = store.state.value
            println("[smoke] 3 秒后 position=${advanced.positionMs}ms duration=${advanced.durationMs}ms backend=${advanced.engine.backend}")
            assertTrue("进度没有前进，播放链路可能断了", advanced.positionMs > 0L)
        } finally {
            store.shutdown()
        }
    }

    @Test
    fun `charts load real data from the default provider`() = runBlocking {
        val store = newStore()
        try {
            store.bootstrap()
            val loaded = awaitState(store, timeoutMs = 30_000) { it.charts.charts.isNotEmpty() || it.charts.error.isNotBlank() }
            println("[smoke] 榜单 ${loaded.charts.charts.size} 个，error=${loaded.charts.error}")
            assumeTrue("榜单为空（网络不可用）：${loaded.charts.error}", loaded.charts.charts.isNotEmpty())
            assertTrue("榜单必须有名字", loaded.charts.charts.all { it.name.isNotBlank() })
        } finally {
            store.shutdown()
        }
    }
}
