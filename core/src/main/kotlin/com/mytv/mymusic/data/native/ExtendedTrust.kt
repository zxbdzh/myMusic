package com.mytv.mymusic.data.native

import okhttp3.OkHttpClient

/**
 * 需要附加信任锚的域名与其专用客户端。
 *
 * 原来 `NativeHttp` 直接引 `MHubTls`（:app，里面挂着 Android 的系统证书库适配），
 * 搬进 :core 后改成注入：Android 侧启动时装上 [MHubTls] 的那份客户端与域名表，
 * 桌面端在实现自己的 TLS 附加信任锚时同样装进来；没装就是普通客户端，行为与
 * 「这些域名不在表里」完全一致。
 */
object ExtendedTrustHosts {
    /** 需要走 [client] 的域名。 */
    @Volatile
    var hosts: Set<String> = emptySet()

    /** 带附加信任锚的客户端；为空时这些域名也走默认客户端。 */
    @Volatile
    var client: OkHttpClient? = null

    /** 平台层安装：[MHubTls.hosts] + `MHubTls.client`。 */
    fun install(hosts: Set<String>, client: OkHttpClient) {
        this.hosts = hosts
        this.client = client
    }
}
