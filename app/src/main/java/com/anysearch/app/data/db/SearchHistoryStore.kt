package com.anysearch.app.data.db

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.anysearch.app.data.SearchHistoryItem
import com.anysearch.app.data.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history")

/**
 * 使用 DataStore 存储搜索历史记录
 * 最多保存 100 条，每条包含搜索关键词、搜索结果列表、时间戳
 */
class SearchHistoryStore(private val context: Context) {

    companion object {
        private const val KEY_PREFIX = "history_"
        private const val MAX_ENTRIES = 100
        private val HISTORY_KEYS_KEY = stringPreferencesKey("history_keys")
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** 获取所有历史记录（按时间倒序） */
    val historyFlow: Flow<List<SearchHistoryItem>> = context.historyDataStore.data.map { prefs ->
        val keysJson = prefs[HISTORY_KEYS_KEY] ?: "[]"
        val keys: List<String> = try {
            json.decodeFromString(keysJson)
        } catch (e: Exception) {
            emptyList()
        }
        keys.mapNotNull { key ->
            val entryJson = prefs[stringPreferencesKey("$KEY_PREFIX$key")] ?: return@mapNotNull null
            try {
                json.decodeFromString<SearchHistoryItem>(entryJson)
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.timestamp }
    }

    /** 添加一条搜索记录 */
    suspend fun addEntry(query: String, results: List<SearchResult>, vertical: String? = null) {
        context.historyDataStore.edit { prefs ->
            val keysJson = prefs[HISTORY_KEYS_KEY] ?: "[]"
            val keys: MutableList<String> = try {
                json.decodeFromString(keysJson)
            } catch (e: Exception) {
                mutableListOf()
            }

            // 生成唯一 ID（用时间戳）
            val id = System.currentTimeMillis()
            val key = id.toString()

            val entry = SearchHistoryItem(
                id = id,
                query = query,
                results = results,
                vertical = vertical,
                timestamp = id
            )

            val entryJson = json.encodeToString(entry)
            prefs[stringPreferencesKey("$KEY_PREFIX$key")] = entryJson

            // 加到列表头部
            keys.add(0, key)

            // 限制 100 条，删除多余的
            while (keys.size > MAX_ENTRIES) {
                val oldKey = keys.removeAt(keys.lastIndex)
                prefs.remove(stringPreferencesKey("$KEY_PREFIX$oldKey"))
            }

            prefs[HISTORY_KEYS_KEY] = json.encodeToString(keys)
        }
    }

    /** 删除单条记录 */
    suspend fun deleteEntry(id: Long) {
        context.historyDataStore.edit { prefs ->
            val keysJson = prefs[HISTORY_KEYS_KEY] ?: "[]"
            val keys: MutableList<String> = try {
                json.decodeFromString(keysJson)
            } catch (e: Exception) {
                mutableListOf()
            }

            val key = id.toString()
            keys.remove(key)
            prefs.remove(stringPreferencesKey("$KEY_PREFIX$key"))
            prefs[HISTORY_KEYS_KEY] = json.encodeToString(keys)
        }
    }

    /** 清空所有记录 */
    suspend fun clearAll() {
        context.historyDataStore.edit { prefs ->
            val keysJson = prefs[HISTORY_KEYS_KEY] ?: "[]"
            val keys: List<String> = try {
                json.decodeFromString(keysJson)
            } catch (e: Exception) {
                emptyList()
            }
            keys.forEach { key ->
                prefs.remove(stringPreferencesKey("$KEY_PREFIX$key"))
            }
            prefs.remove(HISTORY_KEYS_KEY)
        }
    }
}
