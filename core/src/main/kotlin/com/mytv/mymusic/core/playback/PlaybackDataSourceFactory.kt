package com.mytv.mymusic.core.playback

/**
 * 播放数据源工厂的占位类型。
 *
 * WebDAV 的云端副本要把「认证 + 证书钉住」的取流客户端交给播放器，:app 里就是 media3 的
 * `DataSource.Factory`；桌面端对应的是 VLC 的取流回调。:core 只负责把它原样传到调用方，
 * 不做任何平台判断，也不依赖 media3。
 *
 * 取值的一方自己向下转型：`cloudStream.dataSourceFactory as? DataSource.Factory`。
 */
typealias PlaybackDataSourceFactory = Any
