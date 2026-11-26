package com.mytv.mymusic.desktop.win

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 单实例互斥 + 回环唤醒。
 *
 * 为什么不用 mock 掉文件锁：`FileLock` 的语义（进程退出自动释放、同 JVM 重入抛
 * `OverlappingFileLockException`）是本实现的核心依赖，mock 掉就什么都没验证。
 * 这里用临时目录做两次真实 acquire。
 */
class SingleInstanceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `第一个实例拿到锁，第二个退化为副实例`() {
        val dir = temp.newFolder("instance")
        val primary = SingleInstance.acquire(dir)
        try {
            assertTrue("首个实例应当是主实例", primary.isPrimary)

            val secondary = SingleInstance.acquire(dir)
            assertFalse("同 JVM 第二次 acquire 必须退化为副实例", secondary.isPrimary)
            secondary.close()
        } finally {
            primary.close()
        }

        // 主实例释放后，锁与端口文件都应清理干净
        assertFalse(dir.resolve("instance.lock").exists())
        assertFalse(dir.resolve("instance.port").exists())
    }

    @Test
    fun `释放后可以重新成为主实例`() {
        val dir = temp.newFolder("reuse")
        SingleInstance.acquire(dir).close()
        val again = SingleInstance.acquire(dir)
        try {
            assertTrue(again.isPrimary)
        } finally {
            again.close()
        }
    }

    @Test
    fun `副实例通过回环端口唤醒主实例`() {
        val dir = temp.newFolder("wake")
        val primary = SingleInstance.acquire(dir)
        val activated = CountDownLatch(1)
        primary.onActivate { activated.countDown() }
        try {
            val secondary = SingleInstance.acquire(dir)
            assertTrue("端口文件存在时应能送达", secondary.signalExistingInstance())
            secondary.close()

            assertTrue(
                "主实例应在 1.5s 内收到唤醒",
                activated.await(1500, TimeUnit.MILLISECONDS),
            )
        } finally {
            primary.close()
        }
    }

    @Test
    fun `端口文件缺失时送达失败但不抛异常`() {
        val dir = temp.newFolder("missing")
        val primary = SingleInstance.acquire(dir)
        try {
            val secondary = SingleInstance.acquire(dir)
            dir.resolve("instance.port").delete()
            assertFalse(secondary.signalExistingInstance())
            secondary.close()
        } finally {
            primary.close()
        }
    }

    @Test
    fun `非 Windows 平台整体跳过`() {
        assumeTrue("单实例实现依赖 Windows 文件锁语义", Win32.isWindows)
        assertTrue(Win32.isWindows)
    }
}
