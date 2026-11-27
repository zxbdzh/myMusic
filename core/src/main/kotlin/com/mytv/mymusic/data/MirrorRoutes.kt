package com.mytv.mymusic.data

import kotlinx.serialization.Serializable

/**
 * 镜像线路的纯数据层：枚举、路线形状、排序档位。
 * 从 app 侧 GithubMirrorRoutes.kt 拆出来（SettingsStore.githubMirror 需要），随 t13 的 :core 搬迁进来；
 * 带网络/SharedPreferences 的 GithubMirrors（远程清单合并与刷新）留在 :app——它要 Context。
 */
/** 镜像的工作方式：[PREFIX] 把地址拼在原 URL 前面，[RAW_HOST] 只替换 raw 的主机名。 */
enum class MirrorKind { PREFIX, RAW_HOST }

/**
 * 一条可用的镜像线路。内置线路与 gitee 远程清单合并后都是这个形状，
 * 设置页、检查更新测速、下载回退共用同一份。
 */
data class MirrorRoute(
    val id: String,
    val label: String,
    /** [MirrorKind.PREFIX] 是拼在原 URL 前的前缀；[MirrorKind.RAW_HOST] 只存主机名；直连为空串。 */
    val prefix: String,
    val kind: MirrorKind,
    /** 是否转发 `api.github.com`；不转发的线路只能下载资产与 raw 文件。 */
    val proxiesApi: Boolean,
    /** 排序档位，见 [TIER_FAST] 一族常量。 */
    val tier: Int,
    val enabled: Boolean = true,
) {
    /**
     * 为 [target] 拼出这条线路的 URL。线路不适用时返回 null——
     * 例如 raw_host 线路遇到 Release 资产地址，拼出来也是 404，不如直接不生成。
     */
    fun urlFor(target: String): String? = when (kind) {
        MirrorKind.PREFIX ->
            if (prefix.isEmpty()) target else prefix.trimEnd('/') + "/" + target
        MirrorKind.RAW_HOST ->
            if (target.startsWith(RAW_URL_PREFIX)) {
                target.replaceFirst(RAW_URL_PREFIX, "https://${prefix.trimEnd('/')}/")
            } else {
                null
            }
    }
}

/** 只服务 raw 的镜像就是替换这个主机。 */
private const val RAW_URL_PREFIX = "https://raw.githubusercontent.com/"

/**
 * 线路排序档位。铁律：镜像(0) → 国内直连(1) → 慢镜像(2) → 只服务 raw 的镜像(3)。
 * 顶层常量而不是枚举伴生对象里的，避免枚举条目依赖伴生对象的初始化顺序。
 */
const val TIER_MODE = -1
const val TIER_FAST = 0
const val TIER_DIRECT = 1
const val TIER_SLOW = 2
const val TIER_RAW_ONLY = 3

/** gitee `my-mirror` 仓库的清单结构，字段与 [MirrorRoute] 一一对应（同样由 :app 的 GithubMirrors 解码）。 */
@Serializable
data class RemoteMirrorList(
    val schema: String = "",
    val mirrors: List<RemoteMirror> = emptyList(),
)

@Serializable
data class RemoteMirror(
    val id: String = "",
    val label: String = "",
    val base: String = "",
    val kind: String = "prefix",
    val api: Boolean = false,
    val tier: Int = 0,
    val enabled: Boolean = true,
)
