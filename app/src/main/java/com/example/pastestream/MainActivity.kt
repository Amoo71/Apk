package com.example.pastestream

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Html
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PASTE_SOURCE_URL = "https://justpaste.it/replace-with-your-paste-url"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PasteStreamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppScreen()
                }
            }
        }
    }
}

@Composable
private fun PasteStreamTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        background = Color(0xFF111315),
        surface = Color(0xFF181B1F),
        surfaceVariant = Color(0xFF20252B),
        primary = Color(0xFFAAC7FF),
        secondary = Color(0xFFBDC7DC),
        onPrimary = Color(0xFF08111F),
        onBackground = Color(0xFFE8ECF3),
        onSurface = Color(0xFFE8ECF3),
        onSurfaceVariant = Color(0xFFBBC4D0),
        outline = Color(0xFF313842),
    )

    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var selectedItem by remember { mutableStateOf<ContentItem?>(null) }
    var playerSession by remember { mutableStateOf<PlayerSession?>(null) }
    var browserSession by remember { mutableStateOf<BrowserSession?>(null) }
    var lastUpdated by remember { mutableLongStateOf(0L) }
    var showingCachedData by remember { mutableStateOf(false) }

    suspend fun refresh(forceMessage: Boolean = true) {
        isLoading = true
        if (forceMessage) {
            statusMessage = "Refreshing..."
        }
        try {
            val sourceText = withContext(Dispatchers.IO) {
                SourceRepository.fetchSourceText(PASTE_SOURCE_URL)
            }
            val parsedItems = SourceRepository.parseContentItems(sourceText)
            if (parsedItems.isEmpty()) {
                throw IllegalStateException("No valid content blocks found.")
            }
            withContext(Dispatchers.IO) {
                SourceRepository.writeCache(context.cacheDir, sourceText)
            }
            items = parsedItems
            showingCachedData = false
            lastUpdated = System.currentTimeMillis()
            statusMessage = "Loaded ${parsedItems.size} items."
        } catch (e: Exception) {
            statusMessage = e.message ?: "Refresh failed."
            showingCachedData = items.isNotEmpty()
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        val cachedSource = withContext(Dispatchers.IO) {
            SourceRepository.readCache(context.cacheDir)
        }
        if (cachedSource != null) {
            val parsedItems = SourceRepository.parseContentItems(cachedSource)
            if (parsedItems.isNotEmpty()) {
                items = parsedItems
                showingCachedData = true
                lastUpdated = SourceRepository.cacheLastModified(context.cacheDir)
                statusMessage = "Loaded ${parsedItems.size} cached items."
            }
        }
        if (items.isEmpty()) {
            refresh(forceMessage = false)
        }
    }

    val filteredItems = remember(query, items) {
        if (query.isBlank()) {
            items
        } else {
            items.filter { it.title.contains(query, ignoreCase = true) }
        }
    }

    when {
        playerSession != null -> {
            PlayerScreen(
                title = playerSession!!.title,
                url = playerSession!!.url,
                onClose = { playerSession = null },
            )
        }

        browserSession != null -> {
            BrowserScreen(
                title = browserSession!!.title,
                initialUrl = browserSession!!.url,
                onClose = { browserSession = null },
                onOpenPlayer = { mediaUrl ->
                    playerSession = PlayerSession(browserSession!!.title, mediaUrl)
                    browserSession = null
                },
            )
        }

        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        placeholder = {
                            Text("Search")
                        },
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = {
                            scope.launch { refresh() }
                        },
                        shape = RoundedCornerShape(18.dp),
                        enabled = !isLoading,
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text(if (isLoading) "..." else "Refresh")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                StatusStrip(
                    itemCount = filteredItems.size,
                    isLoading = isLoading,
                    lastUpdated = lastUpdated,
                    showingCachedData = showingCachedData,
                    statusMessage = statusMessage,
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (filteredItems.isEmpty() && !isLoading) {
                    EmptyState()
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            ContentCard(
                                item = item,
                                onClick = { selectedItem = item },
                            )
                        }
                    }
                }
            }
        }
    }

    if (selectedItem != null) {
        val item = selectedItem!!
        AlertDialog(
            onDismissRequest = { selectedItem = null },
            title = {
                Text(
                    text = item.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    AsyncImage(
                        model = item.coverUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop,
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Select a server",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    item.servers.forEachIndexed { index, serverUrl ->
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val normalized = serverUrl.trim()
                                    if (!URLUtil.isNetworkUrl(normalized)) {
                                        statusMessage = "Invalid server URL."
                                        return@launch
                                    }

                                    isLoading = true
                                    statusMessage = "Checking server..."
                                    val playable = withContext(Dispatchers.IO) {
                                        SourceRepository.resolvePlayableUrl(normalized)
                                    }
                                    isLoading = false

                                    selectedItem = null
                                    if (playable != null) {
                                        playerSession = PlayerSession(item.title, playable)
                                        statusMessage = "Opening player..."
                                    } else {
                                        browserSession = BrowserSession(item.title, normalized)
                                        statusMessage = "Opening source page..."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(serverLabel(index, serverUrl))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedItem = null }) {
                    Text("Close")
                }
            },
            shape = RoundedCornerShape(24.dp),
        )
    }
}

@Composable
private fun StatusStrip(
    itemCount: Int,
    isLoading: Boolean,
    lastUpdated: Long,
    showingCachedData: Boolean,
    statusMessage: String?,
) {
    val updatedText = if (lastUpdated > 0L) {
        "Last updated: ${formatTimestamp(lastUpdated)}"
    } else {
        "Last updated: -"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$itemCount items",
                style = MaterialTheme.typography.titleSmall,
            )
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        Text(
            text = updatedText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = if (showingCachedData) "Source: cache" else "Source: live",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!statusMessage.isNullOrBlank()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No items found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Check your source URL or refresh the list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContentCard(
    item: ContentItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.coverUrl,
            contentDescription = item.title,
            modifier = Modifier
                .size(width = 96.dp, height = 64.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${item.servers.size} server(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserScreen(
    title: String,
    initialUrl: String,
    onClose: () -> Unit,
    onOpenPlayer: (String) -> Unit,
) {
    var pageTitle by rememberSaveable(initialUrl) { mutableStateOf(title) }
    var currentUrl by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var detectedMediaUrl by rememberSaveable(initialUrl) {
        mutableStateOf(if (SourceRepository.isProbablyDirectMediaUrl(initialUrl)) initialUrl else null)
    }
    var browserStatus by rememberSaveable(initialUrl) {
        mutableStateOf(
            if (detectedMediaUrl != null) {
                "Direct media URL ready."
            } else {
                "Open the page and tap the video normally. When a direct media URL is detected, use Open in Player."
            }
        )
    }
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }

    fun registerMediaUrl(candidate: String?) {
        val normalized = candidate?.trim().orEmpty()
        if (!URLUtil.isNetworkUrl(normalized)) return
        if (!SourceRepository.isProbablyDirectMediaUrl(normalized)) return
        detectedMediaUrl = normalized
        browserStatus = "Detected direct media URL."
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewHolder.value?.apply {
                stopLoading()
                destroy()
            }
            webViewHolder.value = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onClose,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Back")
            }

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = pageTitle,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { webViewHolder.value?.reload() },
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Reload")
            }

            Button(
                onClick = { detectedMediaUrl?.let(onOpenPlayer) },
                enabled = detectedMediaUrl != null,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Open in Player")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = browserStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Page: $currentUrl",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (detectedMediaUrl == null) {
                    "Detected media: none yet"
                } else {
                    "Detected media: $detectedMediaUrl"
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        AndroidView(
            factory = { ctx ->
                val bridge = BrowserJsBridge { url ->
                    registerMediaUrl(url)
                }

                WebView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        allowFileAccess = false
                    }

                    addJavascriptInterface(bridge, "PasteStream")
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) {
                                pageTitle = title
                            }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val target = request?.url?.toString().orEmpty()
                            if (target.isBlank()) return false
                            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                                browserStatus = "Blocked non-web link."
                                return true
                            }
                            currentUrl = target
                            if (SourceRepository.isProbablyDirectMediaUrl(target)) {
                                registerMediaUrl(target)
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            if (!url.isNullOrBlank()) {
                                currentUrl = url
                                browserStatus = "Page loaded. Tap the video or play button if needed."
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (!url.isNullOrBlank()) {
                                currentUrl = url
                            }
                            view?.evaluateJavascript(MEDIA_DETECTOR_JS, null)
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            super.onLoadResource(view, url)
                            registerMediaUrl(url)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val target = request?.url?.toString()
                            if (!target.isNullOrBlank() && SourceRepository.isProbablyDirectMediaUrl(target)) {
                                view?.post { registerMediaUrl(target) }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    webViewHolder.value = this
                    loadUrl(initialUrl)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
    }
}

@Composable
private fun PlayerScreen(
    title: String,
    url: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var playbackState by remember(url) { mutableStateOf("Loading player...") }

    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        playbackState = when (state) {
                            Player.STATE_IDLE -> "Player idle"
                            Player.STATE_BUFFERING -> "Buffering..."
                            Player.STATE_READY -> "Ready"
                            Player.STATE_ENDED -> "Playback ended"
                            else -> "Loading player..."
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        playbackState = error.message ?: "Player error"
                    }
                },
            )
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onClose,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Back")
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    player = player
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black),
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = playbackState,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class ContentItem(
    val id: String,
    val title: String,
    val coverUrl: String,
    val servers: List<String>,
)

private data class PlayerSession(
    val title: String,
    val url: String,
)

private data class BrowserSession(
    val title: String,
    val url: String,
)

private class BrowserJsBridge(
    private val onMediaUrlDetected: (String) -> Unit,
) {
    @JavascriptInterface
    fun reportMediaUrl(url: String?) {
        if (!url.isNullOrBlank()) {
            onMediaUrlDetected(url)
        }
    }
}

private object SourceRepository {
    private const val CACHE_FILE_NAME = "source_cache.txt"
    private val directExtensions = listOf(".m3u8", ".mp4", ".webm", ".mkv", ".mpd", ".mov", ".m4v")
    private val directContentTypes = listOf(
        "video/",
        "audio/",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/dash+xml",
        "application/octet-stream",
    )

    fun cacheLastModified(cacheDir: File): Long {
        return File(cacheDir, CACHE_FILE_NAME).takeIf { it.exists() }?.lastModified() ?: 0L
    }

    fun readCache(cacheDir: File): String? {
        val file = File(cacheDir, CACHE_FILE_NAME)
        return if (file.exists()) file.readText() else null
    }

    fun writeCache(cacheDir: File, content: String) {
        File(cacheDir, CACHE_FILE_NAME).writeText(content)
    }

    fun fetchSourceText(url: String): String {
        require(URLUtil.isValidUrl(url)) { "Set a valid source URL in MainActivity.kt first." }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PasteStream/1.0")
        }

        return connection.inputStream.bufferedReader().use { reader ->
            extractPlainText(reader.readText())
        }
    }

    fun parseContentItems(rawText: String): List<ContentItem> {
        val normalized = rawText
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val lines = normalized.lines()
        val blocks = mutableListOf<List<String>>()
        var currentBlock = mutableListOf<String>()
        var collecting = false

        for (line in lines) {
            val trimmed = line.trim()
            when (trimmed) {
                "#" -> {
                    collecting = true
                    currentBlock = mutableListOf()
                }
                "##" -> {
                    if (collecting && currentBlock.isNotEmpty()) {
                        blocks += currentBlock.toList()
                    }
                    collecting = false
                    currentBlock = mutableListOf()
                }
                else -> if (collecting) {
                    currentBlock += trimmed
                }
            }
        }

        return blocks.mapNotNull { block ->
            val title = block.firstNotNullOfOrNull { line ->
                when {
                    line.startsWith("Titel:", ignoreCase = true) -> line.substringAfter(":").trim()
                    line.startsWith("Title:", ignoreCase = true) -> line.substringAfter(":").trim()
                    else -> null
                }
            }

            val cover = block.firstNotNullOfOrNull { line ->
                if (line.startsWith("Cover:", ignoreCase = true)) {
                    line.substringAfter(":").trim()
                } else {
                    null
                }
            }

            val servers = block.firstNotNullOfOrNull { line ->
                if (line.startsWith("S:", ignoreCase = true)) {
                    line.substringAfter(":")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                } else {
                    null
                }
            } ?: emptyList()

            if (title.isNullOrBlank() || cover.isNullOrBlank() || servers.isEmpty()) {
                null
            } else {
                ContentItem(
                    id = "${title}_${cover}".hashCode().toString(),
                    title = title,
                    coverUrl = cover,
                    servers = servers,
                )
            }
        }
    }

    fun resolvePlayableUrl(url: String): String? {
        if (!URLUtil.isValidUrl(url)) {
            return null
        }

        val lowerUrl = url.lowercase(Locale.ROOT)
        if (directExtensions.any { lowerUrl.contains(it) }) {
            return url
        }

        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PasteStream/1.0")
            }
            val contentType = connection.contentType?.lowercase(Locale.ROOT).orEmpty()
            if (directContentTypes.any { contentType.startsWith(it) || contentType.contains(it) }) {
                url
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isProbablyDirectMediaUrl(url: String, contentType: String? = null): Boolean {
        val lowerUrl = url.lowercase(Locale.ROOT)
        val normalizedContentType = contentType?.lowercase(Locale.ROOT).orEmpty()
        return directExtensions.any { lowerUrl.contains(it) } ||
            directContentTypes.any {
                normalizedContentType.startsWith(it) || normalizedContentType.contains(it)
            }
    }

    private fun extractPlainText(raw: String): String {
        if (!raw.contains("<html", ignoreCase = true)) {
            return raw
        }

        val body = Regex("(?is)<body.*?>(.*)</body>")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?: raw

        val cleaned = body
            .replace(Regex("(?is)<script.*?>.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?>.*?</style>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)</div>"), "\n")
            .replace(Regex("(?i)</li>"), "\n")

        return Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace("\u00A0", " ")
    }
}

private fun serverLabel(index: Int, url: String): String {
    return runCatching {
        URI(url).host
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?.let { "${index + 1}. $it" }
    }.getOrNull() ?: "Server ${index + 1}"
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    return formatter.format(Date(timestamp))
}

private val MEDIA_DETECTOR_JS = """
    (() => {
      try {
        const found = new Set();
        const add = (u) => {
          if (!u || typeof u !== 'string') return;
          const clean = u.trim();
          if (!clean) return;
          found.add(clean);
        };

        document.querySelectorAll('video, audio, source').forEach((el) => {
          add(el.currentSrc);
          add(el.src);
          add(el.getAttribute && el.getAttribute('src'));
        });

        document.querySelectorAll('a').forEach((el) => {
          const href = el.href || (el.getAttribute && el.getAttribute('href'));
          if (href) add(href);
        });

        Array.from(found).forEach((u) => window.PasteStream.reportMediaUrl(u));
      } catch (e) {}
    })();
""".trimIndent()
