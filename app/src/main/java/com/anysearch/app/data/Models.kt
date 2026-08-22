package com.anysearch.app.data

import kotlinx.serialization.Serializable

/**
 * AnySearch API 数据模型
 * v0.2.0 - kotlinx.serialization 类型安全
 */

// 搜索结果项
@Serializable
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String = "",
    val source: String = "",
    val date: String = ""
)

// 提取的网页内容
@Serializable
data class ExtractedContent(
    val title: String,
    val source: String,
    val markdown: String,
    val success: Boolean
)

// 搜索模式
enum class SearchMode {
    SINGLE_LINE,    // 单行 -> 通用搜索
    MULTI_LINE,     // 多行 -> 并行搜索
    VERTICAL        // 垂直搜索
}

// 垂直领域
@Serializable
data class VerticalDomain(
    val key: String,
    val name: String,
    val icon: String,
    val subDomains: List<SubDomain> = emptyList()
) {
    companion object {
        fun chineseName(key: String): String = when (key) {
            "security" -> "安全"
            "academic" -> "学术"
            "code" -> "代码"
            "finance" -> "金融"
            "legal" -> "法律"
            "health" -> "健康"
            "business" -> "商业"
            "social_media" -> "社交媒体"
            "travel" -> "旅行"
            "film" -> "电影"
            "gaming" -> "游戏"
            "energy" -> "能源"
            "environment" -> "环境"
            "agriculture" -> "农业"
            "ip" -> "知识产权"
            "resource" -> "资源"
            else -> key.replaceFirstChar { it.uppercase() }
        }
    }
}

@Serializable
data class SubDomain(
    val key: String,
    val description: String,
    val params: List<ParamInfo> = emptyList()
)

@Serializable
data class ParamInfo(
    val key: String,
    val description: String,
    val required: Boolean = false
)

/**
 * 镜像测速结果（已废弃，测速逻辑随 GitHub 下载源一并移除）
 */

/**
 * 内容块（用于更新日志和公告的富文本渲染）
 * 通过 JSON 控制渲染方式，支持几十种类型
 */
@Serializable
data class ContentBlock(
    val type: String,                    // text, heading, list, link, image, button, divider, html, quote, code, bold, italic
    val content: String? = null,         // 文本内容
    val items: List<String>? = null,     // list 类型时使用
    val text: String? = null,            // link/button 类型时的显示文字
    val url: String? = null,             // link/button 类型时的跳转链接
    val level: Int? = null,              // heading 类型时的级别 (1-6)
    val language: String? = null         // code 类型时的语言
)

/**
 * 富文本内容（包裹一组 ContentBlock）
 */
@Serializable
data class RichContent(
    val type: String = "rich",           // 固定为 "rich"
    val title: String? = null,           // 可选标题
    val blocks: List<ContentBlock> = emptyList()
)