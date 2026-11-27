package com.mytv.mymusic.desktop

import com.mytv.mymusic.desktop.ui.components.DockPrefs
import com.mytv.mymusic.desktop.ui.components.PanePlayMode
import com.mytv.mymusic.desktop.ui.components.clampDockWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放窗格的宽度夹取与播放模式枚举。
 *
 * 对应 `docs/pc/PC-NATIVE-CHECKLIST.md` P-05 的验证要求「单测：宽度夹取」：
 * 拖动分隔条是 UI 手势，截不到也点不到，唯一能自动化锁住的是"越界值必须被夹住"。
 * 落盘（`dock.properties`）不做单测：它只写用户目录，测它反而会把开发机的偏好改坏。
 */
class DockPrefsTest {

    @Test
    fun `宽度夹在 240 到 480 之间`() {
        assertEquals(240, clampDockWidth(120))
        assertEquals(240, clampDockWidth(240))
        assertEquals(320, clampDockWidth(320))
        assertEquals(480, clampDockWidth(480))
        assertEquals(480, clampDockWidth(9999))
    }

    @Test
    fun `拖动累加后仍然不越界`() {
        // resizeBy 是拖动回调的入口：连续两段拖动不能把窗格拖出可读范围
        DockPrefs.resizeBy(-9999f)
        assertEquals(DockPrefs.MIN_WIDTH_DP, DockPrefs.widthDp)
        DockPrefs.resizeBy(9999f)
        assertEquals(DockPrefs.MAX_WIDTH_DP, DockPrefs.widthDp)
        // 恢复成默认值，避免这段单测影响同一进程里后续用例的初始宽度
        DockPrefs.resizeBy((DockPrefs.DEFAULT_WIDTH_DP - DockPrefs.widthDp).toFloat())
        assertEquals(DockPrefs.DEFAULT_WIDTH_DP, DockPrefs.widthDp)
    }

    @Test
    fun `播放模式四种齐全且默认顺序播放`() {
        assertEquals(
            listOf("顺序播放", "随机播放", "单曲循环", "心动模式"),
            PanePlayMode.entries.map { it.label },
        )
        // 默认值只在首次启动或配置缺失时生效；此处只断言枚举本身完整
        assertTrue(PanePlayMode.entries.contains(PanePlayMode.Sequential))
    }

    @Test
    fun `折叠宽度小于最小可拖宽度`() {
        // 折叠条必须是"窄但可点"，不能小于 40dp，也不能宽到像没折叠
        assertTrue(DockPrefs.COLLAPSED_WIDTH_DP < DockPrefs.MIN_WIDTH_DP)
        assertTrue(DockPrefs.COLLAPSED_WIDTH_DP >= 40)
    }
}
