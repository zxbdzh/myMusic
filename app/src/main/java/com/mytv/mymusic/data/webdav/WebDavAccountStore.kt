package com.mytv.mymusic.data.webdav

import android.content.Context
import com.mytv.mymusic.data.sync.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// WebDavAccount / WebDavAccountInfo / WebDavPaths 已随核心层搬到 :core，包名不变；
// 这里只剩需要 SharedPreferences 与 Keystore（SecretStore）的本机存储实现。

/**
 * WebDAV 账号的本机存储：地址、用户名、目录、证书开关和记下的证书指纹放普通偏好（指纹是公开信息，
 * 只需要防改不需要保密，应用私有目录够了），密码交给 [SecretStore] 用 Keystore 加密。
 *
 * [usedForStorage]：本地歌上传还在用这个账号（往 WebDAV 排过上传、或在“设置 › 本地音乐”里单独配的）。
 * 这时断开同步只解除同步绑定，不删账号；要删在本地音乐设置里单独删。
 */
class WebDavAccountStore(context: Context, private val secrets: SecretStore) {
    private val preferences = context.getSharedPreferences("mymusic_webdav", Context.MODE_PRIVATE)
    private val _info = MutableStateFlow(readInfo())
    val info: StateFlow<WebDavAccountInfo?> = _info.asStateFlow()

    /**
     * 每次保存、清除都加一。只改密码时 [info] 前后相等、不会发新值，用账号的地方
     * （云端副本的取流客户端）靠它知道要重建、之前记下的“密码不对”要作废。
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @get:Synchronized
    val usedForStorage: Boolean get() = preferences.getBoolean(KEY_STORAGE, false)

    @Synchronized
    fun load(): WebDavAccount? {
        val url = preferences.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = secrets.secret(SECRET_PASSWORD) ?: return null
        return WebDavAccount(
            serverUrl = url,
            username = preferences.getString(KEY_USER, "").orEmpty(),
            password = password,
            directory = preferences.getString(KEY_DIR, WebDavPaths.DEFAULT_DIRECTORY).orEmpty(),
            trustSelfSigned = preferences.getBoolean(KEY_TRUST, false),
            pinnedCertificate = WebDavCertificate.decode(preferences.getString(KEY_CERT, null)),
        ).normalized()
    }

    @Synchronized
    fun save(account: WebDavAccount) {
        val normalized = account.normalized()
        secrets.saveSecret(SECRET_PASSWORD, normalized.password)
        preferences.edit()
            .putString(KEY_URL, normalized.serverUrl)
            .putString(KEY_USER, normalized.username)
            .putString(KEY_DIR, normalized.directory)
            .putBoolean(KEY_TRUST, normalized.trustSelfSigned)
            .apply {
                val certificate = normalized.pinnedCertificate
                if (certificate != null) putString(KEY_CERT, certificate.encode()) else remove(KEY_CERT)
            }
            .apply()
        _info.value = readInfo()
        _revision.value += 1
    }

    @Synchronized
    fun setUsedForStorage(value: Boolean) {
        preferences.edit().putBoolean(KEY_STORAGE, value).apply()
        _info.value = readInfo()
    }

    @Synchronized
    fun clear() {
        secrets.removeSecret(SECRET_PASSWORD)
        preferences.edit().clear().apply()
        _info.value = null
        _revision.value += 1
    }

    private fun readInfo(): WebDavAccountInfo? {
        val url = preferences.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        return WebDavAccountInfo(
            serverUrl = url,
            username = preferences.getString(KEY_USER, "").orEmpty(),
            directory = preferences.getString(KEY_DIR, WebDavPaths.DEFAULT_DIRECTORY).orEmpty(),
            trustSelfSigned = preferences.getBoolean(KEY_TRUST, false),
            hasPassword = secrets.hasSecret(SECRET_PASSWORD),
            usedForStorage = preferences.getBoolean(KEY_STORAGE, false),
            certificate = WebDavCertificate.decode(preferences.getString(KEY_CERT, null))
                ?.takeIf { preferences.getBoolean(KEY_TRUST, false) },
        )
    }

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_USER = "username"
        const val KEY_DIR = "directory"
        const val KEY_TRUST = "trust_self_signed"
        const val KEY_CERT = "pinned_certificate"
        const val KEY_STORAGE = "used_for_storage"
        const val SECRET_PASSWORD = "webdav_password"
    }
}
