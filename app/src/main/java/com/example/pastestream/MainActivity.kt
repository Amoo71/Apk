package com.example.pastestream

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PASTE_SOURCE_URL = "https://justpaste.it/myqhs"
private const val CACHE_FILE_NAME = "content_cache.json"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PasteStreamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PasteStreamApp()
                }
            }
        }
    }
}

@Composable
private fun PasteStreamTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        background = androidx.compose.ui.graphics.Color(0xFF0B0B0F),
        surface = androidx.compose.ui.graphics.Color(0xFF121218),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1B1B23),
        primary = androidx.compose.ui.graphics.Color(0xFF8AB4FF),
        secondary = androidx.compose.ui.graphics.Color(0xFFB8C0FF),
        onBackground = androidx.compose.ui.graphics.Color(0xFFEDEDF3),
        onSurface = androidx.compose.ui.graphics.Color(0xFFEDEDF3),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB5B7C4)
    )

    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content
    )
}

private sealed interface AppScreen {
    data object Home : AppScreen
    data class Browser(val title: String, val pageUrl: String) : AppScreen
    data class Player(val title: String, val mediaUrl: String) : AppScreen
}

private data class ContentItem(
    val title: String,
    val coverUrl: String,
    val servers: List<ServerOption>
)

private data class ServerOption(
    val label: String,
    val url: String
)

private data class CachePayload(
    val savedAt: Long,
    val items: List<ContentItem>
)

@Composable
private fun PasteStreamApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    var allItems by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedItem by remember { mutableStateOf<ContentItem?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastUpdated by remember { mutableStateOf<Long?>(null) }

    fun applyLoadedData(items: List<ContentItem>, timestamp: Long?) {
        allItems = items
        lastUpdated = timestamp
        errorMessage = null
    }

    suspend fun loadFromCache() {
        val cache = readCache(context)
        if (cache != null) {
            applyLoadedData(cache.items, cache.savedAt)
        }
    }

    fun refresh(forceShowLoader: Boolean = true) {
        scope.launch {
            if (forceShowLoader) {
                isRefreshing = true
            }
            try {
                val raw = fetchPasteSourceText(PASTE_SOURCE_URL)
                val items = parsePasteSource(raw)
                if (items.isEmpty()) {
                    errorMessage = "No content found in source."
                } else {
                    writeCache(context, items)
                    applyLoadedData(items, System.currentTimeMillis())
                }
            } catch (t: Throwable) {
                errorMessage = t.message ?: "Failed to refresh content."
            } finally {
                isInitialLoading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadFromCache()
        isInitialLoading = false
        if (allItems.isEmpty()) {
            refresh(forceShowLoader = true)
        }
    }

    val filteredItems = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) {
            allItems
        } else {
            allItems.filter {
                it.title.contains(searchQuery.trim(), ignoreCase = true)
            }
        }
    }

    when (val current = screen) {
        AppScreen.Home -> {
            HomeScreen(
                items = filteredItems,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onRefresh = { refresh(forceShowLoader = true) },
                onItemClick = { selectedItem = it },
                isRefreshing = isRefreshing,
                isInitialLoading = isInitialLoading,
                errorMessage = errorMessage,
                lastUpdated = lastUpdated
            )
        }

        is AppScreen.Browser -> {
            BrowserScreen(
                title = current.title,
                initialUrl = current.pageUrl,
                onBack = { screen = AppScreen.Home },
                onOpenPlayer = { mediaUrl ->
                    screen = AppScreen.Player(
                        title = current.title,
                        mediaUrl = mediaUrl
                    )
                }
            )
        }

        is AppScreen.Player -> {
            PlayerScreen(
                title = current.title,
                mediaUrl = current.mediaUrl,
                onBack = { screen = AppScreen.Home }
            )
        }
    }

    selectedItem?.let { item ->
        ContentDialog(
            item = item,
            onDismiss = { selectedItem = null },
            onServerClick = { server ->
                selectedItem = null
                screen = if (isDirectMediaUrl(server.url)) {
                    AppScreen.Player(item.title, server.url)
                } else {
                    AppScreen.Browser(item.title, server.url)
                }
            }
        )
    }
}

@Composable
private fun HomeScreen(
    items: List<ContentItem>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onItemClick: (ContentItem) -> Unit,
    isRefreshing: Boolean,
    isInitialLoading: Boolean,
    errorMessage: String?,
    lastUpdated: Long?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None
                ),
                shape = RoundedCornerShape(18.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Button(
                onClick = onRefresh,
                shape = RoundedCornerShape(18.dp),
                enabled = !isRefreshing
            ) {
                Text(if (isRefreshing) "..." else "Refresh")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = lastUpdated?.let { "Last updated: ${formatTimestamp(it)}" } ?: "Last updated: —",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                color = androidx.compose.ui.graphics.Color(0xFFFF8A8A),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            if (items.isEmpty() && (isRefreshing || isInitialLoading)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (items.isEmpty()) {
                Text(
                    text = "No content available.",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items) { item ->
                        ContentCard(
                            item = item,
                            onClick = { onItemClick(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentCard(
    item: ContentItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                modifier = Modifier
                    .size(width = 82.dp, height = 110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "${item.servers.size} server(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ContentDialog(
    item: ContentItem,
    onDismiss: () -> Unit,
    onServerClick: (ServerOption) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Text(
                text = item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Servers",
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                item.servers.forEachIndexed { index, server ->
                    Button(
                        onClick = { onServerClick(server) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(server.label.ifBlank { "Server ${index + 1}" })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserScreen(
    title: String,
    initialUrl: String,
    onBack: () -> Unit,
    onOpenPlayer: (String) -> Unit
) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var detectedMediaUrl by remember { mutableStateOf(if (isDirectMediaUrl(initialUrl)) initialUrl else "") }
    var isLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (webViewRef?.canGoBack() == true) {
                            webViewRef?.goBack()
                        } else {
                            onBack()
                        }
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Back")
                }

                Button(
                    onClick = { webViewRef?.reload() },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Reload")
                }

                Button(
                    onClick = {
                        val url = detectedMediaUrl.takeIf { it.isNotBlank() }
                            ?: currentUrl.takeIf { isDirectMediaUrl(it) }

                        if (url != null) {
                            onOpenPlayer(url)
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    enabled = detectedMediaUrl.isNotBlank() || isDirectMediaUrl(currentUrl)
                ) {
                    Text("Open in Player")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (isLoading) "Page status: loading..." else "Page status: ready",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Current page: ${shortenUrl(currentUrl)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (detectedMediaUrl.isBlank()) {
                    "Detected media: not yet detected"
                } else {
                    "Detected media: ${shortenUrl(detectedMediaUrl)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (detectedMediaUrl.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Divider()

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    setBackgroundColor(android.graphics.Color.BLACK)

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                    addJavascriptInterface(
                        MediaUrlBridge { found ->
                            detectedMediaUrl = found
                        },
                        "PasteStreamBridge"
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            return false
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            if (url.isBlank()) return false

                            currentUrl = url

                            if (!isAllowedHttpUrl(url)) {
                                return true
                            }

                            if (isDirectMediaUrl(url)) {
                                detectedMediaUrl = url
                            }

                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading = true
                            currentUrl = url.orEmpty()
                            if (isDirectMediaUrl(currentUrl)) {
                                detectedMediaUrl = currentUrl
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            currentUrl = url.orEmpty()

                            if (isDirectMediaUrl(currentUrl)) {
                                detectedMediaUrl = currentUrl
                            }

                            view?.evaluateJavascript(
                                """
                                (function() {
                                  try {
                                    var nodes = [];
                                    nodes = nodes.concat(Array.from(document.querySelectorAll('video')));
                                    nodes = nodes.concat(Array.from(document.querySelectorAll('audio')));
                                    nodes = nodes.concat(Array.from(document.querySelectorAll('source')));
                                    for (var i = 0; i < nodes.length; i++) {
                                      var src = nodes[i].src || nodes[i].currentSrc || nodes[i].getAttribute('src');
                                      if (src) {
                                        window.PasteStreamBridge.onMediaUrl(src);
                                        return;
                                      }
                                    }
                                  } catch (e) {}
                                })();
                                """.trimIndent(),
                                null
                            )
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val url = request?.url?.toString().orEmpty()
                            if (isDirectMediaUrl(url)) {
                                Handler(Looper.getMainLooper()).post {
                                    detectedMediaUrl = url
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    loadUrl(initialUrl)
                }
            },
            update = { view ->
                if (view.url != currentUrl && currentUrl.isNotBlank() && view.url.isNullOrBlank()) {
                    view.loadUrl(currentUrl)
                }
            }
        )
    }
}

@Composable
private fun PlayerScreen(
    title: String,
    mediaUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val exoPlayer = remember(mediaUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(mediaUrl))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onBack,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Back")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = shortenUrl(mediaUrl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    player = exoPlayer
                }
            },
            update = { view ->
                view.player = exoPlayer
            }
        )
    }
}
private class MediaUrlBridge(
    private val onFound: (String) -> Unit
) {
    @JavascriptInterface
    fun onMediaUrl(url: String?) {
        val safeUrl = url?.trim().orEmpty()
        if (safeUrl.isNotBlank() && isAllowedHttpUrl(safeUrl)) {
            Handler(Looper.getMainLooper()).post {
                onFound(safeUrl)
            }
        }
    }
}

private suspend fun fetchPasteSourceText(sourceUrl: String): String {
    return withContext(Dispatchers.IO) {
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
            )
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP error $code")
            }

            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            extractUsefulText(raw)
        } finally {
            connection.disconnect()
        }
    }
}

private fun extractUsefulText(raw: String): String {
    val normalized = raw.trim()
    if (looksLikePasteContent(normalized)) {
        return normalized
    }

    val stripped = normalized
        .replace(Regex("(?is)<script.*?>.*?</script>"), "\n")
        .replace(Regex("(?is)<style.*?>.*?</style>"), "\n")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n")
        .replace(Regex("(?i)</div>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("[ \t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    return stripped
}

private fun looksLikePasteContent(text: String): Boolean {
    val lower = text.lowercase(Locale.US)
    return lower.contains("titel:") || lower.contains("title:") || lower.contains("\ns:")
}

private fun parsePasteSource(text: String): List<ContentItem> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()

    val blocks = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    var inBlock = false

    fun flush() {
        if (current.isNotEmpty()) {
            blocks += current.toList()
            current = mutableListOf()
        }
    }

    for (rawLine in lines) {
        val line = rawLine.trim()
        when (line) {
            "#" -> {
                flush()
                inBlock = true
            }
            "##" -> {
                flush()
                inBlock = false
            }
            else -> {
                if (inBlock && line.isNotBlank()) {
                    current += line
                }
            }
        }
    }
    flush()

    return blocks.mapNotNull { block ->
        var title = ""
        var cover = ""
        val serverTokens = mutableListOf<String>()

        for (line in block) {
            when {
                line.startsWith("Titel:", ignoreCase = true) -> {
                    title = line.substringAfter(":").trim()
                }
                line.startsWith("Title:", ignoreCase = true) -> {
                    title = line.substringAfter(":").trim()
                }
                line.startsWith("Cover:", ignoreCase = true) -> {
                    cover = line.substringAfter(":").trim()
                }
                line.startsWith("S:", ignoreCase = true) -> {
                    serverTokens += line.substringAfter(":")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                }
                line.startsWith("Server:", ignoreCase = true) -> {
                    serverTokens += line.substringAfter(":")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                }
            }
        }

        if (title.isBlank() || cover.isBlank() || serverTokens.isEmpty()) {
            null
        } else {
            ContentItem(
                title = title,
                coverUrl = cover,
                servers = serverTokens.mapIndexedNotNull { index, token ->
                    parseServerToken(index, token)
                }
            )
        }
    }.filter { it.servers.isNotEmpty() }
}

private fun parseServerToken(index: Int, token: String): ServerOption? {
    val cleaned = token.trim()
    if (cleaned.isBlank()) return null

    val patterns = listOf("|", "->", "=>")
    for (separator in patterns) {
        if (cleaned.contains(separator)) {
            val parts = cleaned.split(separator, limit = 2).map { it.trim() }
            val label = parts.getOrNull(0).orEmpty()
            val url = parts.getOrNull(1).orEmpty()
            if (url.isNotBlank()) {
                return ServerOption(
                    label = if (label.isBlank()) fallbackServerLabel(url, index) else label,
                    url = url
                )
            }
        }
    }

    return ServerOption(
        label = fallbackServerLabel(cleaned, index),
        url = cleaned
    )
}

private fun fallbackServerLabel(url: String, index: Int): String {
    return try {
        val host = Uri.parse(url).host?.removePrefix("www.")
        if (!host.isNullOrBlank()) host else "Server ${index + 1}"
    } catch (_: Throwable) {
        "Server ${index + 1}"
    }
}

private fun isAllowedHttpUrl(url: String): Boolean {
    return try {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.US)
        scheme == "http" || scheme == "https"
    } catch (_: Throwable) {
        false
    }
}

private fun isDirectMediaUrl(url: String): Boolean {
    val lower = url.lowercase(Locale.US)

    if (!isAllowedHttpUrl(lower)) return false

    val directExtensions = listOf(
        ".m3u8", ".mp4", ".mkv", ".webm", ".m4v", ".mov", ".avi", ".mpd", ".ts", ".mp3", ".aac"
    )
    if (directExtensions.any { lower.contains(it) }) return true

    if (lower.contains("mime=video") || lower.contains("mime=audio")) return true
    if (lower.contains("/video/") || lower.contains("/stream/") || lower.contains("/playlist/")) return true

    return false
}

private fun shortenUrl(url: String, max: Int = 80): String {
    return if (url.length <= max) url else url.take(max - 1) + "…"
}

private fun formatTimestamp(timestamp: Long): String {
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))
    } catch (_: Throwable) {
        timestamp.toString()
    }
}

private suspend fun readCache(context: Context): CachePayload? {
    return withContext(Dispatchers.IO) {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        if (!file.exists()) return@withContext null

        runCatching {
            val root = JSONObject(file.readText())
            val savedAt = root.optLong("savedAt", 0L)
            val itemsArray = root.optJSONArray("items") ?: JSONArray()
            val items = buildList {
                for (i in 0 until itemsArray.length()) {
                    val obj = itemsArray.getJSONObject(i)
                    val serversJson = obj.optJSONArray("servers") ?: JSONArray()
                    val servers = buildList {
                        for (j in 0 until serversJson.length()) {
                            val serverObj = serversJson.getJSONObject(j)
                            add(
                                ServerOption(
                                    label = serverObj.optString("label"),
                                    url = serverObj.optString("url")
                                )
                            )
                        }
                    }
                    add(
                        ContentItem(
                            title = obj.optString("title"),
                            coverUrl = obj.optString("coverUrl"),
                            servers = servers
                        )
                    )
                }
            }
            CachePayload(savedAt = savedAt, items = items)
        }.getOrNull()
    }
}

private suspend fun writeCache(context: Context, items: List<ContentItem>) {
    withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("savedAt", System.currentTimeMillis())

        val itemsArray = JSONArray()
        items.forEach { item ->
            val itemObj = JSONObject()
            itemObj.put("title", item.title)
            itemObj.put("coverUrl", item.coverUrl)

            val serversArray = JSONArray()
            item.servers.forEach { server ->
                val serverObj = JSONObject()
                serverObj.put("label", server.label)
                serverObj.put("url", server.url)
                serversArray.put(serverObj)
            }

            itemObj.put("servers", serversArray)
            itemsArray.put(itemObj)
        }

        root.put("items", itemsArray)

        File(context.filesDir, CACHE_FILE_NAME).writeText(root.toString())
    }
}
