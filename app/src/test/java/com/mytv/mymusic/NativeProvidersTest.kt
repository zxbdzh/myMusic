package com.mytv.mymusic

import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.native.BaseNativeProvider
import com.mytv.mymusic.data.native.NativeProviders
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeProvidersTest {
    companion object {
        /**
         * Apple / Spotify / MHub 三个实现留在 :app（Widevine、android.util.Base64、附加信任锚），
         * :core 的 NativeProviders 只留工厂位。单元测试跑不到 Application.onCreate，
         * 这里按同样的顺序补登记，保证 16 个内置平台齐全、与搬迁前一致。
         */
        @JvmStatic
        @org.junit.BeforeClass
        fun installAppProviders() {
            NativeProviders.registerFactory("apple") { com.mytv.mymusic.data.native.AppleProvider() }
            NativeProviders.registerFactory("spotify") { com.mytv.mymusic.data.native.SpotifyProvider() }
            NativeProviders.registerFactory("mhub") { com.mytv.mymusic.data.native.MHubProvider() }
        }
    }
    @Test
    fun allNativeProvidersAreRegistered() {
        val expected = setOf(
            "netease",
            "qq",
            "kugou",
            "kuwo",
            "migu",
            "qianqian",
            "soda",
            "fivesing",
            "jamendo",
            "joox",
            "bilibili",
            "apple",
            "spotify",
            "youtubemusic",
            "mhub",
            "yunting",
        )
        val providers = NativeProviders.all()
        assertEquals(expected, providers.map { it.info.id }.toSet())
        assertEquals(expected.size, providers.size)
    }

    @Test
    fun playlistWriteCapabilitiesMatchImplementedProviders() {
        val write = NativeProviders.all().filter { it.info.playlistWrite }.map { it.info.id }.toSet()
        assertEquals(setOf("netease", "qq", "kugou", "mhub", "soda"), write)
        val reorder = NativeProviders.all().filter { it.info.playlistReorder }.map { it.info.id }.toSet()
        assertEquals(setOf("qq", "kugou", "mhub", "soda"), reorder)
    }

    @Test
    fun dynamicProvidersCanBeReplacedAndRemoved() {
        val id = "subsonic:test"
        val first = object : BaseNativeProvider(ProviderInfo(id = id, name = "first")) {}
        val second = object : BaseNativeProvider(ProviderInfo(id = id, name = "second")) {}
        try {
            NativeProviders.register(first)
            assertSame(first, NativeProviders.get(id))
            assertTrue(NativeProviders.infos().any { it.id == id })
            NativeProviders.register(second)
            assertSame(second, NativeProviders.get(id))
            assertEquals(1, NativeProviders.all().count { it.info.id == id })
            NativeProviders.unregister(id)
            assertFalse(NativeProviders.all().any { it.info.id == id })
        } finally {
            NativeProviders.unregister(id)
        }
    }

    @Test
    fun dynamicProviderCannotShadowStaticSource() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeProviders.register(object : BaseNativeProvider(ProviderInfo(id = "netease")) {})
        }
    }

    @Test
    fun collectCapabilitiesMatchImplementedProviders() {
        // 目前只有汽水有账号侧收藏（「我喜欢的音乐」）
        val collect = NativeProviders.all().filter { it.supportsCollect() }.map { it.info.id }.toSet()
        assertEquals(setOf("soda"), collect)
    }
}
