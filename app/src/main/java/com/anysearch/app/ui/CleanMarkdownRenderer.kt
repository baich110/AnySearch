package com.anysearch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import org.intellij.markdown.ast.getTextInNode

/**
 * markdown block 内部的一个片段：普通文本 或 链接
 */
private sealed class BlockSegment {
    data class TextSegment(val text: String) : BlockSegment()
    data class LinkSegment(val text: String, val url: String) : BlockSegment()
}

/**
 * 严格中文纯文本清洗：从任意 markdown/混杂文本中，只保留干净的中文纯文本。
 * 规则（严格到极致）：
 *  1. 剥离所有链接结构：[text](url)、[](url)、裸 URL，全部去掉
 *  2. 剔除所有"不规则"片段：纯英文/数字串、URL 残渣、markdown 符号、特殊字符
 *  3. 只保留：中文字符 + 中文标点（。，、；：！？""''（）《》）
 *  4. 清理后按句子（。！？…）重建，剔除空句
 * 用途：纯文本渲染模式（renderLinks=false）下，确保读屏读到的只有干净中文，绝无链接/URL 残留。
 */
fun extractChinesePlainText(raw: String): String {
    // 1. 剥离所有链接结构
    val noLinks = raw
        // [text](url) -> text
        .replace(Regex("""\[([^\]]*)\]\([^)]*\)""")) { m -> m.groupValues[1] }
        // []() 空链接 -> 删除
        .replace(Regex("""\[\]\([^)]*\)"""), " ")
        // [[N]] 引用标注
        .replace(Regex("""\[\[\d+\]\]"""), " ")
        // [N] 引用
        .replace(Regex("""\[\d+\]"""), " ")
        // 裸 http/https URL（到空白或右括号为止）
        .replace(Regex("""https?://[^\s)\]]+"""), " ")
        // 残留的 wikipedia.org/... 等裸域名（无 http，可能是被剥离后的残渣）
        .replace(Regex("""\b[a-zA-Z0-9]+\.(?:wikipedia|wikidata|org|com|net|edu|gov|io|github)[a-zA-Z0-9./?=&:%#_\-]*"""), " ")

    // 2. 按行/句子处理，剔除不规则片段
    val cleaned = StringBuilder()
    for (line in noLinks.split('\n')) {
        var seg = line
        // 剔除包含 URL 特征的整段（残留 http 或 .svg / .png / .jpg 等）
        if (seg.contains("http") || seg.matches(Regex(""".*\.(svg|png|jpg|jpeg|gif|webp)""", RegexOption.IGNORE_CASE))) {
            continue
        }
        // 逐步累积：只保留中文字符和中文标点
        val sb = StringBuilder()
        for (ch in seg) {
            if (isChineseChar(ch) || isChinesePunct(ch) || ch == '\n') {
                sb.append(ch)
            } else if (ch == ' ' || ch == '\t') {
                sb.append(' ')
            }
            // 其他字符（拉丁字母、数字、符号）直接剔除
        }
        cleaned.append(sb).append('\n')
    }

    // 3. 压缩空白和空行
    return cleaned.toString()
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex("""\n{2,}"""), "\n")
        .replace(Regex("""[ \t]+\n"""), "\n")
        .trim()
}

/** 是否为中文字符（含扩展区） */
private fun isChineseChar(ch: Char): Boolean =
    ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' || ch in '\uF900'..'\uFAFF' || ch == '\u3007'

/** 是否为中文标点 */
private fun isChinesePunct(ch: Char): Boolean = when (ch) {
    '。', '，', '、', '；', '：', '！', '？', '…', '—', '“', '”', '‘', '’', '（', '）', '《', '》', '〈', '〉', '【', '】', '「', '」', '『', '』' -> true
    else -> false
}

/**
 * 把单个 markdown block 按链接拆分成片段序列。
 * [text](url) -> LinkSegment，其余 -> TextSegment。
 * 这样每个链接可以渲染成独立可聚焦节点，各自挂 1-2 个操作，
 * 永不触发"单个组件最多 32 个 customActions"的崩溃。
 */
private fun splitBlockIntoSegments(block: String): List<BlockSegment> {
    val segments = mutableListOf<BlockSegment>()
    val linkRegex = Regex("""\[([^\]]*)\]\((https?://[^)]+)\)""")
    var lastEnd = 0
    for (m in linkRegex.findAll(block)) {
        // 链接前的纯文本
        if (m.range.first > lastEnd) {
            segments.add(BlockSegment.TextSegment(block.substring(lastEnd, m.range.first)))
        }
        val text = m.groupValues[1].ifBlank { m.groupValues[2] }
        segments.add(BlockSegment.LinkSegment(text, m.groupValues[2].trim()))
        lastEnd = m.range.last + 1
    }
    // 末尾纯文本
    if (lastEnd < block.length) {
        segments.add(BlockSegment.TextSegment(block.substring(lastEnd)))
    }
    return segments
}

/**
 * 把 Markdown 按逻辑块切分（段落、标题、列表、代码块、引用、表格等）
 * 增强版：除了按空行切分，还在"无空行"的连续正文中按逻辑段落边界切分，
 * 并加入"超长保护"：单个块超过 MAX_BLOCK_LEN 时强制切分，确保任何长文本
 * （如维基百科英文长文）都不会塌缩成一个大 block（大焦点）。
 * 边界规则：
 *  1. 空行 -> 切
 *  2. markdown 结构行（#标题 / -列表 / * / >引用 / |表格 / ```代码）-> 切
 *  3. 上一行是"短标题行"（<=25字且不以标点结尾）且本行更长 -> 短行视为标题，切
 *  4. 上一行以句号/叹号/问号结尾 -> 新段落，切
 *  5. 超长保护：当前块超过 MAX_BLOCK_LEN 且当前行以句号/冒号结尾 -> 切
 */
fun parseMarkdownBlocks(markdown: String): List<String> {
    val blocks = mutableListOf<String>()
    val current = StringBuilder()
    var inCodeBlock = false
    val sentenceEnd = setOf('。', '！', '？', '!', '?', '.', '…')
    val noBreakEnd = setOf('，', ',', '、', '；', ';', '：', ':')

    // 超长保护：单个块超过该长度即强制切分。
    // 调小阈值，确保任何长段落（含翻译后的大段中文）都被按句子切成可管理的小块，
    // 避免读屏一个大焦点读整段。约 800 字符，读屏逐句/逐小段读，体验最佳。
    val MAX_BLOCK_LEN = 800

    fun flush() {
        val text = current.toString().trimEnd()
        current.clear()
        if (text.isBlank()) return
        // 超长块二次切分：如果该块仍超过 MAX_BLOCK_LEN（如翻译后一整段长中文在单行内），
        // 按句子结束标点拆成多个小块，确保不会出现大焦点。
        if (text.length > MAX_BLOCK_LEN) {
            blocks.addAll(splitLongBlock(text, MAX_BLOCK_LEN, sentenceEnd))
        } else {
            blocks.add(text)
        }
    }

    for (line in markdown.lines()) {
        val t = line.trim()
        // 代码块开始/结束标记
        if (t.startsWith("```")) {
            if (inCodeBlock) {
                current.append(line)
                flush()
                inCodeBlock = false
            } else {
                flush()
                current.append(line).append("\n")
                inCodeBlock = true
            }
            continue
        }
        if (inCodeBlock) {
            current.append(line).append("\n")
            continue
        }

        // 空行 -> 切
        if (t.isEmpty()) {
            flush()
            continue
        }

        // 需要判断是否开启新块
        if (current.isNotBlank()) {
            val prev = current.toString().substringAfterLast("\n").trim()
            var isNewBlock = false

            // 规则2：当前行是 markdown 结构行
            if (t.startsWith("#") || t.startsWith("- ") || t.startsWith("* ") ||
                t.startsWith(">") || t.startsWith("|") || t.startsWith("```")
            ) {
                isNewBlock = true
            }
            // 规则3：上一行是短标题行（短、不以标点结尾、本行更长）
            else if (prev.isNotEmpty() &&
                prev.length <= 25 &&
                prev.last() !in sentenceEnd &&
                prev.last() !in noBreakEnd &&
                t.length > prev.length
            ) {
                isNewBlock = true
            }
            // 规则4：上一行以句子结束标点结尾
            else if (prev.isNotEmpty() && prev.last() in sentenceEnd) {
                isNewBlock = true
            }
            // 规则5：超长保护——当前块已超长，在句子结束标点处强制切分
            else if (current.length > MAX_BLOCK_LEN && prev.isNotEmpty() && prev.last() in sentenceEnd) {
                isNewBlock = true
            }
            // 规则6：绝对兜底——当前块超过 MAX_BLOCK_LEN*2 仍无句号可切，无条件切分
            else if (current.length > MAX_BLOCK_LEN * 2) {
                isNewBlock = true
            }

            if (isNewBlock) flush()
        }

        if (current.isNotEmpty()) current.append("\n")
        current.append(line)
    }
    flush()
    return blocks
}

/**
 * 把超长块按句子（。！？…等）拆成不超过 maxLen 的多个小块。
 * 优先在句子边界切；若单个句子本身超长则硬切。
 */
private fun splitLongBlock(text: String, maxLen: Int, sentenceEnd: Set<Char>): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        current.append(ch)
        // 句子边界：遇到结束标点，且当前块超过 maxLen 的一半，切分
        if (ch in sentenceEnd && current.length >= maxLen / 2) {
            result.add(current.toString().trim())
            current.clear()
        }
        // 绝对硬切：当前块已超过 maxLen 且还没遇到句号，强制切
        else if (current.length >= maxLen && ch !in sentenceEnd) {
            result.add(current.toString().trim())
            current.clear()
        }
        i++
    }
    if (current.isNotBlank()) result.add(current.toString().trim())
    return result.filter { it.isNotBlank() }
}

/**
 * 干净 Markdown 渲染器 v5 (规则增强版)
 *
 * 渲染规则参考：
 * - HungStayDeepTry/compose-markdown: 表格 Card 包裹、引用左侧彩条、列表嵌套符号
 * - mikepenz/multiplatform-markdown-renderer: 自定义 typography/colors/dimens
 * - JetBrains/markdown: AST 解析规范
 *
 * 文本处理规则：
 * - 第1层：杀噪音行（维基百科/CSDN/知乎等已知噪音）
 * - 第2层：行内清洗（相对链接、标注、编辑标记）
 * - 第3层：压缩连续空行（最多保留1个做段落分隔）
 * - 第4层：修复常见格式问题（表格缺少分隔行、列表前多余空行等）
 */

/**
 * 清洗 Markdown 文本 -- 去除网页噪音，只保留文本/标准超链接/表格/代码块
 */
fun cleanMarkdown(markdown: String): String {
    return markdown.lines()
        // 第1层：杀整行噪音
        .filter { line ->
            val t = line.trim()
            if (t.isEmpty()) return@filter true
            // 维基百科噪音（中英文）
            if (t.contains("[编辑]") || t.contains("[edit]")) return@filter false
            if (t.startsWith("[跳转到内容]") || t.startsWith("[Jump to content]") || t.startsWith("[字符至容]")) return@filter false
            if (t == "维基百科，自由的百科全书" || t == "From Wikipedia, the free encyclopedia" || t == "文出維基大典" || t == "出自維基百科") return@filter false
            if (t.startsWith("此条目") || t.startsWith("关于") && t.contains("请见")) return@filter false
            if (t.contains("//upload.wikimedia.org/")) return@filter false
            if (t.startsWith("（重定向自") || t.startsWith("(Redirected from")) return@filter false
            if (t.startsWith("Find sources:") || t.contains("news · newspapers · books")) return@filter false
            if (t.contains("better source needed") || t.contains("citation needed") || t.contains("[来源请求]")) return@filter false
            if (t.startsWith("This article relies")) return@filter false
            if (t.contains("Learn how and when to remove this message")) return@filter false
            if (t.startsWith("资料来源") || t.startsWith("** 资料来源")) return@filter false
            // CSDN 噪音
            if (t.contains("最新推荐文章于") || t.contains("版权声明") || t.contains("本内容遵循CC")) return@filter false
            if (t.contains("[GEO检测]") || t.contains("收录于") || t.contains("该文章已生成")) return@filter false
            if (t.contains("请附上原文") || t.contains("转载请附上") || t.contains("本文为博主")) return@filter false
            if (t.matches(Regex("""\d+\.\d+w 阅读""")) || (t.contains("CC ") && t.contains("BY-SA"))) return@filter false
            if (t.contains("csdnimg.cn") && t.contains("newHeart")) return@filter false
            // 知乎噪音
            if (t.startsWith("关注者") && t.matches(Regex("""关注者\d+"""))) return@filter false
            if (t.startsWith("被浏览") && t.matches(Regex("""被浏览\d+"""))) return@filter false
            // 标注行 [[N]]
            if (t.matches(Regex("""\[\[\d+\]\]"""))) return@filter false
            // CSS 残留
            if (t.startsWith(".mw-parser-output") || t.matches(Regex("""^\..*\{.*\}"""))) return@filter false
            // 纯 HTML 残留标签
            if (t.matches(Regex("""^<[^>]+>$"""))) return@filter false
            // 空表格行
            if (t.matches(Regex("""^\|[\s|]*\|$"""))) return@filter false
            // 纯列表圆点
            if (t == "•" || t == "·") return@filter false
            // 纯导航词
            if (t == "入題" || t == "入题") return@filter false
            if (t == "博覽" || t == "博览") return@filter false
            if (t == "兼查" || t == "據" || t == "据") return@filter false
            // 纯竖线（表格残留）
            if (t == "|") return@filter false
            // 纯数字加点（列表残留）
            if (t.matches(Regex("""^\d+\.$"""))) return@filter false
            true
        }
        // 第2层：行内清洗
        .map { line ->
            line
                // 先把全角冒号 http：// 修成 http://
                .replace(Regex("""(https?)[：:]""")) { m -> m.groupValues[1] + ":" }
                // 把中文括号链接 [text]（url）统一转成英文括号 [text](url)
                // 匹配 [text]（...） 整体替换
                .replace(Regex("""\[([^\]]*?)\]（([^）]*?)）""")) { m -> "[${m.groupValues[1]}](${m.groupValues[2]})" }
                // 残留的单独中文括号也转
                .replace(Regex("""\[([^\]]*?)\]（""")) { m -> "[${m.groupValues[1]}](" }
                .replace(Regex("""）"""), ")")
                // 去掉 URL 中的空格（action = edit -> action=edit）
                .replace(Regex("""\?(\w+)\s+=\s+""")) { m -> "?${m.groupValues[1]}=" }
                .replace(Regex("""&(\w+)\s+=\s+""")) { m -> "&${m.groupValues[1]}=" }
                // 去掉引用标注 [[N]](url) -- 整个删除
                .replace(Regex("""\[\[\d+\]\]\([^)]*\)"""), "")
                // 去掉 [↑](...) 引用箭头
                .replace(Regex("""\[↑\]\([^)]*\)"""), "")
                // 去掉 [edit] [编辑] 残留
                .replace(Regex("""\[edit\]|\[编辑\]"""), "")
                // 去掉 [better source needed] [citation needed] [来源请求]
                .replace(Regex("""\[better source needed\]|\[citation needed\]|\[来源请求\]"""), "")
                // 去掉 [tined](...) 编辑链接
                .replace(Regex("""\[tined\]\([^)]*\)"""), "")
                // 相对链接 [text](/path) -> 纯文字
                .replace(Regex("""\[([^\]]*?)\]\(/[^\)]*?\)""")) { m -> m.groupValues[1] }
                // 协议相对链接 [text](//host/path) -> 纯文字
                .replace(Regex("""\[([^\]]*?)\]\(//[^\)]*?\)""")) { m -> m.groupValues[1] }
                // 锚点链接 [text](#anchor) -> 纯文字
                .replace(Regex("""\[([^\]]*?)\]\(#[^\)]*?\)""")) { m -> m.groupValues[1] }
                // action=edit&redlink=1 的红链 -> 纯文字（不管是不是 https）
                .replace(Regex("""\[([^\]]*?)\]\([^)]*action=edit[^)]*\)""")) { m -> m.groupValues[1] }
                // veaction=edit 的编辑链接 -> 删除
                .replace(Regex("""\[([^\]]*?)\]\([^)]*veaction=edit[^)]*\)""")) { m -> m.groupValues[1] }
                // 保留标准超链接 [text](https://...) 不动
                // 去掉 HTML 标签
                .replace(Regex("""<sup[^>]*>.*?</sup>"""), "")
                .replace(Regex("""<small[^>]*>.*?</small>"""), "")
                .replace(Regex("""<span[^>]*>(.*?)</span>""")) { m -> m.groupValues[1] }
                .replace(Regex("""<a[^>]*>(.*?)</a>""")) { m -> m.groupValues[1] }
                .replace(Regex("""<img[^>]*>"""), "")
                .replace(Regex("""<br\s*/?>"""), "\n")
                .replace(Regex("""<ref[^>]*>.*?</ref>"""), "")
                // 去掉行首圆点 • ·
                .replace(Regex("""^[•·]\s*"""), "")
                // 去掉自取"..."格式
                .replace(Regex("""自取"[^"]*"""), "")
                // 去掉 [一个例子](...) 等残留
                .replace(Regex("""\[一个例子\]\([^)]*\)"""), "")
                // 去掉纯数字列表标记（如 "1. " 单独成行的）
                .replace(Regex("""^\d+\.\s*$"""), "")
                .trim()
                // 去掉单独的 [ 或 ] 残留
                .replace(Regex("""^\[\s*$"""), "")
                .replace(Regex("""^\]\s*$"""), "")
        }
        // 第3层：压缩连续空行
        .fold(mutableListOf<String>()) { acc, line ->
            if (line.isBlank() && acc.isNotEmpty() && acc.last().isBlank()) acc
            else { acc.add(line); acc }
        }
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
        // 第4层：修复格式问题
        .let { lines ->
            val result = mutableListOf<String>()
            for (i in lines.indices) {
                val line = lines[i]
                val prev = result.lastOrNull()
                if (line.startsWith("|") && prev != null && prev.isNotEmpty() && !prev.startsWith("|")) {
                    result.add("")
                }
                if (line.startsWith("```") && prev != null && prev.isNotEmpty() && !prev.startsWith("```")) {
                    result.add("")
                }
                if (line.startsWith(">") && prev != null && prev.isNotEmpty() && !prev.startsWith(">")) {
                    result.add("")
                }
                result.add(line)
            }
            result
        }
        .joinToString("\n")
}

/**
 * 渲染清洗后的 Markdown
 * @param renderLinks true=带链接完整渲染（每个链接独立可聚焦节点+提取/浏览器打开操作）
 *                    false=纯文本渲染（链接转纯文本，不渲染可点击链接，干净稳定）
 */
@Composable
fun CleanMarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
    renderLinks: Boolean = true,
    onLinkClick: (String) -> Unit = {},
    onBrowse: (String) -> Unit = {}
) {
    val cleaned = remember(markdown) { cleanMarkdown(markdown) }
    val blocks = remember(cleaned) { parseMarkdownBlocks(cleaned) }

    // 拦截 Markdown 内部链接点击，交给 onLinkClick 处理（而不是跳外部浏览器）
    val customUriHandler = remember(onLinkClick) {
        object : UriHandler {
            override fun openUri(uri: String) {
                onLinkClick(uri)
            }
        }
    }

    CompositionLocalProvider(LocalUriHandler provides customUriHandler) {
        // LazyColumn 虚拟化：性能好，低端机不吃力，只渲染可见块
        LazyColumn(
            modifier = modifier.fillMaxWidth()
        ) {
            items(blocks) { block ->
                if (renderLinks) {
                    // ===== 带链接完整渲染：每个链接是独立可聚焦节点，挂 提取/浏览器打开 操作 =====
                    val segments = remember(block) { splitBlockIntoSegments(block) }
                    val hasLink = remember(block) { block.contains("](") && block.contains("http") }
                    if (hasLink) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                            segments.forEach { seg ->
                                when (seg) {
                                    is BlockSegment.TextSegment -> {
                                        if (seg.text.isNotBlank()) {
                                            Text(
                                                text = seg.text,
                                                style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                    is BlockSegment.LinkSegment -> {
                                        // 每个链接是独立可聚焦节点，挂 2 个操作（提取正文/浏览器打开）
                                        val linkDesc = "${seg.text}，链接"
                                        Text(
                                            text = buildAnnotatedString {
                                                withStyle(SpanStyle(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    textDecoration = TextDecoration.Underline,
                                                    fontWeight = FontWeight.Medium
                                                )) { append(seg.text) }
                                            },
                                            style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(role = Role.Button) { onLinkClick(seg.url) }
                                                .clearAndSetSemantics {
                                                    contentDescription = linkDesc
                                                    // 每个链接最多 2 个操作，永不超 32 上限
                                                    customActions = listOf(
                                                        CustomAccessibilityAction(
                                                            label = "提取正文",
                                                            action = { onLinkClick(seg.url); true }
                                                        ),
                                                        CustomAccessibilityAction(
                                                            label = "浏览器打开",
                                                            action = { onBrowse(seg.url); true }
                                                        )
                                                    )
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // 无链接块：普通 Text，稳定焦点
                        val plainText = remember(block) {
                            block.replace(Regex("""[#*>`_-]"""), " ")
                                .replace(Regex("""\s+"""), " ")
                                .trim()
                        }
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                            Text(
                                text = plainText,
                                style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.clearAndSetSemantics {
                                    contentDescription = plainText
                                }
                            )
                        }
                    }
                } else {
                    // ===== 纯文本渲染（不带链接）：严格清洗，只保留干净中文纯文本 =====
                    // 用 extractChinesePlainText 剥离所有链接/URL/不规则内容，只留中文
                    val plainText = remember(block) { extractChinesePlainText(block) }
                    if (plainText.isNotBlank()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                            Text(
                                text = plainText,
                                style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.clearAndSetSemantics {
                                    contentDescription = plainText
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
