package com.anysearch.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.util.concurrent.TimeUnit

/**
 * AnySearch API 客户端 v1.3.0
 * - OkHttp 5.4 + okhttp-coroutines（官方挂起扩展 executeAsync/awaitResult）
 * - kotlinx.serialization 编译期类型安全，零反射
 * - JSON-RPC 2.0 协议
 * - 分离翻译专用客户端，避免连接池争抢
 * - 并行翻译提升大文本翻译速度
 */
class AnySearchApi(
    private val apiKey: String = "",
    private val appVersion: String = "1.1.0"
) {
    companion object {
        private const val DEFAULT_ENDPOINT = "https://api.anysearch.com/mcp"
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 30L
        private const val TRANSLATE_MAX_CHARS = 5000
        private const val TRANSLATE_PARALLEL_CHUNK = 2000
        private const val MAX_CONCURRENT_TRANSLATIONS = 8
        private const val CLIENT_UA = "anysearch-app/"

        // 商汤 SenseNova AI 排版（OpenAI 兼容格式，走 OkHttp 协程）
        private const val AI_ENDPOINT = "https://token.sensenova.cn/v1/chat/completions"
        private const val AI_API_KEY = "sk-GmK471pZW5taBLL4O3XsK8w1Xtx3dINr"
        private const val AI_MODEL = "sensenova-6.8-flash-lite"
        private const val AI_MAX_TOKENS = 8192
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val translateClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    // AI 排版专用客户端（独立连接池，避免与搜索/翻译争抢，更快更稳）
    private val aiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)  // AI 生成耗时较长
        .build()

    private val endpoint = DEFAULT_ENDPOINT
    private val jsonMedia = "application/json".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }
    private var rpcId = 1

    // ==================== API 方法 ====================

    /**
     * 通用搜索
     */
    suspend fun search(
        query: String,
        maxResults: Int = 10,
        domain: String? = null,
        subDomain: String? = null
    ): List<SearchResult> {
        val args = buildJsonObject {
            put("query", query)
            put("max_results", maxResults.coerceIn(1, 50))
            if (domain != null) put("domain", domain)
            if (subDomain != null) put("sub_domain", subDomain)
        }
        val response = callMcp("search", args)
        return parseSearchResults(extractMcpContent(response))
    }

    /**
     * 并行批量搜索
     */
    suspend fun batchSearch(queries: List<String>, maxResults: Int = 10): Map<String, List<SearchResult>> {
        if (queries.isEmpty()) return emptyMap()
        val queriesArray = JsonArray(queries.map { query ->
            buildJsonObject {
                put("query", query)
                put("max_results", maxResults.coerceIn(1, 50))
            }
        })
        val args = buildJsonObject { put("queries", queriesArray) }
        val response = callMcp("batch_search", args)
        return parseBatchResults(extractMcpContent(response), queries)
    }

    /**
     * 获取垂直领域列表
     */
    suspend fun getSubDomains(domains: List<String>): List<VerticalDomain> {
        val args = buildJsonObject { put("domains", JsonArray(domains.map { JsonPrimitive(it) })) }
        val response = callMcp("get_sub_domains", args)
        return parseSubDomains(extractMcpContent(response))
    }

    /**
     * 提取网页正文
     */
    suspend fun extract(url: String): ExtractedContent {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL 必须以 http:// 或 https:// 开头"
        }
        val args = buildJsonObject { put("url", url) }
        val response = callMcp("extract", args)
        return parseExtractResult(extractMcpContent(response), url)
    }

    /**
     * 检查更新（从 MinIO 公网读取 update.json，无需任何凭证，Token 不再进 APK）
     * 注意：服务器端更新 JSON 后需清 Cloudflare CDN 缓存（Cache Rule 配置了长缓存）
     */
    suspend fun checkUpdate(currentVersionCode: Int): UpdateInfo? {
        val updateUrl = "https://files.baichabuyu.asia/public/anysearch/update.json"
        return try {
            val request = Request.Builder()
                .url(updateUrl)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).executeAsync().use { response ->
                if (!response.isSuccessful) null
                else {
                    val body = response.body?.string() ?: return@use null
                    val updateJson = json.decodeFromString<UpdateJson>(body)
                    UpdateInfo(
                        versionCode = updateJson.versionCode,
                        versionName = updateJson.versionName,
                        downloadUrl = updateJson.downloadUrl,
                        updateLog = updateJson.updateLog,
                        history = (updateJson.history ?: emptyList()).map {
                            HistoryItem(it.versionName, it.date, it.updateLog)
                        },
                        currentVersionCode = currentVersionCode
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取公告（从 MinIO 公网读取，无需任何凭证）
     */
    suspend fun getAnnouncement(): String? {
        val request = Request.Builder()
            .url("https://files.baichabuyu.asia/public/anysearch/announcement.json")
            .header("Cache-Control", "no-cache")
            .build()
        return try {
            client.newCall(request).executeAsync().use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 已废弃：GitHub 镜像测速。当前下载源为 MinIO 直链（files.baichabuyu.asia），
     * 无需测速。保留此占位避免误调用；原实现与 MirrorSpeedResult 一并清理。
     */

    /**
     * 串行翻译（按段落分块，保证顺序）
     */
    suspend fun translate(text: String, source: String = "en", target: String = "zh"): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        val chunks = chunkText(text, maxChars = TRANSLATE_MAX_CHARS)
        if (chunks.isEmpty()) return@withContext text
        val results = chunks.map { chunk -> translateChunk(chunk, source, target) }
        results.joinToString("\n")
    }

    /**
     * 并行翻译（小分块 + 最多8个并发，速度更快）
     */
    suspend fun translateParallel(text: String, source: String = "en", target: String = "zh"): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        val chunks = chunkText(text, maxChars = TRANSLATE_PARALLEL_CHUNK)
        if (chunks.isEmpty()) return@withContext text
        chunks.chunked(MAX_CONCURRENT_TRANSLATIONS).flatMap { batch ->
            batch.map { chunk ->
                async(Dispatchers.IO) { translateChunk(chunk, source, target) }
            }.awaitAll()
        }.joinToString("\n")
    }

    /**
     * 按段落分块，每块不超过 maxChars
     */
    private fun chunkText(text: String, maxChars: Int): List<String> {
        val paragraphs = text.split("\n")
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (para in paragraphs) {
            if (current.length + para.length + 1 > maxChars && current.isNotEmpty()) {
                chunks.add(current.toString())
                current.clear()
            }
            if (para.length > maxChars) {
                para.chunked(maxChars).forEach { chunks.add(it) }
            } else {
                if (current.isNotEmpty()) current.append("\n")
                current.append(para)
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    /**
     * 调用腾讯 TranSmart 翻译一段文本
     */
    private suspend fun translateChunk(text: String, source: String, target: String): String {
        val requestJson = buildJsonObject {
            putJsonObject("header") {
                put("fn", "auto_translation")
                put("session", "")
                put("client_key", "browser-chrome-130.0.0-Android-${android.os.Build.VERSION.SDK_INT}-${System.currentTimeMillis()}")
                put("user", "")
            }
            put("type", "plain")
            put("model_category", "normal")
            put("text_domain", "")
            putJsonObject("source") {
                put("lang", source)
                putJsonArray("text_list") { add(JsonPrimitive(text)) }
            }
            putJsonObject("target") {
                put("lang", target)
            }
        }
        val request = Request.Builder()
            .url("https://transmart.qq.com/api/imt")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(jsonMedia))
            .build()
        return try {
            translateClient.newCall(request).executeAsync().use { resp ->
                if (!resp.isSuccessful) return@use text
                val body = resp.body?.string() ?: return@use text
                try {
                    val response = json.parseToJsonElement(body).jsonObject
                    val translations = response["auto_translation"]?.jsonArray
                    if (translations != null && translations.isNotEmpty()) {
                        translations.joinToString("") { it.jsonPrimitive.content }
                    } else text
                } catch (e: Exception) { text }
            }
        } catch (e: Exception) {
            // 网络层异常（取消/超时等），返回原文兜底
            text
        }
    }

    // ==================== AI 智能排版（商汤 SenseNova，OpenAI 兼容格式） ====================

    /**
     * 把 MCP 提取的原始内容交给 AI，让 AI 排版成 RichContent JSON。
     * AI 就是规则：不做任何预处理清洗，AI 直接接收原始 markdown，
     * 按我们渲染器支持的组件 schema 输出结构化 JSON。
     * 走 OkHttp 协程（okhttp-coroutines executeAsync），速度快。
     * @return RichContent JSON 字符串（blocks 数组），可直接用 RichContentRenderer 渲染
     */
    suspend fun aiFormatContent(title: String, rawMarkdown: String): String {
        // 限制输入长度：截断到约 1.5 万字符，减少首 token 延迟（模型快，输入长了反而慢）
        val input = if (rawMarkdown.length > 15000) rawMarkdown.take(15000) else rawMarkdown

        // system prompt：只排版、不润色、保留全部链接，输出 RichContent JSON
        val systemPrompt = """
            你是一个内容排版工具，不是作者。你的唯一任务是把用户提供的原文**原样排版**成结构化 JSON。
            禁止改写、润色、扩写、总结原文内容，禁止新增任何原文没有的内容。

            ## 铁律（必须严格遵守）
            1. **只排版，不创作**：正文文字必须忠实于原文，一个词都不要改、不要润色、不要扩写
            2. **完整保留所有超链接**：原文中每个链接 [文字](url) 都必须用 link 组件原样保留，链接文字和 url 都不能丢、不能改、不能漏
            3. 只剔除明显的噪音：URL 残渣、引用标注[1]、markdown 语法残留、乱码
            4. 输出必须是严格合法的 JSON，不要任何解释、不要 ```json 代码块标记、不要多余文字

            ## 输出格式（只输出这个 JSON）
            {"type":"rich","title":"文章标题","blocks":[
              {"type":"heading","level":1,"content":"标题"},
              {"type":"text","content":"段落"},
              {"type":"bold","content":"加粗"},
              {"type":"link","text":"链接文字","url":"https://真实url"},
              {"type":"list","items":["项1","项2"]},
              {"type":"quote","content":"引用"},
              {"type":"divider"},
              {"type":"image","url":"https://图片","content":"描述"}
            ]}

            ## 可用组件类型（只能这些）
            heading(level 1-6) / text / bold / italic / link(text+url) / list(items数组) / quote / code(content+language) / divider / image(url+content)

            ## 排版原则
            1. 用 heading 划分章节、text 放段落、list 放列表、link 放链接、quote 放引用
            2. 中文排版（原文英文则保持原文语言，不翻译）
            3. 内容多长就多长，忠实呈现，不要截断、不要精简
        """.trimIndent()

        // 第一次请求
        var assistantContent = callAiForFormat(systemPrompt, title, input)
        // 工具校验：如果格式不合法，把错误喂回给 AI，让它重试一次（self-healing）
        val validation = validateRichJson(assistantContent)
        if (!validation.first) {
            AppLogger.w("AIFormat", "AI 输出格式不合法，重试：${validation.second}")
            assistantContent = callAiForFormat(
                systemPrompt,
                title,
                input,
                retryFeedback = validation.second // 把工具的错误提示喂回给 AI
            )
        }
        // 返回最终结果（无论重试后是否合法，都能兜底渲染）
        return extractRichJson(assistantContent, title)
    }

    /** 调用商汤 AI 排版（OkHttp 协程 + IO 线程） */
    private suspend fun callAiForFormat(
        systemPrompt: String,
        title: String,
        input: String,
        retryFeedback: String? = null
    ): String {
        val requestJson = buildJsonObject {
            put("model", AI_MODEL)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "标题：$title\n\n原始内容：\n$input")
                })
                // 如果有重试反馈，追加一条 assistant 错误 + 用户纠正
                if (retryFeedback != null) {
                    add(buildJsonObject {
                        put("role", "assistant")
                        put("content", "（上次输出不符合要求）")
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", "你上次的输出不合法，工具校验失败：$retryFeedback。请重新严格按要求输出合法 JSON，只排版、保留全部链接、不要润色。")
                    })
                }
            }
            put("temperature", 0.3)  // 降低温度，让输出更稳定、更守格式
            put("max_tokens", AI_MAX_TOKENS)
        }

        val request = Request.Builder()
            .url(AI_ENDPOINT)
            .addHeader("Authorization", "Bearer $AI_API_KEY")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(jsonMedia))
            .build()

        return try {
            withContext(Dispatchers.IO) {
                aiClient.newCall(request).executeAsync().use { resp ->
                    if (!resp.isSuccessful) {
                        AppLogger.e("AIFormat", "AI 排版失败 HTTP ${resp.code}")
                        return@use buildFallbackRichJson(title, "AI 排版请求失败（HTTP ${resp.code}）")
                    }
                    val body = resp.body?.string() ?: return@use buildFallbackRichJson(title, "AI 排版响应为空")
                    try {
                        val jsonResp = json.parseToJsonElement(body).jsonObject
                        jsonResp["choices"]?.jsonArray
                            ?.firstOrNull()?.jsonObject
                            ?.get("message")?.jsonObject
                            ?.get("content")?.jsonPrimitive?.content
                        ?: buildFallbackRichJson(title, "AI 排版无返回内容")
                    } catch (e: Exception) {
                        AppLogger.e("AIFormat", "AI 排版解析失败", e)
                        buildFallbackRichJson(title, "AI 排版解析失败")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("AIFormat", "AI 排版网络异常", e)
            buildFallbackRichJson(title, "AI 排版网络异常：${e.message}")
        }
    }

    /**
     * 工具校验：检查 AI 输出的 RichContent JSON 是否符合 schema。
     * @return valid=true 合法；valid=false 时 error 为给 AI 的错误提示
     */
    private fun validateRichJson(content: String): Pair<Boolean, String> {
        val text = content.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return false to "输出不是有效的 JSON 对象（缺少花括号）"
        }
        return try {
            val root = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
            val blocks = root["blocks"]?.jsonArray
                ?: return false to "缺少 blocks 数组字段"
            if (blocks.isEmpty()) return false to "blocks 数组为空"
            for (b in blocks) {
                val type = b.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    ?: return false to "某个 block 缺少 type 字段"
                val validTypes = setOf(
                    "heading", "text", "bold", "italic", "link", "list",
                    "quote", "code", "divider", "image"
                )
                if (type !in validTypes) return false to "不支持的组件类型: $type（只允许 heading/text/bold/italic/link/list/quote/code/divider/image）"
            }
            true to ""
        } catch (e: Exception) {
            false to "JSON 解析失败：${e.message}"
        }
    }

    // ==================== JSON-RPC 通信 ====================

    /** 从 AI 返回内容中提取 RichContent JSON（可能被 ```json 包裹或带额外文字） */
    private fun extractRichJson(content: String, title: String): String {
        var text = content.trim()
        // 去掉可能的 ```json ... ``` 代码块标记
        text = text.replace(Regex("""^```(?:json)?\s*"""), "")
        text = text.replace(Regex("""\s*```$"""), "")
        // 提取 {...} 部分
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
        }
        return buildFallbackRichJson(title, "AI 返回格式无法解析")
    }

    /** 构建失败/兜底的 RichContent JSON */
    private fun buildFallbackRichJson(title: String, error: String): String {
        return """{"type":"rich","title":"$title","blocks":[
            {"type":"heading","level":1,"content":"AI 排版未完成"},
            {"type":"text","content":"$error"},
            {"type":"text","content":"请稍后重试，或切换其他渲染模式。"}
        ]}"""
    }

    // ==================== JSON-RPC 通信 ====================

    /**
     * 发送 JSON-RPC 2.0 请求（OkHttp 5 官方协程扩展，全链路挂起不阻塞线程）
     */
    private suspend fun callMcp(toolName: String, arguments: JsonObject): String {
        val rpcRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", rpcId++)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .apply { if (apiKey.isNotEmpty()) addHeader("Authorization", "Bearer $apiKey") }
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Anysearch-Client", "$CLIENT_UA$appVersion")
            .post(rpcRequest.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(request).executeAsync().use { resp ->
            if (!resp.isSuccessful) {
                throw AnySearchApiException(
                    statusCode = resp.code,
                    message = "API 请求失败: HTTP ${resp.code}"
                )
            }
            return resp.body?.string() ?: throw AnySearchApiException(
                statusCode = resp.code,
                message = "API 响应体为空"
            )
        }
    }

    // ==================== 响应解析 ====================

    /**
     * 从 JSON-RPC 响应中提取 content 文本
     */
    private fun extractMcpContent(raw: String): String {
        return try {
            val response = json.parseToJsonElement(raw).jsonObject
            val result = response["result"]?.jsonObject
            val contentArray = result?.get("content")?.jsonArray
            if (contentArray != null && contentArray.isNotEmpty()) {
                buildString {
                    contentArray.forEach { item ->
                        append(item.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "")
                    }
                }
            } else raw
        } catch (e: Exception) { raw }
    }

    // ==================== 结果解析 ====================

    /**
     * 解析搜索结果文本
     */
    private fun parseSearchResults(content: String): List<SearchResult> {
        if (content.isBlank()) return emptyList()
        val results = mutableListOf<SearchResult>()
        val regex = """### \d+\.\s+(.+?)(?:\n|$)((?:.|\n)*?)(?=\n###|\Z)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        regex.findAll(content).forEach { match ->
            val title = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            val urlMatch = """\*\*URL\*\*:\s*(\S+)""".toRegex().find(body)
                ?: """(https?://\S+)""".toRegex().find(body)
            val url = urlMatch?.groupValues?.getOrNull(1)?.trim() ?: ""
            val dateMatch = """date:\s*(.+)""".toRegex().find(body)
            val date = dateMatch?.groupValues?.getOrNull(1)?.trim() ?: ""
            val snippet = body.lines()
                .filter { !it.contains("**URL**") && !it.startsWith("- **URL") }
                .joinToString(" ").trim()
            if (title.isNotEmpty()) {
                results.add(SearchResult(title, url, snippet, extractDomain(url), date))
            }
        }
        return results
    }

    /**
     * 解析批量搜索结果，按查询分组
     */
    private fun parseBatchResults(content: String, queries: List<String>): Map<String, List<SearchResult>> {
        if (content.isBlank() || queries.isEmpty()) return queries.associateWith { emptyList() }
        val result = mutableMapOf<String, List<SearchResult>>()
        val sections = content.split("(?=\n## Query \\d+)".toRegex()).filter { it.isNotBlank() }

        if (sections.size >= queries.size) {
            queries.forEachIndexed { i, q ->
                result[q] = if (i < sections.size) parseSearchResults(sections[i]) else emptyList()
            }
        } else {
            val sections2 = content.split("(?=\n## )".toRegex()).filter { it.isNotBlank() }
            if (sections2.size >= queries.size) {
                queries.forEachIndexed { i, q ->
                    result[q] = if (i < sections2.size) parseSearchResults(sections2[i]) else emptyList()
                }
            } else {
                val all = parseSearchResults(content)
                queries.forEachIndexed { i, q ->
                    result[q] = if (i == 0) all else emptyList()
                }
            }
        }
        return result
    }

    /**
     * 解析垂直领域列表
     */
    private fun parseSubDomains(content: String): List<VerticalDomain> {
        if (content.isBlank()) return emptyList()
        val domains = mutableListOf<VerticalDomain>()
        val regex = """(\w+) Domain""".toRegex()
        val seen = mutableSetOf<String>()
        regex.findAll(content).forEach { match ->
            val key = match.groupValues[1].lowercase()
            if (seen.add(key)) {
                domains.add(VerticalDomain(key, VerticalDomain.chineseName(key), ""))
            }
        }
        if (domains.isEmpty()) {
            listOf(
                "security", "academic", "code", "finance", "legal", "health",
                "business", "social_media", "travel", "film", "gaming",
                "energy", "environment", "agriculture", "ip", "resource"
            ).forEach { domains.add(VerticalDomain(it, VerticalDomain.chineseName(it), "")) }
        }
        return domains
    }

    /**
     * 解析提取结果
     */
    private fun parseExtractResult(content: String, sourceUrl: String): ExtractedContent {
        if (content.isBlank()) return ExtractedContent("", sourceUrl, "", success = false)
        val titleMatch = """^#\s+(.+)$""".toRegex(RegexOption.MULTILINE).find(content)
        val title = titleMatch?.groupValues?.getOrNull(1)?.trim() ?: sourceUrl
        val sourceMatch = """\*\*Source\*\*:\s*(.+)""".toRegex().find(content)
        val source = sourceMatch?.groupValues?.getOrNull(1)?.trim() ?: sourceUrl
        return ExtractedContent(title, source, content, success = content.isNotBlank())
    }

    private fun extractDomain(url: String): String {
        return try { java.net.URI(url).host ?: "" } catch (e: Exception) { "" }
    }
}

/**
 * AnySearch API 自定义异常
 */
class AnySearchApiException(
    val statusCode: Int = -1,
    override val message: String
) : Exception(message)

/**
 * 更新信息
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val updateLog: String,
    val history: List<HistoryItem> = emptyList(),
    val currentVersionCode: Int = 0
) {
    val hasUpdate: Boolean get() = versionCode > currentVersionCode
}

/**
 * 历史版本记录
 */
data class HistoryItem(
    val versionName: String,
    val date: String,
    val updateLog: String
)

/**
 * update.json 数据结构（MinIO 公网读取）
 */
@kotlinx.serialization.Serializable
data class UpdateJson(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val updateLog: String,
    val history: List<UpdateHistoryJson>? = null
)

@kotlinx.serialization.Serializable
data class UpdateHistoryJson(
    val versionName: String = "",
    val date: String = "",
    val updateLog: String = ""
)
