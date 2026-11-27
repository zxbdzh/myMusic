package com.mytv.mymusic.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

// 两个设置文件仍落在应用私有目录的 datastore 目录下，文件名没变，老用户的设置原地可读。
private val Context.dataStore by preferencesDataStore("mymusic")

// 每首歌的歌词记忆单独一个文件：切一首歌写一次，放进主设置会让整个界面跟着收到一次新设置
private val Context.lyricMemoryStore by preferencesDataStore("lyric_memory")

/**
 * Android 侧创建 [SettingsStore]：DataStore 还是 androidx.datastore 的那一套，
 * 只是实例从这里注入进 :core 的 SettingsStore（那边不再持有 Context）。
 */
fun appSettingsStore(context: Context): SettingsStore =
    SettingsStore(context.dataStore, context.lyricMemoryStore)
