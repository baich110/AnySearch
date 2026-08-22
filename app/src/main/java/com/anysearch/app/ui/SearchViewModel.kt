package com.anysearch.app.ui

import android.content.pm.PackageManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anysearch.app.data.*
import com.anysearch.app.data.AppLogger
import com.anysearch.app.data.db.PrivacyPreferences
import com.anysearch.app.data.db.SearchHistoryStore
import com.anysearch.app.data.db.SettingsStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SearchViewModel(private val appContext: android.content.Context? = null) : ViewModel() {

    private val apiKey = ""
    private val appVersion = getAppVersion()
    private val api = AnySearchApi(apiKey, appVersion)

    // 数据存储
    private val historyStore = appContext?.let { SearchHistoryStore(it) }
    private val privacyPrefs = appContext?.let { PrivacyPreferences(it) }
    private val settingsStore = appContext?.let { SettingsStore(it) }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun getAppVersion(): String {
        return try {
            appContext?.packageManager?.getPackageInfo(appContext.packageName, 0)?.versionName ?: "1.2.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.2.0"
        }
    }

    private fun getVersionCode(): Int {
        return try {
            appContext?.packageManager?.getPackageInfo(appContext.packageName, 0)?.longVersionCode?.toInt() ?: 6
        } catch (e: PackageManager.NameNotFoundException) {
            6
        }
    }

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        data class Results(val grouped: Map<String, List<SearchResult>>) : UiState()
        data class Extracting(val url: String) : UiState()
        data class Reading(
            val content: ExtractedContent,
            val fromUrl: String,
            val isTranslating: Boolean = false,
            val aiHtml: String? = null   // AI 排版后的 HTML（非 null 时用 WebView 渲染）
        ) : UiState()
        data class Browsing(val url: String, val fromResults: Boolean = true) : UiState()
        data class Error(val message: String) : UiState()
        object About : UiState()
        object History : UiState()  // 新增：历史记录页面
    }

    sealed class UiEvent {
        data class ShowError(val message: String) : UiEvent()
        data class ShowToast(val message: String) : UiEvent()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var lastResults: UiState.Results? = null

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _searchText = MutableStateFlow(TextFieldValue(""))
    val searchText: StateFlow<TextFieldValue> = _searchText.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    // 完整的历史记录（从 Room 读取）
    private val _fullHistory = MutableStateFlow<List<SearchHistoryItem>>(emptyList())
    val fullHistory: StateFlow<List<SearchHistoryItem>> = _fullHistory.asStateFlow()

    private val _verticals = MutableStateFlow<List<VerticalDomain>>(emptyList())
    val verticals: StateFlow<List<VerticalDomain>> = _verticals.asStateFlow()

    private val _selectedVertical = MutableStateFlow<String?>(value = null)
    val selectedVertical: StateFlow<String?> = _selectedVertical.asStateFlow()

    private val _focusedResultUrl = MutableStateFlow<String?>(value = null)
    val focusedResultUrl: StateFlow<String?> = _focusedResultUrl.asStateFlow()

    // 隐私协议同意状态（true=已同意，false=未同意，默认 true 避免闪一下）
    private val _privacyAgreed = MutableStateFlow(false)
    val privacyAgreed: StateFlow<Boolean> = _privacyAgreed.asStateFlow()

    // 渲染偏好：是否带链接（全局默认，从 DataStore 读）
    private val _renderLinks = MutableStateFlow(true)
    val renderLinks: StateFlow<Boolean> = _renderLinks.asStateFlow()

    // 阅读页临时渲染模式：null=跟随全局偏好，true/false=临时覆盖
    private val _tempRenderLinks = MutableStateFlow<Boolean?>(null)
    val tempRenderLinks: StateFlow<Boolean?> = _tempRenderLinks.asStateFlow()

    init {
        // 检查隐私协议状态
        viewModelScope.launch {
            privacyPrefs?.hasAgreed?.collect { agreed ->
                // 只在未同意时才更新状态，避免启动时闪一下
                if (!agreed) _privacyAgreed.value = false
            }
        }
        // 从 DataStore 加载历史记录
        viewModelScope.launch {
            historyStore?.historyFlow?.collect { items ->
                _fullHistory.value = items
            }
        }
        // 从 DataStore 读取渲染偏好（是否带链接）
        viewModelScope.launch {
            settingsStore?.renderLinks?.collect { value ->
                _renderLinks.value = value
                AppLogger.i("Settings", "渲染偏好(带链接): $value")
            }
        }
    }

    /** 设置全局渲染偏好（是否带链接），写入 DataStore 持久化 */
    fun setRenderLinksPreference(enableLinks: Boolean) {
        viewModelScope.launch {
            settingsStore?.setRenderLinks(enableLinks)
            _renderLinks.value = enableLinks
            AppLogger.i("Settings", "设置渲染偏好(带链接): $enableLinks")
        }
    }

    /**
     * 阅读页临时切换渲染模式（覆盖全局偏好，仅当前阅读生效）。
     * @param mode null=跟随全局偏好，true=临时带链接，false=临时纯文本
     */
    fun setTempRenderLinks(mode: Boolean?) {
        _tempRenderLinks.value = mode
    }

    /** 计算当前阅读页实际使用的渲染模式：优先临时覆盖，否则用全局偏好 */
    fun effectiveRenderLinks(): Boolean = _tempRenderLinks.value ?: _renderLinks.value

    /** 用户同意隐私协议 */
    fun agreeToPrivacy() {
        viewModelScope.launch {
            privacyPrefs?.setAgreed()
            _privacyAgreed.value = true
        }
    }

    /** 用户不同意隐私协议（退出应用） */
    fun disagreeToPrivacy() {
        // 由 UI 层调用 context 退出
        _events.trySend(UiEvent.ShowToast("需要同意隐私政策才能使用"))
    }

    fun updateSearchText(text: TextFieldValue) { _searchText.value = text }
    fun clearSearchText() { _searchText.value = TextFieldValue("") }
    fun bringCursorToEnd() {
        val text = _searchText.value.text
        _searchText.value = _searchText.value.copy(selection = TextRange(text.length))
    }
    fun selectVertical(key: String?) { _selectedVertical.value = key }

    fun search() {
        val text = _searchText.value.text.trim()
        if (text.isEmpty()) return
        val lines = text.lines().filter { it.trim().isNotEmpty() }
        val vertical = _selectedVertical.value
        AppLogger.i("Search", "发起搜索: 关键词=$text, 模式=${if (lines.size > 1) "多行并行(${lines.size})" else "单行"}, 垂直=${vertical ?: "无"}")

        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val results = if (lines.size == 1) {
                    val list = if (vertical != null) {
                        api.search(lines[0], domain = vertical)
                    } else {
                        api.search(lines[0])
                    }
                    mapOf(lines[0] to list)
                } else {
                    api.batchSearch(lines)
                }

                val total = results.values.sumOf { it.size }
                AppLogger.i("Search", "搜索完成: 共${total}条结果")

                // 更新最近搜索关键词列表（主页展示）
                _history.value = (listOf(text) + _history.value).distinct().take(20)

                // 保存到 DataStore
                val allResults = results.values.flatten()
                historyStore?.addEntry(text, allResults, vertical)

                val rs = UiState.Results(results)
                lastResults = rs
                _state.value = rs
            } catch (e: AnySearchApiException) {
                AppLogger.e("Search", "搜索失败(status=${e.statusCode}): ${e.message}")
                _state.value = UiState.Error(e.message ?: "搜索失败")
                _events.send(UiEvent.ShowError(e.message ?: "搜索失败"))
            } catch (e: Exception) {
                AppLogger.e("Search", "搜索异常", e)
                _state.value = UiState.Error("搜索失败，请检查网络连接")
                _events.send(UiEvent.ShowError("搜索失败: ${e.message}"))
            }
        }
    }

    /** 从历史记录中加载搜索结果 */
    private var _fromHistory = false

    fun loadFromHistory(item: SearchHistoryItem) {
        _fromHistory = true
        val grouped = mapOf(item.query to item.results)
        val rs = UiState.Results(grouped)
        lastResults = rs
        _state.value = rs
    }

    fun backFromHistoryResults() {
        if (_fromHistory) {
            _fromHistory = false
            _state.value = UiState.History
        } else {
            reset()
        }
    }

    /** 从历史记录重新发起搜索 */
    fun reSearchFromHistory(query: String) {
        _searchText.value = TextFieldValue(query)
        search()
    }

    /** 删除单条历史记录 */
    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            historyStore?.deleteEntry(id)
        }
    }

    /** 清空所有历史记录 */
    fun clearAllHistory() {
        viewModelScope.launch {
            historyStore?.clearAll()
        }
    }

    /** 显示历史记录页面 */
    fun showHistory() {
        _state.value = UiState.History
    }

    fun loadVerticals() {
        viewModelScope.launch {
            try {
                val domains = api.getSubDomains(listOf(
                    "security", "academic", "code", "finance", "legal", "health",
                    "business", "social_media", "travel", "film", "gaming",
                    "energy", "environment", "agriculture", "ip", "resource"
                ))
                _verticals.value = domains
            } catch (e: Exception) {
                _verticals.value = listOf(
                    "security", "academic", "code", "finance", "legal", "health",
                    "business", "social_media", "travel", "film", "gaming",
                    "energy", "environment", "agriculture", "ip", "resource"
                ).map { VerticalDomain(it, VerticalDomain.chineseName(it), "") }
            }
        }
    }

    fun extractContent(url: String) {
        if (_state.value is UiState.Results) lastResults = _state.value as UiState.Results
        _focusedResultUrl.value = url
        AppLogger.i("Read", "提取正文: $url")
        viewModelScope.launch {
            _state.value = UiState.Extracting(url)
            try {
                val content = api.extract(url)
                AppLogger.i("Read", "提取成功: 标题=${content.title}, 长度=${content.markdown.length}")
                _state.value = UiState.Reading(content, url, isTranslating = false)
                if (isMostlyEnglish(content.markdown)) {
                    AppLogger.i("Read", "正文主要为英文，自动触发翻译")
                    translateContent()
                }
            } catch (e: Exception) {
                AppLogger.e("Read", "提取失败: $url", e)
                _state.value = UiState.Error(e.message ?: "提取失败")
                _events.send(UiEvent.ShowError("提取失败: ${e.message}"))
            }
        }
    }

    /**
     * AI 智能排版：把当前阅读内容交给商汤模型，排版成精美 HTML。
     * 走 OkHttp 协程，速度快。成功后 Reading.aiHtml 被设置，UI 用 WebView 渲染。
     */
    fun aiFormatContent() {
        val current = _state.value as? UiState.Reading ?: return
        if (current.isTranslating) return
        AppLogger.i("AIFormat", "开始 AI 排版，原文长度=${current.content.markdown.length}")
        viewModelScope.launch {
            _state.value = current.copy(isTranslating = true)
            try {
                val html = api.aiFormatContent(
                    title = current.content.title.ifBlank { current.fromUrl },
                    rawMarkdown = current.content.markdown
                )
                AppLogger.i("AIFormat", "AI 排版完成，HTML 长度=${html.length}")
                _state.value = current.copy(isTranslating = false, aiHtml = html)
            } catch (e: Exception) {
                AppLogger.e("AIFormat", "AI 排版失败", e)
                _state.value = current.copy(isTranslating = false)
                _events.send(UiEvent.ShowError("AI 排版失败: ${e.message}"))
            }
        }
    }

    fun extractAndTranslate(url: String) {
        if (_state.value is UiState.Results) lastResults = _state.value as UiState.Results
        _focusedResultUrl.value = url
        viewModelScope.launch {
            _state.value = UiState.Extracting(url)
            try {
                val content = api.extract(url)
                _state.value = UiState.Reading(content, url, isTranslating = true)
                val translatedMarkdown = api.translateParallel(content.markdown)
                val translatedContent = content.copy(markdown = translatedMarkdown)
                _state.value = UiState.Reading(translatedContent, url, isTranslating = false)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "提取失败")
                _events.send(UiEvent.ShowError("提取失败: ${e.message}"))
            }
        }
    }

    fun openInBrowser(url: String) {
        if (_state.value is UiState.Results) lastResults = _state.value as UiState.Results
        _focusedResultUrl.value = url
        _state.value = UiState.Browsing(url, fromResults = true)
    }

    fun backFromBrowser() {
        _state.value = lastResults ?: UiState.Idle
    }

    private fun isMostlyEnglish(text: String): Boolean {
        val englishChars = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val chineseChars = text.count { it in '一'..'鿿' }
        return englishChars > chineseChars * 3 && englishChars > 50
    }

    fun translateContent() {
        val current = _state.value as? UiState.Reading ?: return
        if (current.isTranslating) return
        AppLogger.i("Translate", "开始翻译，原文长度=${current.content.markdown.length}")
        viewModelScope.launch {
            _state.value = current.copy(isTranslating = true)
            try {
                val translatedMarkdown = api.translateParallel(current.content.markdown)
                AppLogger.i("Translate", "翻译完成，译后长度=${translatedMarkdown.length}")
                val translatedContent = current.content.copy(markdown = translatedMarkdown)
                _state.value = UiState.Reading(translatedContent, current.fromUrl, isTranslating = false)
            } catch (e: Exception) {
                AppLogger.e("Translate", "翻译失败", e)
                _state.value = current.copy(isTranslating = false)
                _events.send(UiEvent.ShowError("翻译失败: ${e.message}"))
            }
        }
    }

    fun backToResults() { _state.value = lastResults ?: UiState.Idle }
    fun clearFocusedResultUrl() { _focusedResultUrl.value = null }
    fun reset() { _state.value = UiState.Idle }
    fun showAbout() { _state.value = UiState.About }
    fun backToHome() { _state.value = UiState.Idle }

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(value = null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()
    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()
    private val _announcement = MutableStateFlow<String?>(value = null)
    val announcement: StateFlow<String?> = _announcement.asStateFlow()
    private val _announcementLoading = MutableStateFlow(false)
    val announcementLoading: StateFlow<Boolean> = _announcementLoading.asStateFlow()

    fun checkUpdate() {
        if (_checkingUpdate.value) return
        AppLogger.i("Update", "检查更新，当前versionCode=${getVersionCode()}")
        viewModelScope.launch {
            _checkingUpdate.value = true
            try {
                val info = api.checkUpdate(getVersionCode())
                if (info == null) {
                    AppLogger.w("Update", "检查更新失败(返回null)")
                    _events.send(UiEvent.ShowToast("检查更新失败，请检查网络"))
                } else {
                    _updateInfo.value = info
                    AppLogger.i("Update", "检查更新结果: 服务器=${info.versionName}(code=${info.versionCode}), 本机=${getVersionCode()}, hasUpdate=${info.hasUpdate}")
                    if (info.hasUpdate) {
                        _events.send(UiEvent.ShowToast("发现新版本 ${info.versionName}"))
                    } else {
                        _events.send(UiEvent.ShowToast("当前已是最新版本"))
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("Update", "检查更新异常", e)
                _events.send(UiEvent.ShowToast("检查更新失败: ${e.message}"))
            } finally {
                _checkingUpdate.value = false
            }
        }
    }

    fun loadAnnouncement() {
        if (_announcementLoading.value) return
        AppLogger.i("Update", "加载公告")
        viewModelScope.launch {
            _announcementLoading.value = true
            try {
                val raw = api.getAnnouncement()
                if (raw == null) {
                    AppLogger.w("Update", "公告为null")
                    _events.send(UiEvent.ShowToast("暂无公告"))
                } else {
                    _announcement.value = raw
                    AppLogger.i("Update", "公告加载成功，长度=${raw.length}")
                }
            } catch (e: Exception) {
                AppLogger.e("Update", "获取公告失败", e)
                _events.send(UiEvent.ShowError("获取公告失败: ${e.message}"))
            } finally {
                _announcementLoading.value = false
            }
        }
    }

    fun clearUpdateInfo() { _updateInfo.value = null }
    fun clearAnnouncement() { _announcement.value = null }

    override fun onCleared() {
        super.onCleared()
        _events.close()
    }
}

class SearchViewModelFactory(private val context: android.content.Context? = null) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SearchViewModel(context) as T
    }
}
