package com.mytv.mymusic.data.native

/**
 * Temari（Apple FairPlay SAMPLE-AES 本地解密）的 JNI 封装。
 *
 * 模板句柄是 Rust 侧 `Box<Template>` 的裸指针，只读，可并发解密；
 * 由 [AppleCardProvider] 在进程存活期间持有，不逐首释放。
 */
internal object TemariNative {
    /** arm64/armv7/x86_64 都打包了 .so；加载失败时只让这个源不可用，不影响其他平台。 */
    val available: Boolean

    init {
        // 必须在 object 初始化时加载：直接调 create 时 lazy 不会触发，JNI 方法就没绑定
        available = runCatching { System.loadLibrary("temari") }.isSuccess
    }

    /** 传入 `/api/music/key` 返回的模板 JSON，返回句柄；失败返回 0。 */
    external fun create(json: ByteArray): Long

    /** 解密一个 sample（16 字节对齐部分解密，尾部原样保留）。 */
    external fun decrypt(handle: Long, input: ByteArray): ByteArray

    external fun destroy(handle: Long)
}
