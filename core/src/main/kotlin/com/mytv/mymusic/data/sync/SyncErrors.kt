package com.mytv.mymusic.data.sync

/**
 * 同步失败的分类与异常：:core 的合并/投影逻辑（SyncModels）要抛它们，
 * :app 的同步位置实现（Gist/Gitee/WebDAV）也用它们报错，所以定义放 :core。
 */

/** Precondition：带条件的写入被拒（WebDAV 412），说明读完到写入之间云端被别的设备改了。 */
enum class SyncFailureKind { Auth, Permission, NotFound, Network, Corrupt, Precondition, Unknown }

class SyncException(val kind: SyncFailureKind, override val message: String) : Exception(message)
