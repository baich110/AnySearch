package com.anysearch.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anysearch.app.data.ContentBlock
import com.anysearch.app.data.RichContent
import com.anysearch.app.data.SearchHistoryItem
import com.anysearch.app.data.SearchResult
import com.anysearch.app.data.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.coroutines.executeAsync

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnySearchApp(viewModel: SearchViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val searchText by viewModel.searchText.collectAsState()
    val history by viewModel.history.collectAsState()
    val fullHistory by viewModel.fullHistory.collectAsState()
    val verticals by viewModel.verticals.collectAsState()
    val selectedVertical by viewModel.selectedVertical.collectAsState()
    val focusedResultUrl by viewModel.focusedResultUrl.collectAsState()
    val privacyAgreed by viewModel.privacyAgreed.collectAsState()
    val renderLinks by viewModel.renderLinks.collectAsState()          // 全局偏好：是否带链接
    val tempRenderLinks by viewModel.tempRenderLinks.collectAsState() // 阅读页临时覆盖
    var showVerticalSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.1.0"
        } catch (e: Exception) { "1.1.0" }
    }

    // 监听 ViewModel 事件，显示 Toast
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SearchViewModel.UiEvent.ShowToast -> {
                    android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                }
                is SearchViewModel.UiEvent.ShowError -> {
                    android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 隐私协议弹窗：首次打开时显示，同意后不再弹出
    if (!privacyAgreed) {
        PrivacyAgreementDialog(
            onAgree = viewModel::agreeToPrivacy,
            onDisagree = {
                viewModel.disagreeToPrivacy()
                (context as? android.app.Activity)?.finishAffinity()
            }
        )
        return
    }

    BackHandler(enabled = state !is SearchViewModel.UiState.Idle) {
        when (state) {
            is SearchViewModel.UiState.Reading,
            is SearchViewModel.UiState.Extracting,
            is SearchViewModel.UiState.Error,
            is SearchViewModel.UiState.Browsing -> viewModel.backToResults()
            is SearchViewModel.UiState.Results -> viewModel.backFromHistoryResults()
            is SearchViewModel.UiState.About -> viewModel.backToHome()
            is SearchViewModel.UiState.History -> viewModel.backToHome()
            else -> {}
        }
    }

    // 侧边栏：历史记录 / 关于 / 设置
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                Text(
                    "搜索 · 菜单",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp).semantics { heading() }
                )
                HorizontalDivider()
                // 历史记录
                NavigationDrawerItem(
                    label = { Text("历史记录") },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    selected = state is SearchViewModel.UiState.History,
                    onClick = {
                        scope.launch { drawerState.close() }
                        viewModel.showHistory()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "历史记录，双击查看搜索历史"
                        role = Role.Button
                    }
                )
                // 关于
                NavigationDrawerItem(
                    label = { Text("关于") },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    selected = state is SearchViewModel.UiState.About,
                    onClick = {
                        scope.launch { drawerState.close() }
                        viewModel.showAbout()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "关于，双击查看应用信息和检查更新"
                        role = Role.Button
                    }
                )
                HorizontalDivider()
                // 设置：渲染是否带链接
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp).semantics { heading() }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("阅读页带链接渲染", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (renderLinks) "当前：带链接（每链接有提取/打开操作）" else "当前：纯文本（更简洁稳定）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = renderLinks,
                        onCheckedChange = { viewModel.setRenderLinksPreference(it) },
                        modifier = Modifier.semantics {
                            contentDescription = "阅读页是否带链接渲染，当前${if (renderLinks) "开" else "关"}"
                        }
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("搜索", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }, modifier = Modifier.semantics { contentDescription = "打开菜单" }) {
                            Icon(Icons.Default.Menu, contentDescription = "打开菜单")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                )
            }
        ) { padding ->
        AnimatedContent(targetState = state, transitionSpec = { ContentTransform(
            targetContentEnter = slideInHorizontally { it } + fadeIn(tween(250)),
            initialContentExit = slideOutHorizontally { -it } + fadeOut(tween(200))
        ) }, modifier = Modifier.padding(padding).fillMaxSize()) { targetState ->
            when (val s = targetState) {
                is SearchViewModel.UiState.Idle -> {
                    val view = LocalView.current
                    LaunchedEffect(Unit) {
                        viewModel.bringCursorToEnd()
                        view.announceForAccessibility("主页")
                    }
                    val currentVertical = selectedVertical
                    LaunchedEffect(currentVertical) {
                        if (currentVertical != null) {
                            view.announceForAccessibility("已选择垂直搜索：${com.anysearch.app.data.VerticalDomain.chineseName(currentVertical)}")
                        }
                    }
                    SearchHomePage(
                        searchText, viewModel::updateSearchText, viewModel::search,
                        { showVerticalSheet = true }, viewModel::clearSearchText,
                        selectedVertical, viewModel::selectVertical,
                        { scope.launch { drawerState.open() } }
                    )
                }
                is SearchViewModel.UiState.Loading -> {
                    val view = LocalView.current
                    LaunchedEffect(Unit) { view.announceForAccessibility("正在搜索，请稍候") }
                    LoadingView()
                }
                is SearchViewModel.UiState.Results -> {
                    val view = LocalView.current
                    val totalCount = s.grouped.values.sumOf { it.size }
                    LaunchedEffect(Unit) { view.announceForAccessibility("搜索结果，共${totalCount}条") }
                    ResultsPage(
                        s.grouped, viewModel::reset, viewModel::extractContent,
                        focusedResultUrl, viewModel::clearFocusedResultUrl,
                        viewModel::openInBrowser
                    )
                }
                is SearchViewModel.UiState.Extracting -> {
                    val view = LocalView.current
                    LaunchedEffect(Unit) { view.announceForAccessibility("正在提取正文") }
                    ExtractingView(s.url)
                }
                is SearchViewModel.UiState.Reading -> {
                    // 实际渲染模式：优先临时覆盖，否则用全局偏好
                    val effective = tempRenderLinks ?: renderLinks
                    ReadingPage(
                        s.content, s.isTranslating, s.fromUrl,
                        renderLinks = effective,
                        aiHtml = s.aiHtml,
                        onToggleRenderLinks = { viewModel.setTempRenderLinks(!effective) },
                        onAiFormat = viewModel::aiFormatContent,
                        onBack = viewModel::backToResults,
                        onLinkClick = viewModel::extractContent,
                        onBrowse = viewModel::openInBrowser,
                        onTranslate = viewModel::translateContent
                    )
                }
                is SearchViewModel.UiState.Browsing -> InAppBrowser(
                    initialUrl = s.url,
                    onBack = viewModel::backFromBrowser,
                    onExtract = { url ->
                        viewModel.backFromBrowser()
                        viewModel.extractContent(url)
                    },
                    onTranslate = {
                        viewModel.backFromBrowser()
                        viewModel.extractAndTranslate(s.url)
                    }
                )
                is SearchViewModel.UiState.Error -> {
                    val view = LocalView.current
                    LaunchedEffect(Unit) { view.announceForAccessibility("搜索出错") }
                    ErrorView(
                        s.message, viewModel::search, viewModel::backToResults
                    )
                }
                is SearchViewModel.UiState.About -> {
                    val view = LocalView.current
                    LaunchedEffect(Unit) { view.announceForAccessibility("关于") }
                    val updateInfo by viewModel.updateInfo.collectAsState()
                    val checkingUpdate by viewModel.checkingUpdate.collectAsState()
                    val announcement by viewModel.announcement.collectAsState()
                    val announcementLoading by viewModel.announcementLoading.collectAsState()
                    AboutPage(
                        onBack = viewModel::backToHome,
                        onCheckUpdate = { viewModel.checkUpdate() },
                        checkingUpdate = checkingUpdate,
                        updateInfo = updateInfo,
                        onClearUpdateInfo = viewModel::clearUpdateInfo,
                        onLoadAnnouncement = viewModel::loadAnnouncement,
                        announcementLoading = announcementLoading,
                        announcement = announcement,
                        onClearAnnouncement = viewModel::clearAnnouncement,
                        appVersion = appVersion
                    )
                }
                is SearchViewModel.UiState.History -> {
                    val view = LocalView.current
                    LaunchedEffect(Unit) { view.announceForAccessibility("历史记录，共${fullHistory.size}条") }
                    HistoryPage(
                        historyItems = fullHistory,
                        onBack = viewModel::backToHome,
                        onItemClick = viewModel::loadFromHistory,
                        onReSearch = viewModel::reSearchFromHistory,
                        onDeleteItem = viewModel::deleteHistoryItem,
                        onClearAll = viewModel::clearAllHistory
                    )
                }
            }
        }
    }
    }   // 关闭 ModalNavigationDrawer content

    if (showVerticalSheet) {
        ModalBottomSheet(onDismissRequest = { showVerticalSheet = false }) {
            val sheetView = LocalView.current
            LazyColumn(modifier = Modifier.padding(16.dp).semantics { paneTitle = "选择垂直领域" }) {
                item { Text("选择垂直领域", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp).semantics { heading() }) }
                // 如果已选择，显示取消选项
                if (selectedVertical != null) {
                    item {
                        ListItem(
                            headlineContent = { Text("取消垂直搜索", color = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable(role = Role.Button) {
                                viewModel.selectVertical(null)
                                showVerticalSheet = false
                                sheetView.announceForAccessibility("已取消垂直搜索，恢复通用搜索")
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                items(verticals) { domain ->
                    val isSelected = selectedVertical == domain.key
                    ListItem(
                        headlineContent = { Text(domain.name, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        trailingContent = { if (isSelected) Icon(Icons.Default.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable(role = Role.Button) {
                            viewModel.selectVertical(domain.key)
                            showVerticalSheet = false
                            sheetView.announceForAccessibility("已选择垂直搜索：${domain.name}")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHomePage(
    searchText: TextFieldValue, onSearchTextChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit, onExpandVertical: () -> Unit,
    onClear: () -> Unit,
    selectedVertical: String?,
    onClearVertical: (String?) -> Unit,
    onOpenDrawer: () -> Unit   // 打开侧边栏菜单（历史记录/关于/设置）
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val lineCount = searchText.text.lines().filter { it.isNotBlank() }.size
    Column(modifier = Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(40.dp))
        Row(modifier = Modifier.fillMaxWidth().semantics { traversalIndex = 2f }, verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = searchText, onValueChange = onSearchTextChange,
                modifier = Modifier.weight(1f).semantics { contentDescription = "搜索输入框，${when { lineCount > 1 -> "多行并行搜索模式，${lineCount}个关键词"; selectedVertical != null -> "垂直搜索，${com.anysearch.app.data.VerticalDomain.chineseName(selectedVertical)}"; else -> "输入关键词搜索" }}"; traversalIndex = 2f },
                label = { Text("搜索关键词") },
                placeholder = { Text("输入关键词搜索...") },
                supportingText = { Text(when { lineCount > 1 -> "并行搜索 · $lineCount 个关键词"; selectedVertical != null -> "垂直搜索 · ${com.anysearch.app.data.VerticalDomain.chineseName(selectedVertical)}"; else -> "单行通用 · 多行并行" }) },
                minLines = 1, maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(); keyboard?.hide() }),
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    if (searchText.text.isNotEmpty()) {
                        Row {
                            IconButton(onClick = onClear, modifier = Modifier.size(48.dp).semantics { traversalIndex = 3f }) { Icon(Icons.Default.Close, contentDescription = "清除搜索内容") }
                            IconButton(onClick = onSearch, modifier = Modifier.size(48.dp).semantics { traversalIndex = 4f }) { Icon(Icons.Default.Search, contentDescription = "执行搜索") }
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onExpandVertical, modifier = Modifier.padding(top = 8.dp).size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer).semantics { traversalIndex = 5f }) {
                Icon(Icons.Default.ExpandMore, contentDescription = if (selectedVertical != null) "已选择${com.anysearch.app.data.VerticalDomain.chineseName(selectedVertical)}垂直搜索，点击切换" else "选择垂直搜索领域")
            }
        }
        // 选了垂直领域后显示可关闭的标签
        if (selectedVertical != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().semantics { traversalIndex = 6f }, verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { onClearVertical(null) },
                    label = { Text("垂直搜索: ${com.anysearch.app.data.VerticalDomain.chineseName(selectedVertical)}") },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.semantics {
                        contentDescription = "已选择垂直搜索：${com.anysearch.app.data.VerticalDomain.chineseName(selectedVertical)}，双击取消"
                        role = Role.Button
                        liveRegion = LiveRegionMode.Polite
                        traversalIndex = 6f
                    }
                )
            }
        }
        // 顶部菜单按钮：打开侧边栏（历史记录/关于/设置）
        Row(
            modifier = Modifier.fillMaxWidth().semantics { traversalIndex = 7f },
            horizontalArrangement = Arrangement.Start
        ) {
            AssistChip(
                onClick = onOpenDrawer,
                label = { Text("菜单", maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Menu, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = "打开菜单，包含历史记录、关于、设置"
                    traversalIndex = 7f
                }
            )
        }
        // 底部提示（原关于按钮移入侧边栏）
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "无障碍友好的多源聚合搜索",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.semantics { traversalIndex = 99f }
        )
    }
}

@Composable
private fun ResultsPage(
    grouped: Map<String, List<SearchResult>>,
    onBack: () -> Unit,
    onResultClick: (String) -> Unit,
    focusedResultUrl: String?,
    onClearFocusedUrl: () -> Unit,
    onBrowse: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val view = LocalView.current
    val totalCount = grouped.values.sumOf { it.size }
    // 搜索结果到达时播报
    LaunchedEffect(grouped) {
        view.announceForAccessibility("搜索完成，共 $totalCount 条结果")
    }
    // 焦点记忆和恢复
    LaunchedEffect(focusedResultUrl, grouped) {
        if (focusedResultUrl != null) {
            var flatIndex = -1
            var currentIdx = 0
            for ((_, results) in grouped) {
                for (result in results) {
                    if (result.url == focusedResultUrl) {
                        flatIndex = currentIdx
                        break
                    }
                    currentIdx++
                }
                if (flatIndex != -1) break
            }
            if (flatIndex != -1) {
                listState.scrollToItem(flatIndex)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).semantics { traversalIndex = 1f }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回搜索") }
            Text("搜索结果 · $totalCount 条", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading(); traversalIndex = 2f })
        }
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().semantics { collectionInfo = androidx.compose.ui.semantics.CollectionInfo(rowCount = totalCount, columnCount = 1); traversalIndex = 3f }, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            grouped.forEach { (query, results) ->
                if (grouped.size > 1) item { Text(query, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading(); traversalIndex = 3f }) }
                itemsIndexed(results) { index, result ->
                    ResultCard(
                        result,
                        itemIndex = index,
                        totalItems = results.size,
                        isFocused = focusedResultUrl == result.url,
                        onClearFocusedUrl = onClearFocusedUrl,
                        onRead = { onResultClick(result.url) },
                        onBrowse = { onBrowse(result.url) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultCard(
    result: SearchResult,
    itemIndex: Int = 0,
    totalItems: Int = 0,
    isFocused: Boolean = false,
    onClearFocusedUrl: () -> Unit = {},
    onRead: () -> Unit,
    onBrowse: () -> Unit
) {
    val cardDesc = buildString {
        append("第${itemIndex + 1}项，共${totalItems}项。")
        append(result.title)
        if (result.snippet.isNotBlank()) append("，摘要：${result.snippet}")
        if (result.source.isNotEmpty()) append("，来源：${result.source}")
        append("，双击提取正文阅读")
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isFocused) {
        if (isFocused) {
            focusRequester.requestFocus()
            onClearFocusedUrl()
        }
    }
    Card(modifier = Modifier
        .fillMaxWidth()
        .focusRequester(focusRequester)
        .clickable(onClick = onRead, role = Role.Button)
        .semantics(mergeDescendants = true) {
            contentDescription = cardDesc
            collectionItemInfo = androidx.compose.ui.semantics.CollectionItemInfo(rowIndex = itemIndex, rowSpan = 1, columnIndex = 0, columnSpan = 1)
            customActions = listOf(
                CustomAccessibilityAction(
                    label = "内置浏览器打开",
                    action = { onBrowse(); true }
                )
            )
        },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(result.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            Text(result.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    if (result.source.isNotEmpty()) Text(result.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (result.date.isNotEmpty()) Text(result.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("提取正文 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ReadingPage(
    content: com.anysearch.app.data.ExtractedContent,
    isTranslating: Boolean,
    fromUrl: String,
    renderLinks: Boolean,          // 当前阅读是否带链接渲染
    aiHtml: String?,               // AI 排版后的 RichContent JSON（非 null 时渲染 AI 排版）
    onToggleRenderLinks: () -> Unit, // 临时切换渲染模式
    onAiFormat: () -> Unit,        // AI 智能排版
    onBack: () -> Unit,
    onLinkClick: (String) -> Unit,
    onBrowse: (String) -> Unit,
    onTranslate: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current
    // 进入阅读页播报
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        view.announceForAccessibility("正文已提取，向下滑动开始阅读")
    }
    // 渲染模式切换播报
    LaunchedEffect(renderLinks) {
        view.announceForAccessibility(if (renderLinks) "带链接渲染" else "纯文本渲染")
    }
    // 翻译状态变化播报
    LaunchedEffect(isTranslating) {
        if (!isTranslating) {
            view.announceForAccessibility("翻译完成")
        }
    }
    // AI 排版完成播报
    LaunchedEffect(aiHtml) {
        if (aiHtml != null) view.announceForAccessibility("AI 排版完成")
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).focusRequester(focusRequester).semantics { traversalIndex = 1f }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回搜索结果") }
            Text("提取阅读", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading(); traversalIndex = 2f })
            Spacer(modifier = Modifier.weight(1f))
            // AI 智能排版按钮（第三个选项）
            TextButton(
                onClick = onAiFormat,
                modifier = Modifier.semantics {
                    contentDescription = "AI智能排版，交给AI重新排版成精美文章"
                    traversalIndex = 3f
                }
            ) { Text("AI排版", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium) }
            if (isTranslating) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("AI处理中...", style = MaterialTheme.typography.labelSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; traversalIndex = 4f })
            } else {
                // 渲染模式临时切换：纯文本 / 带链接
                TextButton(
                    onClick = onToggleRenderLinks,
                    modifier = Modifier.semantics {
                        contentDescription = if (renderLinks) "当前带链接渲染，点击切换为纯文本" else "当前纯文本渲染，点击切换为带链接"
                        traversalIndex = 4f
                    }
                ) { Text(if (renderLinks) "纯文本" else "带链接", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = onTranslate, modifier = Modifier.semantics { traversalIndex = 5f }) { Text("翻译", style = MaterialTheme.typography.labelMedium) }
            }
            IconButton(onClick = { onBrowse(fromUrl) }, modifier = Modifier.size(48.dp).semantics { traversalIndex = 6f }) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = "内置浏览器打开原文")
            }
        }
        HorizontalDivider()
        if (aiHtml != null) {
            // AI 排版模式：用 RichContentRenderer 渲染 AI 生成的 RichContent JSON
            val blocks = remember(aiHtml) { parseRichContent(aiHtml) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .semantics { traversalIndex = 7f }
            ) {
                RichContentRenderer(blocks = blocks)
            }
        } else {
            CleanMarkdownRenderer(
                markdown = content.markdown,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(
                                label = "内置浏览器打开原文",
                                action = { onBrowse(fromUrl); true }
                            )
                        )
                        traversalIndex = 7f
                    },
                renderLinks = renderLinks,
                onLinkClick = onLinkClick,
                onBrowse = onBrowse
            )
        }
    }
}

@Composable
private fun LoadingView() {
    Box(modifier = Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("搜索中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExtractingView(url: String) {
    Box(modifier = Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("正在提取正文...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(modifier = Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Assertive }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
            Row { TextButton(onClick = onBack) { Text("返回") }; Button(onClick = onRetry, modifier = Modifier.focusRequester(focusRequester)) { Text("重试") } }
        }
    }
}

@Composable
private fun AboutPage(
    onBack: () -> Unit,
    onCheckUpdate: () -> Unit,
    checkingUpdate: Boolean,
    updateInfo: UpdateInfo?,
    onClearUpdateInfo: () -> Unit,
    onLoadAnnouncement: () -> Unit,
    announcementLoading: Boolean,
    announcement: String?,
    onClearAnnouncement: () -> Unit,
    appVersion: String
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    var showLicense by remember { mutableStateOf(false) }
    var showAgreement by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showAnnouncement by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).focusRequester(focusRequester)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回主页")
            }
            Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 应用信息卡片
            item {
                Card(modifier = Modifier.fillMaxWidth().semantics { heading() }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("搜索", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("无障碍友好的多源聚合搜索引擎", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("版本 $appVersion", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 联系开发者
            item {
                Text("联系开发者", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() })
            }
            item {
                ListItem(
                    headlineContent = { Text("白茶不语") },
                    supportingContent = { Text("baichabuyu8@gmail.com") },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                    trailingContent = { Icon(Icons.Default.Email, contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button) {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("mailto:baichabuyu8@gmail.com")
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "搜索APP反馈")
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "发送邮件"))
                    }.semantics { contentDescription = "联系开发者白茶不语，邮箱 baichabuyu8@gmail.com，双击发送邮件" }
                )
            }

            // 应用功能
            item {
                Text("应用管理", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() })
            }
            item {
                ListItem(
                    headlineContent = { Text("检查更新") },
                    supportingContent = { if (checkingUpdate) Text("检查中...") else Text("点击检查最新版本") },
                    leadingContent = { Icon(Icons.Default.Update, contentDescription = null) },
                    trailingContent = {
                        if (checkingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp).semantics { progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(current = 0f, range = 0f..1f) }, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    },
                    modifier = Modifier.clickable(role = Role.Button) { onCheckUpdate() }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("公告") },
                    supportingContent = { if (announcementLoading) Text("加载中...") else Text("查看最新公告") },
                    leadingContent = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    trailingContent = {
                        if (announcementLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp).semantics { progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(current = 0f, range = 0f..1f) }, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    },
                    modifier = Modifier.clickable(role = Role.Button) {
                        onLoadAnnouncement()
                        showAnnouncement = true
                    }
                )
            }
            // 复制日志（调试用）：一键复制完整日志文本
            item {
                val clipboard = LocalClipboardManager.current
                ListItem(
                    headlineContent = { Text("复制日志") },
                    supportingContent = { Text("复制完整日志，粘贴给开发者排查问题") },
                    leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
                    trailingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button) {
                        val logText = com.anysearch.app.data.AppLogger.buildFullLogText()
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(logText))
                        android.widget.Toast.makeText(context, "日志已复制，共 ${logText.length} 字符", android.widget.Toast.LENGTH_SHORT).show()
                    }.semantics { contentDescription = "复制日志，一键复制完整日志到剪贴板，粘贴给开发者" }
                )
            }

            // 法律文档
            item {
                Text("法律信息", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() })
            }
            item {
                ListItem(
                    headlineContent = { Text("开源声明") },
                    supportingContent = { Text("查看第三方开源组件") },
                    leadingContent = { Icon(Icons.Default.Code, contentDescription = null) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button) { showLicense = true }.semantics { contentDescription = "开源声明，双击查看第三方开源组件许可证" }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("用户协议") },
                    supportingContent = { Text("使用条款与条件") },
                    leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button) { showAgreement = true }.semantics { contentDescription = "用户协议，双击查看使用条款" }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("隐私政策") },
                    supportingContent = { Text("数据收集与使用说明") },
                    leadingContent = { Icon(Icons.Default.Security, contentDescription = null) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button) { showPrivacy = true }.semantics { contentDescription = "隐私政策，双击查看数据使用说明" }
                )
            }

            // 底部签名
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Made with Kotlin + Jetpack Compose", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text("© 2026 白茶不语", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }

    // 更新弹窗
    if (updateInfo != null) {
        val hasUpdate = updateInfo.hasUpdate
        AlertDialog(
            onDismissRequest = onClearUpdateInfo,
            title = {
                Text(if (hasUpdate) "发现新版本 ${updateInfo.versionName}" else "当前已是最新版本")
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (hasUpdate) {
                        Text("当前版本：$appVersion", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("最新版本：${updateInfo.versionName}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("更新内容：", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(updateInfo.updateLog, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("当前版本：$appVersion", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (updateInfo.history.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("历史更新", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        updateInfo.history.forEach { item ->
                            Text(
                                "v${item.versionName} (${item.date})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Text(item.updateLog, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                if (hasUpdate) {
                    if (downloading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier.width(120.dp).semantics {
                                    progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(current = downloadProgress / 100f, range = 0f..1f)
                                    stateDescription = "正在下载，进度 $downloadProgress%"
                                }
                            )
                            Text(
                                "$downloadProgress%",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    } else {
                        TextButton(onClick = {
                            downloading = true
                            downloadProgress = 0
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                var response: okhttp3.Response? = null
                                try {
                                    val client = okhttp3.OkHttpClient.Builder()
                                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                                        .followRedirects(true)
                                        .build()
                                    val request = okhttp3.Request.Builder()
                                        .url(updateInfo.downloadUrl)
                                        .build()
                                    response = client.newCall(request).executeAsync()
                                    if (!response.isSuccessful) {
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, "下载失败: HTTP ${response.code}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        return@launch
                                    }
                                    val body = response.body ?: run {
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, "下载失败: 响应体为空", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        return@launch
                                    }
                                    val totalBytes = body.contentLength()
                                    val file = java.io.File(context.cacheDir, "update.apk")
                                    var bytesRead = 0L
                                    body.byteStream().use { input ->
                                        java.io.FileOutputStream(file).use { output ->
                                            val buffer = ByteArray(8192)
                                            var bytes = input.read(buffer)
                                            while (bytes != -1) {
                                                output.write(buffer, 0, bytes)
                                                bytesRead += bytes
                                                if (totalBytes > 0) {
                                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                        downloadProgress = (bytesRead * 100 / totalBytes).toInt()
                                                    }
                                                }
                                                bytes = input.read(buffer)
                                            }
                                        }
                                    }
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        downloadProgress = 100
                                        // 自动安装
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setDataAndType(
                                                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(context.cacheDir, "update.apk")),
                                                "application/vnd.android.package-archive"
                                            )
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                } catch (e: Exception) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        val errMsg = e.message ?: e.javaClass.simpleName
                                        android.widget.Toast.makeText(context, "下载失败: $errMsg", android.widget.Toast.LENGTH_SHORT).show()
                                        android.util.Log.e("AnySearch-Download", "下载失败", e)
                                    }
                                } finally {
                                    response?.close()
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        downloading = false
                                        downloadProgress = -1
                                    }
                                }
                            }
                        }) { Text("下载更新") }
                    }
                } else {
                    TextButton(onClick = onClearUpdateInfo) { Text("确定") }
                }
            },
            dismissButton = if (hasUpdate) {
                { TextButton(onClick = onClearUpdateInfo) { Text("稍后") } }
            } else null
        )
    }

    // 公告弹窗（富文本渲染）
    if (showAnnouncement && announcement != null) {
        AlertDialog(
            onDismissRequest = {
                showAnnouncement = false
                onClearAnnouncement()
            },
            title = { Text("公告") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    RichContentRenderer(blocks = parseRichContent(announcement))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAnnouncement = false
                    onClearAnnouncement()
                }) { Text("知道了") }
            }
        )
    }

    if (showLicense) {
        LegalDialog(title = "开源声明", onDismiss = { showLicense = false }) {
            """
## 开源组件

本应用基于以下开源项目构建，感谢开源社区：

- Kotlin (Apache 2.0) -- JetBrains
- Jetpack Compose (Apache 2.0) -- Google
- Material Components for Android (Apache 2.0) -- Google
- OkHttp (Apache 2.0) -- Square
- kotlinx.serialization (Apache 2.0) -- JetBrains
- multiplatform-markdown-renderer (Apache 2.0) -- mikepenz
- Coil3 (Apache 2.0) -- Coil Contributors

## 开源协议

本应用自身代码遵循 MIT 协议开源。

## 第三方服务

- AnySearch API -- 搜索与内容提取
- 腾讯 TranSmart -- 翻译服务
""".trimIndent()
        }
    }

    if (showAgreement) {
        LegalDialog(title = "用户协议", onDismiss = { showAgreement = false }) {
            """
## 欢迎使用搜索

本应用提供网页搜索及内容提取功能。使用本应用即表示您同意以下条款：

## 服务说明

- 本应用仅为搜索工具，不存储任何网页内容
- 所有数据均来自第三方公开网站
- 提取的正文内容版权归原作者所有

## 用户责任

- 用户应自行承担使用本应用获取信息的相关责任
- 开发者不对搜索内容的准确性、合法性负责
- 请勿将本应用用于任何违反法律法规的用途

## 服务变更

- 开发者保留随时修改或终止服务的权利
- 应用更新可能改变功能特性，恕不另行通知

## 联系方式

- 开发者：白茶不语
- 邮箱：baichabuyu8@gmail.com
""".trimIndent()
        }
    }

    if (showPrivacy) {
        LegalDialog(title = "隐私政策", onDismiss = { showPrivacy = false }) {
            """
## 隐私承诺

本应用高度重视您的隐私。以下是数据收集与使用说明：

## 数据收集

- 本应用不收集、不存储、不上传任何用户个人信息
- 搜索关键词和浏览记录仅保存在您的本地设备上
- 不会发送到除搜索引擎以外的任何服务器

## 第三方服务

- 搜索请求发送至 AnySearch API 进行处理
- 翻译时文本内容发送至腾讯 TranSmart 接口
- 以上服务均不涉及个人身份信息

## 权限说明

- 网络权限：用于搜索和内容提取
- 存储权限：用于应用更新下载
- 无广告、无统计 SDK、无第三方追踪组件

## 开源透明

本应用完全开源，代码可审查，不存在隐藏的数据收集行为。
""".trimIndent()
        }
    }
}

@Composable
private fun LegalDialog(title: String, onDismiss: () -> Unit, content: () -> String) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                content().lines().forEach { line ->
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("## ") -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(trimmed.removePrefix("## "), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                        }
                        trimmed.startsWith("- ") -> {
                            Text("  • ${trimmed.removePrefix("- ")}", style = MaterialTheme.typography.bodySmall)
                        }
                        trimmed.startsWith("  ") -> {
                            Text(trimmed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        trimmed.isEmpty() -> Spacer(modifier = Modifier.height(4.dp))
                        else -> {
                            Text(trimmed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

/**
 * 隐私政策与用户协议首次启动弹窗
 * 简洁设计：一个说明焦点 + 一个复选框 + 两个按钮
 */
@Composable
private fun PrivacyAgreementDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    var checked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .focusRequester(focusRequester),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 焦点1：标题 + 说明 + 超链接（合并为一个焦点）
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleLarge.fontSize)) {
                            append("用户协议与隐私政策\n\n")
                        }
                        withStyle(SpanStyle(fontSize = MaterialTheme.typography.bodyMedium.fontSize)) {
                            append("欢迎使用「搜索」APP。请阅读并同意")
                        }
                        withStyle(SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize
                        )) {
                            append("《用户协议》")
                        }
                        withStyle(SpanStyle(fontSize = MaterialTheme.typography.bodyMedium.fontSize)) {
                            append("和")
                        }
                        withStyle(SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize
                        )) {
                            append("《隐私政策》")
                        }
                        withStyle(SpanStyle(fontSize = MaterialTheme.typography.bodyMedium.fontSize)) {
                            append("后继续使用。")
                        }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "用户协议与隐私政策。欢迎使用搜索APP。请阅读并同意用户协议和隐私政策后继续使用。双击打开用户协议，上滑打开隐私政策。"
                        customActions = listOf(
                            CustomAccessibilityAction(
                                label = "查看用户协议",
                                action = {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://files.baichabuyu.asia/public/anysearch/agreement.html")
                                    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                    context.startActivity(intent)
                                    true
                                }
                            ),
                            CustomAccessibilityAction(
                                label = "查看隐私政策",
                                action = {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://files.baichabuyu.asia/public/anysearch/privacy.html")
                                    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                    context.startActivity(intent)
                                    true
                                }
                            )
                        )
                    },
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 焦点2：复选框
                Row(
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "已阅读并同意用户协议和隐私政策，复选框，${if (checked) "已勾选" else "未勾选"}"
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { checked = it },
                        modifier = Modifier.semantics {
                            contentDescription = "已阅读并同意用户协议和隐私政策，${if (checked) "已勾选" else "未勾选"}"
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "已阅读并同意《用户协议》和《隐私政策》",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { checked = !checked }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 焦点3：同意/不同意按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDisagree,
                        modifier = Modifier.weight(1f).semantics {
                            contentDescription = "不同意，退出应用"
                        }
                    ) {
                        Text("不同意")
                    }

                    Button(
                        onClick = { if (checked) onAgree() },
                        enabled = checked,
                        modifier = Modifier.weight(1f).semantics {
                            contentDescription = if (checked) "同意，继续使用" else "请先勾选复选框"
                        }
                    ) {
                        Text("同意")
                    }
                }
            }
        }
    }
}

/**
 * 搜索历史记录页面
 * 展示最近 100 条搜索记录，点击可跳转回当时的搜索结果
 */
@Composable
private fun HistoryPage(
    historyItems: List<SearchHistoryItem>,
    onBack: () -> Unit,
    onItemClick: (SearchHistoryItem) -> Unit,
    onReSearch: (String) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onClearAll: () -> Unit
) {
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        view.announceForAccessibility("历史记录页面，共${historyItems.size}条记录")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).focusRequester(focusRequester).semantics { traversalIndex = 1f }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回主页")
            }
            Text("历史记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading(); traversalIndex = 2f })
            Spacer(modifier = Modifier.weight(1f))
            if (historyItems.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.semantics { traversalIndex = 3f }
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清空全部", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        HorizontalDivider()

        if (historyItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("暂无搜索记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("搜索后记录将自动保存在这里", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().semantics {
                    collectionInfo = androidx.compose.ui.semantics.CollectionInfo(rowCount = historyItems.size, columnCount = 1)
                    traversalIndex = 4f
                },
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(historyItems) { index, item ->
                    HistoryItemCard(
                        item = item,
                        itemIndex = index,
                        totalItems = historyItems.size,
                        onClick = { onItemClick(item) },
                        onReSearch = { onReSearch(item.query) },
                        onDelete = { onDeleteItem(item.id) }
                    )
                }
            }
        }
    }
}

/**
 * 单条历史记录卡片
 * 点击跳转到历史搜索结果，下滑有"重新搜索"和"删除"操作
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryItemCard(
    item: SearchHistoryItem,
    itemIndex: Int,
    totalItems: Int,
    onClick: () -> Unit,
    onReSearch: () -> Unit,
    onDelete: () -> Unit
) {
    val resultCount = item.results.size
    val desc = buildString {
        append("第${itemIndex + 1}项，共${totalItems}项。")
        append("搜索：${item.query}")
        if (resultCount > 0) append("，${resultCount}条结果")
        if (item.vertical != null) append("，垂直领域：${com.anysearch.app.data.VerticalDomain.chineseName(item.vertical)}")
        append("，双击查看历史搜索结果")
    }

    val view = LocalView.current
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = desc
                collectionItemInfo = androidx.compose.ui.semantics.CollectionItemInfo(
                    rowIndex = itemIndex, rowSpan = 1,
                    columnIndex = 0, columnSpan = 1
                )
                customActions = listOf(
                    CustomAccessibilityAction(
                        label = "重新发起搜索",
                        action = { onReSearch(); true }
                    ),
                    CustomAccessibilityAction(
                        label = "删除此条记录",
                        action = { onDelete(); view.announceForAccessibility("已删除"); true }
                    )
                )
            },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    item.query,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                if (resultCount > 0) {
                    Text("${resultCount}条结果", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (item.vertical != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("垂直: ${com.anysearch.app.data.VerticalDomain.chineseName(item.vertical)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    formatTimestamp(item.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 格式化时间戳为友好字符串
 */
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86400_000 -> "${diff / 3600_000}小时前"
        diff < 604800_000 -> "${diff / 86400_000}天前"
        else -> {
            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}

/**
 * 富文本内容渲染器
 * 根据 ContentBlock 的 type 字段渲染不同的 UI 组件
 * 支持：text, heading, list, link, button, divider, html, quote, code, bold, italic, spacer, color, center, image
 */
@Composable
private fun RichContentRenderer(
    blocks: List<ContentBlock>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block.type) {
                "text" -> Text(text = block.content ?: "", style = MaterialTheme.typography.bodyMedium)
                "heading" -> {
                    val style = when (block.level ?: 1) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(text = block.content ?: "", style = style, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                }
                "list" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items?.forEach { item ->
                            Row {
                                Text("• ", style = MaterialTheme.typography.bodyMedium)
                                Text(text = item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                "link" -> {
                    TextButton(onClick = {
                        block.url?.let { url ->
                            try {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "无法打开链接", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(block.text ?: block.content ?: "打开链接")
                    }
                }
                "button" -> {
                    Button(onClick = {
                        block.url?.let { url ->
                            try {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "无法打开", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(block.text ?: block.content ?: "点击")
                    }
                }
                "divider" -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                "html" -> {
                    block.content?.let { html ->
                        AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                    layoutParams = android.view.ViewGroup.LayoutParams(
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                        )
                    }
                }
                "quote" -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(text = block.content ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "code" -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = block.content ?: "", style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(12.dp))
                    }
                }
                "bold" -> Text(text = block.content ?: "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                "italic" -> Text(text = block.content ?: "", style = MaterialTheme.typography.bodyMedium, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                "spacer" -> Spacer(modifier = Modifier.height(((block.level ?: 1) * 8).dp))
                "color" -> Text(text = block.content ?: "", style = MaterialTheme.typography.bodyMedium, color = try { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(block.url ?: "#000000")) } catch (e: Exception) { MaterialTheme.colorScheme.primary })
                "center" -> Text(text = block.content ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                "image" -> {
                    block.url?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = block.content ?: "图片",
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
                else -> Text(text = block.content ?: "[未知类型: ${block.type}]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 解析富文本内容
 * 如果是 JSON 格式的 RichContent，解析返回 blocks
 * 如果是纯文本，包装为单个 text block
 */
private fun parseRichContent(raw: String): List<ContentBlock> {
    return try {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val rich = json.decodeFromString<RichContent>(raw)
        rich.blocks
    } catch (e: Exception) {
        listOf(ContentBlock(type = "text", content = raw))
    }
}
