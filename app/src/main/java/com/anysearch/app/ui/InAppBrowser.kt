package com.anysearch.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 内置浏览器 -- 基于原生 WebView 封装
 * 参考 FOSS Browser (scoute-dich/browser) 的设计理念：
 * - 底部工具栏（单手操作友好）
 * - 进度条
 * - 前进/后退/刷新/分享
 * - JS 注入接口（为翻译功能预留）
 * - 无障碍支持
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppBrowser(
    initialUrl: String,
    onBack: () -> Unit,
    onExtract: (String) -> Unit = {},
    onTranslate: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var currentTitle by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showUrlBar by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf(initialUrl) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BackHandler(enabled = true) {
        if (canGoBack && webView != null) {
            webView!!.goBack()
        } else {
            onBack()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部：返回 + 标题 + 提取按钮
        TopAppBar(
            title = {
                Text(
                    if (isLoading) "加载中..." else (if (currentTitle.isNotEmpty()) currentTitle else "浏览器"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Normal,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics {
                        contentDescription = if (isLoading) "正在加载网页" else currentTitle
                        heading()
                        if (isLoading) stateDescription = "加载中"
                    }
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp).focusRequester(focusRequester).semantics { traversalIndex = 1f }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出浏览器，返回搜索结果")
                }
            },
            actions = {
                IconButton(onClick = { onExtract(currentUrl) }, modifier = Modifier.size(48.dp).semantics { traversalIndex = 3f }) {
                    Icon(Icons.Default.Article, contentDescription = "提取当前网页正文")
                }
                if (onTranslate != null) {
                    IconButton(onClick = onTranslate, modifier = Modifier.size(48.dp).semantics { traversalIndex = 4f }) {
                        Icon(Icons.Default.Translate, contentDescription = "提取并翻译当前网页正文")
                    }
                }
                IconButton(onClick = {
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, currentUrl)
                    }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, "分享链接"))
                }, modifier = Modifier.size(48.dp).semantics { traversalIndex = 5f }) {
                    Icon(Icons.Default.Share, contentDescription = "分享当前网页链接")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        // 进度条
        if (isLoading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            HorizontalDivider(thickness = 0.5.dp)
        }

        // WebView 主体
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    createWebView(ctx).apply {
                        webViewClient = BrowserWebViewClient(
                            onPageStarted = { url ->
                                isLoading = true
                                currentUrl = url
                                urlInput = url
                            },
                            onPageFinished = { url, title ->
                                isLoading = false
                                currentUrl = url
                                urlInput = url
                                currentTitle = title
                                canGoBack = webView?.canGoBack() ?: false
                                canGoForward = webView?.canGoForward() ?: false
                            },
                            onUrlChanged = { url ->
                                currentUrl = url
                                urlInput = url
                            }
                        )
                        webChromeClient = BrowserChromeClient(
                            onProgress = { p ->
                                progress = p
                                if (p >= 100) isLoading = false
                            },
                            onTitle = { title -> currentTitle = title }
                        )
                        loadUrl(initialUrl)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 底部工具栏（参考 FOSS Browser 单手操作设计）
        BottomAppBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            actions = {
                IconButton(
                    onClick = { webView?.goBack() },
                    enabled = canGoBack,
                    modifier = Modifier.size(48.dp).semantics { stateDescription = if (canGoBack) "可后退" else "无法后退"; traversalIndex = 10f }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "后退",
                        tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
                    )
                }
                IconButton(
                    onClick = { webView?.goForward() },
                    enabled = canGoForward,
                    modifier = Modifier.size(48.dp).semantics { stateDescription = if (canGoForward) "可前进" else "无法前进"; traversalIndex = 11f }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "前进",
                        tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
                    )
                }
                IconButton(onClick = {
                    webView?.reload()
                }, modifier = Modifier.size(48.dp).semantics { traversalIndex = 12f }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新当前网页")
                }
                IconButton(onClick = {
                    showUrlBar = !showUrlBar
                }, modifier = Modifier.size(48.dp).semantics { traversalIndex = 13f }) {
                    Icon(Icons.Default.Public, contentDescription = "打开地址栏输入新网址")
                }
            },
            floatingActionButton = {
                if (isLoading) {
                    FloatingActionButton(
                        onClick = { webView?.stopLoading() },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "停止加载", modifier = Modifier.size(20.dp))
                    }
                } else {
                    FloatingActionButton(
                        onClick = { webView?.scrollBy(0, 9999) },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "滚动到底部", modifier = Modifier.size(20.dp))
                    }
                }
            }
        )
    }

    // URL 输入弹窗
    if (showUrlBar) {
        AlertDialog(
            onDismissRequest = { showUrlBar = false },
            title = { Text("输入网址") },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    var url = urlInput.trim()
                    if (url.isNotEmpty() && !url.startsWith("http")) {
                        url = "https://$url"
                    }
                    webView?.loadUrl(url)
                    showUrlBar = false
                }) { Text("前往") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlBar = false }) { Text("取消") }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(context: android.content.Context): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            allowUniversalAccessFromFileURLs = false
            allowFileAccessFromFileURLs = false
        }
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        isScrollContainer = true
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "网页内容"
    }
}

private class BrowserWebViewClient(
    private val onPageStarted: (String) -> Unit,
    private val onPageFinished: (String, String) -> Unit,
    private val onUrlChanged: (String) -> Unit
) : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != null) onPageStarted(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (view != null && url != null) {
            val title = view.title ?: ""
            onPageFinished(url, title)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        onUrlChanged(url)
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return false
        }
        return true
    }
}

private class BrowserChromeClient(
    private val onProgress: (Int) -> Unit,
    private val onTitle: (String) -> Unit
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        if (title != null) onTitle(title)
    }
}