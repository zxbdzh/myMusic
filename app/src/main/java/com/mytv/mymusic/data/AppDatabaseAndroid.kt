package com.mytv.mymusic.data

import android.content.Context
import androidx.room.Room

/**
 * Android 侧的库打开方式。
 *
 * 库声明、实体、DAO、迁移全部在 :core 的 [AppDatabase]；这里只做一件事——用 Android 的
 * `Room.databaseBuilder(context, …)` 打开，库文件仍是 `context.getDatabasePath("mymusic.db")`
 * （也就是 `databases/mymusic.db`），SQLite 仍是系统自带的那个：
 * 老用户的库原地能用，不需要迁移文件，也不额外引入 bundled SQLite 的原生库。
 *
 * 桌面端走 :core 的 [openAppDatabase]（BundledSQLiteDriver + 显式路径）。
 */
fun createAppDatabase(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "mymusic.db")
        .addMigrations(*AppDatabase.ALL_MIGRATIONS)
        .build()
