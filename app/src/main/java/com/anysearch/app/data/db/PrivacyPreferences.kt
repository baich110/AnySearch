package com.anysearch.app.data.db

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 扩展属性：在 Context 上创建 DataStore
val Context.privacyDataStore: DataStore<Preferences> by preferencesDataStore(name = "privacy_preferences")

class PrivacyPreferences(private val context: Context) {

    companion object {
        private val HAS_AGREED_KEY = booleanPreferencesKey("has_agreed_to_privacy")
    }

    /** 用户是否已同意隐私政策和用户协议 */
    val hasAgreed: Flow<Boolean> = context.privacyDataStore.data.map { preferences ->
        preferences[HAS_AGREED_KEY] ?: false
    }

    /** 标记用户已同意 */
    suspend fun setAgreed() {
        context.privacyDataStore.edit { preferences ->
            preferences[HAS_AGREED_KEY] = true
        }
    }

    /** 重置同意状态（用于测试） */
    suspend fun reset() {
        context.privacyDataStore.edit { preferences ->
            preferences.remove(HAS_AGREED_KEY)
        }
    }
}
