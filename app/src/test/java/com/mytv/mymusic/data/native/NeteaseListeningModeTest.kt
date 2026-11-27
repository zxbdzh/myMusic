package com.mytv.mymusic.data.native

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseListeningModeTest {
    @Test
    fun includesHeartbeatAndRoamModes() = runBlocking {
        val modes = NetEaseProvider().listeningModes("")
        assertTrue(modes.any { it.id == "heartbeat" && it.name == "心动模式" })
        assertTrue(modes.any { it.id == "fm:DEFAULT" })
    }
}
