package com.anysearch.app.data

import kotlinx.serialization.Serializable

/**
 * 搜索历史记录（UI 层使用）
 * 包含搜索关键词、搜索结果列表、时间戳
 */
@Serializable
data class SearchHistoryItem(
    val id: Long,
    val query: String,
    val results: List<SearchResult>,
    val vertical: String? = null,
    val timestamp: Long
)
