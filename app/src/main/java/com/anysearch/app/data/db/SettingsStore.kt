package com.anysearch.app.data.db

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 独立 DataStore 存应用设置偏好
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * 应用设置存储
 * 目前：渲染结果是否带链接（renderLinks）
 */
class SettingsStore(private val context: Context) {

    companion object {
        /** 阅读页渲染是否带链接（true=带链接完整渲染，false=纯文本渲染） */
        private val RENDER_LINKS_KEY = booleanPreferencesKey("render_links")
    }

    /** 是否带链接渲染（默认 true，带链接） */
    val renderLinks: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[RENDER_LINKS_KEY] ?: true
    }

    /** 设置是否带链接渲染 */
    suspend fun setRenderLinks(value: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[RENDER_LINKS_KEY] = value
        }
    }
}
