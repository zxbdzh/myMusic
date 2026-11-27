package com.mytv.mymusic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytv.mymusic.data.native.TemariNative
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真机/模拟器上确认 libtemari.so 能按当前 ABI 加载。 */
@RunWith(AndroidJUnit4::class)
class TemariJniTest {
    @Test
    fun loadsOnDevice() {
        assertTrue("libtemari.so 未加载", TemariNative.available)
    }
}
