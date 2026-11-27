package com.mytv.mymusic.desktop.win

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/**
 * Windows 凭据保险库（Credential Vault，落实 Q5）。
 *
 * 通过 JNA 直调 Win32 `Crypt32.CryptProtectData` / `CryptUnprotectData`（DPAPI）
 * 将各音源 Cookie 与云同步 Token 绑定到当前 Windows 用户加密落盘，零外部依赖；
 * 非 Windows 环境下退化为 UTF-8 Base64（保证跨平台单测可跑，不阻断 CI）。
 *
 * 兼容升级：首次加载若发现早期明文 `platform-cookies.txt`，自动迁移进 DPAPI 密文库后删除明文文件。
 */
class DpapiSecretStore(
    private val dataDir: File,
    private val useDpapi: Boolean = isWindows(),
) {
    private val vaultFile = File(dataDir, VAULT_FILE_NAME)
    private val legacyCookieFile = File(dataDir, LEGACY_COOKIE_FILE)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private val entries: MutableMap<String, String> = mutableMapOf()

    init {
        loadAndMigrate()
    }

    fun get(key: String): String = synchronized(lock) { entries[key].orEmpty() }

    fun getCookie(providerId: String): String = get(cookieKey(providerId))

    fun put(key: String, value: String) {
        if (key.isBlank()) return
        synchronized(lock) {
            if (value.isBlank()) entries.remove(key) else entries[key] = value
            persistLocked()
        }
    }

    fun putCookie(providerId: String, cookie: String) {
        if (providerId.isBlank()) return
        put(cookieKey(providerId), cookie)
    }

    fun allCookies(): Map<String, String> = synchronized(lock) {
        entries.entries
            .filter { it.key.startsWith(COOKIE_PREFIX) }
            .associate { it.key.removePrefix(COOKIE_PREFIX) to it.value }
    }

    private fun loadAndMigrate() = synchronized(lock) {
        if (vaultFile.isFile) {
            val rawMap = runCatching {
                json.decodeFromString<Map<String, String>>(vaultFile.readText(Charsets.UTF_8))
            }.getOrDefault(emptyMap())
            rawMap.forEach { (k, encoded) ->
                decrypt(encoded)?.let { plain -> entries[k] = plain }
            }
        }
        // 自动迁移早期明文 platform-cookies.txt，避免老用户丢登录态同时消除明文残留
        if (legacyCookieFile.isFile) {
            var migrated = false
            runCatching {
                legacyCookieFile.readLines(Charsets.UTF_8).forEach { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) {
                        val provider = line.substring(0, idx).trim()
                        val value = line.substring(idx + 1).trim()
                        if (provider.isNotEmpty() && value.isNotEmpty() && !entries.containsKey(cookieKey(provider))) {
                            entries[cookieKey(provider)] = value
                            migrated = true
                        }
                    }
                }
            }
            if (migrated) persistLocked()
            runCatching { legacyCookieFile.delete() }
        }
    }

    private fun persistLocked() {
        runCatching {
            dataDir.mkdirs()
            val encoded = entries.mapValues { (_, plain) -> encrypt(plain) }
            vaultFile.writeText(json.encodeToString(encoded), Charsets.UTF_8)
        }
    }

    private fun encrypt(plain: String): String {
        val raw = plain.toByteArray(Charsets.UTF_8)
        if (!useDpapi) {
            return PLAIN_PREFIX + Base64.getEncoder().encodeToString(raw)
        }
        val protectedBytes = WinDpapi.protect(raw)
        return if (protectedBytes != null) {
            DPAPI_PREFIX + Base64.getEncoder().encodeToString(protectedBytes)
        } else {
            PLAIN_PREFIX + Base64.getEncoder().encodeToString(raw)
        }
    }

    private fun decrypt(encoded: String): String? = runCatching {
        when {
            encoded.startsWith(DPAPI_PREFIX) -> {
                val cipher = Base64.getDecoder().decode(encoded.removePrefix(DPAPI_PREFIX))
                val plain = WinDpapi.unprotect(cipher) ?: return@runCatching null
                plain.toString(Charsets.UTF_8)
            }
            encoded.startsWith(PLAIN_PREFIX) -> {
                Base64.getDecoder().decode(encoded.removePrefix(PLAIN_PREFIX)).toString(Charsets.UTF_8)
            }
            else -> null
        }
    }.getOrNull()

    companion object {
        private const val VAULT_FILE_NAME = "credentials.dpapi.json"
        private const val LEGACY_COOKIE_FILE = "platform-cookies.txt"
        private const val COOKIE_PREFIX = "cookie:"
        private const val DPAPI_PREFIX = "dpapi:"
        private const val PLAIN_PREFIX = "b64:"

        fun cookieKey(providerId: String): String = "$COOKIE_PREFIX$providerId"

        private fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}

/**
 * 极简 Win32 DPAPI 绑定（零 jna-platform 额外依赖）。
 * 仅使用 `CRYPTPROTECT_UI_FORBIDDEN`（0x1），绝不弹系统密码框抢焦点。
 */
private object WinDpapi {
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    @Structure.FieldOrder("cbData", "pbData")
    class DataBlob : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null
    }

    private interface Crypt32 : Library {
        fun CryptProtectData(
            pDataIn: DataBlob,
            szDataDescr: String?,
            pOptionalEntropy: DataBlob?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DataBlob,
        ): Boolean

        fun CryptUnprotectData(
            pDataIn: DataBlob,
            ppszDataDescr: Pointer?,
            pOptionalEntropy: DataBlob?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DataBlob,
        ): Boolean

        companion object {
            val INSTANCE: Crypt32 by lazy { Native.load("Crypt32", Crypt32::class.java) }
        }
    }

    private interface Kernel32 : Library {
        fun LocalFree(hMem: Pointer?): Pointer?

        companion object {
            val INSTANCE: Kernel32 by lazy { Native.load("Kernel32", Kernel32::class.java) }
        }
    }

    fun protect(plain: ByteArray): ByteArray? = runCatching {
        val mem = if (plain.isEmpty()) Memory(1) else Memory(plain.size.toLong()).apply { write(0, plain, 0, plain.size) }
        val inBlob = DataBlob().apply {
            cbData = plain.size
            pbData = mem
        }
        val outBlob = DataBlob()
        val ok = Crypt32.INSTANCE.CryptProtectData(
            inBlob,
            "myMusic",
            null,
            null,
            null,
            CRYPTPROTECT_UI_FORBIDDEN,
            outBlob,
        )
        if (!ok || outBlob.pbData == null) return@runCatching null
        try {
            outBlob.pbData!!.getByteArray(0, outBlob.cbData)
        } finally {
            Kernel32.INSTANCE.LocalFree(outBlob.pbData)
        }
    }.getOrNull()

    fun unprotect(cipher: ByteArray): ByteArray? = runCatching {
        if (cipher.isEmpty()) return@runCatching ByteArray(0)
        val mem = Memory(cipher.size.toLong()).apply { write(0, cipher, 0, cipher.size) }
        val inBlob = DataBlob().apply {
            cbData = cipher.size
            pbData = mem
        }
        val outBlob = DataBlob()
        val ok = Crypt32.INSTANCE.CryptUnprotectData(
            inBlob,
            null,
            null,
            null,
            null,
            CRYPTPROTECT_UI_FORBIDDEN,
            outBlob,
        )
        if (!ok || outBlob.pbData == null) return@runCatching null
        try {
            outBlob.pbData!!.getByteArray(0, outBlob.cbData)
        } finally {
            Kernel32.INSTANCE.LocalFree(outBlob.pbData)
        }
    }.getOrNull()
}
