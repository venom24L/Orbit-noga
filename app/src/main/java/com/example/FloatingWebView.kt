package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PendingDownloadPrompt(
    val filename: String,
    val mimeType: String?,
    val originUrl: String,
    val onConfirm: () -> Unit
)

data class DetectedMediaItem(
    val url: String,
    val title: String,
    val width: Int = 0,
    val height: Int = 0
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FloatingWebViewContent(
    initialUrl: String = "",
    accentColor: Color,
    isMaximized: Boolean = false,
    onToggleMaximize: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onOpenExternal: ((String) -> Unit)? = null,
    onUrlNavigated: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val defaultHomeUrl = remember(context) { SearchEnginePreferences.getSelectedEngine(context).homeUrl }

    // Retrieve last visited URL from persistent store if initialUrl is blank
    val startingUrl = remember(initialUrl) {
        if (initialUrl.isNotBlank()) {
            initialUrl
        } else {
            BrowserStateManager.getLastVisitedUrl(context)
        }
    }

    var currentUrl by rememberSaveable { mutableStateOf(startingUrl) }
    var inputUrl by rememberSaveable { mutableStateOf(startingUrl) }
    var pageTitle by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isDesktopMode by rememberSaveable { mutableStateOf(BrowserStateManager.getDesktopMode(context)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isAddressBarFocused by remember { mutableStateOf(false) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Native Web file upload & microphone permissions for in-page actions (ChatGPT, DuckDuckGo, etc.)
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingWebPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var hoveredLink by remember { mutableStateOf<String?>(null) }
    var showEngineMenu by remember { mutableStateOf(false) }

    // AI & Web media downloader states
    val coroutineScope = rememberCoroutineScope()
    var lastTouchX by remember { mutableFloatStateOf(0f) }
    var lastTouchY by remember { mutableFloatStateOf(0f) }

    // Generic file chooser launcher for <input type="file"> on web pages
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intentData = result.data
            val uris: Array<Uri>? = when {
                intentData?.clipData != null -> {
                    val count = intentData.clipData!!.itemCount
                    (0 until count).map { intentData.clipData!!.getItemAt(it).uri }.toTypedArray()
                }
                intentData?.data != null -> arrayOf(intentData.data!!)
                else -> null
            }
            fileChooserCallback?.onReceiveValue(uris)
        } else {
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
    }

    // Audio recording runtime permission launcher for in-page WebRTC voice chat (e.g. ChatGPT Voice)
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingWebPermissionRequest?.grant(pendingWebPermissionRequest?.resources)
        } else {
            pendingWebPermissionRequest?.deny()
        }
        pendingWebPermissionRequest = null
    }

    // Download confirmation prompt state
    var pendingDownloadPrompt by remember { mutableStateOf<PendingDownloadPrompt?>(null) }
    var detectedMediaList by remember { mutableStateOf<List<DetectedMediaItem>?>(null) }
    var isScanningMedia by remember { mutableStateOf(false) }

    val triggerDownloadConfirmation: (String, String?, String?, String?, String?, String?, WebView?) -> Unit = remember(context) {
        { reqUrl: String, reqUserAgent: String?, reqContentDisposition: String?, reqMimeType: String?, reqSuggestedFilename: String?, reqReferer: String?, reqWebView: WebView? ->
            downloadFileUniversal(
                context = context,
                url = reqUrl,
                userAgent = reqUserAgent ?: webViewInstance?.settings?.userAgentString,
                contentDisposition = reqContentDisposition,
                mimeType = reqMimeType,
                suggestedFilename = reqSuggestedFilename,
                referer = reqReferer ?: webViewInstance?.url,
                webView = reqWebView ?: webViewInstance,
                skipConfirmation = false,
                onPromptConfirmation = { prompt ->
                    (context as? Activity)?.runOnUiThread {
                        pendingDownloadPrompt = prompt
                    }
                }
            )
        }
    }

    fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        val defaultEngine = SearchEnginePreferences.getSelectedEngine(context)
        if (trimmed.isEmpty()) return defaultEngine.homeUrl
        if (URLUtil.isValidUrl(trimmed) && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            return trimmed
        }
        if (trimmed.contains(".") && !trimmed.contains(" ") && !trimmed.startsWith("http")) {
            return "https://$trimmed"
        }
        val encodedQuery = Uri.encode(trimmed)
        return defaultEngine.searchUrlTemplate.replace("%s", encodedQuery)
    }

    fun navigateTo(url: String) {
        val target = normalizeUrl(url)
        currentUrl = target
        inputUrl = target
        errorMessage = null
        BrowserStateManager.saveLastVisitedUrl(context, target)
        onUrlNavigated?.invoke(target)
        webViewInstance?.loadUrl(target)
        focusManager.clearFocus()
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank() && initialUrl != currentUrl) {
            navigateTo(initialUrl)
        }
    }

    // Forcefully delete whatever link is on browser tab and refresh with speed dial link when requested
    val navigationRequest by BrowserStateManager.navigationRequests.collectAsState()
    LaunchedEffect(navigationRequest) {
        navigationRequest?.let { req ->
            val target = normalizeUrl(req.url)
            currentUrl = target
            inputUrl = target
            pageTitle = ""
            errorMessage = null
            isLoading = true
            loadingProgress = 0
            canGoBack = false
            canGoForward = false

            webViewInstance?.apply {
                stopLoading()
                clearHistory()
                if (url == target) {
                    reload()
                } else {
                    loadUrl(target)
                }
            }
            focusManager.clearFocus()
            BrowserStateManager.saveLastVisitedUrl(context, target)
            onUrlNavigated?.invoke(target)
        }
    }

    // Auto-refresh to the new engine's homepage whenever the user selects a new search engine
    val engineChangeTrigger by SearchEnginePreferences.engineChanges.collectAsState()
    LaunchedEffect(engineChangeTrigger) {
        if (engineChangeTrigger > 0L) {
            val newHome = SearchEnginePreferences.getSelectedEngine(context).homeUrl
            currentUrl = newHome
            inputUrl = newHome
            errorMessage = null
            onUrlNavigated?.invoke(newHome)
            webViewInstance?.loadUrl(newHome)
        }
    }

    fun openExternally(targetUrl: String) {
        val formattedUrl = normalizeUrl(targetUrl)
        if (onOpenExternal != null) {
            onOpenExternal(formattedUrl)
        } else {
            try {
                val uri = Uri.parse(formattedUrl)
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.launchUrl(context, uri)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (ex: Exception) {
                    Toast.makeText(context, "No browser found to open link.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        // Loading Progress Indicator
        val animatedProgress by animateFloatAsState(
            targetValue = loadingProgress / 100f,
            label = "WebViewProgress"
        )
        AnimatedVisibility(
            visible = isLoading && animatedProgress < 1f,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .padding(bottom = 2.dp),
                color = accentColor,
                trackColor = Color.Transparent
            )
        }

        // Top Address & Search Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF111422))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = accentColor,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            BasicTextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = Color.White,
                    fontSize = 12.sp
                ),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Go,
                    keyboardType = KeyboardType.Uri
                ),
                keyboardActions = KeyboardActions(
                    onGo = { navigateTo(inputUrl) }
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 5.dp)
            )
            if (inputUrl.isNotBlank()) {
                IconButton(
                    onClick = { inputUrl = "" },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = TextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (onToggleMaximize != null) {
                Spacer(modifier = Modifier.width(2.dp))
                IconButton(
                    onClick = { onToggleMaximize() },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = if (isMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = if (isMaximized) stringResource(R.string.overlay_restore) else stringResource(R.string.overlay_maximize),
                        tint = accentColor,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            if (onClose != null) {
                Spacer(modifier = Modifier.width(2.dp))
                IconButton(
                    onClick = { onClose() },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.overlay_close),
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Main WebView Viewport Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0C0F1A))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val existingWebView = BrowserStateManager.getRetainedWebView()
                    val webView = if (existingWebView != null) {
                        BrowserStateManager.detachFromParent(existingWebView)
                        existingWebView
                    } else {
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            isFocusable = true
                            isFocusableInTouchMode = true

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                                defaultTextEncodingName = "utf-8"
                                cacheMode = WebSettings.LOAD_DEFAULT
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                setSupportMultipleWindows(true)
                                javaScriptCanOpenWindowsAutomatically = true
                            }
                            BrowserStateManager.setRetainedWebView(this)
                        }
                    }

                    // Enable JavaScript interface for blob downloads and AI picture extraction
                    webView.addJavascriptInterface(
                        AndroidDownloadBridge(
                            onBlobReceived = { dataUrl, mimeType, suggestedFilename, isDirectSave ->
                                val headerMime = if (dataUrl.startsWith("data:")) {
                                    dataUrl.substringAfter("data:").substringBefore(';').substringBefore(',').trim()
                                } else null
                                val effectiveMime = headerMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" && it != "image/*" }
                                    ?: mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" && it != "image/*" }
                                    ?: "image/png"
                                val resolvedName = resolveDownloadFilename("data:$effectiveMime", null, effectiveMime, suggestedFilename)
                                if (isDirectSave) {
                                    saveDataUrlToDownloads(ctx, dataUrl, effectiveMime, resolvedName)
                                } else {
                                    (ctx as? Activity)?.runOnUiThread {
                                        pendingDownloadPrompt = PendingDownloadPrompt(
                                            filename = resolvedName,
                                            mimeType = effectiveMime,
                                            originUrl = if (dataUrl.startsWith("data:image")) dataUrl else "Generated Content",
                                            onConfirm = {
                                                saveDataUrlToDownloads(ctx, dataUrl, effectiveMime, resolvedName)
                                            }
                                        )
                                    }
                                }
                            },
                            onInterceptDownload = { url, filename ->
                                (ctx as? Activity)?.runOnUiThread {
                                    val isDirectFile = isDownloadableFileUrl(url)
                                    if (isDirectFile || url.startsWith("blob:") || url.startsWith("data:")) {
                                        triggerDownloadConfirmation(
                                            url,
                                            webView.settings.userAgentString,
                                            null,
                                            null,
                                            filename,
                                            webView.url,
                                            webView
                                        )
                                    } else {
                                        // Standard web link (e.g. AI chat search result), navigate to page
                                        webView.loadUrl(url)
                                    }
                                }
                            },
                            onImageDetectedAtPoint = { json ->
                                (ctx as? Activity)?.runOnUiThread {
                                    try {
                                        val obj = JSONObject(json)
                                        val url = obj.getString("url")
                                        val alt = obj.optString("alt", "")
                                        triggerDownloadConfirmation(
                                            url,
                                            webView.settings.userAgentString,
                                            null,
                                            "image/png",
                                            alt,
                                            webView.url,
                                            webView
                                        )
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            },
                            onFallbackHttpDownload = { url, filename ->
                                (ctx as? Activity)?.runOnUiThread {
                                    val isDirectFile = isDownloadableFileUrl(url)
                                    if (isDirectFile || url.startsWith("blob:") || url.startsWith("data:")) {
                                        triggerDownloadConfirmation(
                                            url,
                                            webView.settings.userAgentString,
                                            null,
                                            null,
                                            filename,
                                            webView.url,
                                            webView
                                        )
                                    } else {
                                        webView.loadUrl(url)
                                    }
                                }
                            },
                            onMediaScanned = { json ->
                                (ctx as? Activity)?.runOnUiThread {
                                    isScanningMedia = false
                                    try {
                                        val arr = JSONArray(json)
                                        val list = mutableListOf<DetectedMediaItem>()
                                        for (i in 0 until arr.length()) {
                                            val o = arr.getJSONObject(i)
                                            val u = o.getString("url")
                                            val t = o.optString("title", "AI Media ${i + 1}")
                                            val w = o.optInt("width", 0)
                                            val h = o.optInt("height", 0)
                                            list.add(DetectedMediaItem(url = u, title = t, width = w, height = h))
                                        }
                                        if (list.isNotEmpty()) {
                                            detectedMediaList = list
                                        } else {
                                            Toast.makeText(ctx, "No generated media found on this page", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        ),
                        "AndroidDownloadBridge"
                    )

                    // Download Listener for direct downloads and Content-Disposition headers
                    webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                        if (url.isNullOrBlank()) return@setDownloadListener
                        triggerDownloadConfirmation(
                            url,
                            userAgent ?: webView.settings.userAgentString,
                            contentDisposition,
                            mimetype,
                            null,
                            webView.url,
                            webView
                        )
                    }

                    webView.isFocusable = true
                    webView.isFocusableInTouchMode = true
                    webView.setOnTouchListener { v, event ->
                        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                            lastTouchX = event.x
                            lastTouchY = event.y
                            (v as? WebView)?.evaluateJavascript(INJECTED_DOWNLOAD_HOOK, null)
                        }
                        if (!v.hasFocus()) {
                            v.requestFocus()
                        }
                        false
                    }
                    webView.setOnHoverListener { v, _ ->
                        val hitResult = (v as? WebView)?.hitTestResult
                        val link = when (hitResult?.type) {
                            WebView.HitTestResult.SRC_ANCHOR_TYPE,
                            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> hitResult.extra
                            else -> null
                        }
                        hoveredLink = link
                        false
                    }

                    // Configure Clients
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                val cleanUrl = url.substringBefore('?').lowercase()
                                val isDownloadable = cleanUrl.endsWith(".apk") || cleanUrl.endsWith(".zip") || cleanUrl.endsWith(".pdf") ||
                                    cleanUrl.endsWith(".rar") || cleanUrl.endsWith(".7z") || cleanUrl.endsWith(".tar.gz") || cleanUrl.endsWith(".tar") ||
                                    cleanUrl.endsWith(".iso") || cleanUrl.endsWith(".dmg") || cleanUrl.endsWith(".bin") || cleanUrl.endsWith(".gz") ||
                                    cleanUrl.endsWith(".epub") || cleanUrl.endsWith(".csv") || cleanUrl.endsWith(".mp3") || cleanUrl.endsWith(".m4a") ||
                                    cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".docx") || cleanUrl.endsWith(".xlsx") || cleanUrl.endsWith(".pptx") ||
                                    cleanUrl.endsWith(".doc") || cleanUrl.endsWith(".xls") || cleanUrl.endsWith(".ppt") || cleanUrl.endsWith(".txt") ||
                                    cleanUrl.endsWith(".png") || cleanUrl.endsWith(".jpg") || cleanUrl.endsWith(".jpeg") || cleanUrl.endsWith(".webp") ||
                                    cleanUrl.endsWith(".gif") || cleanUrl.endsWith(".svg") || cleanUrl.endsWith(".bmp") || cleanUrl.endsWith(".json")
                                if (isDownloadable) {
                                    triggerDownloadConfirmation(
                                        url,
                                        view?.settings?.userAgentString,
                                        null,
                                        null,
                                        null,
                                        webView.url,
                                        webView
                                    )
                                    return true
                                }
                                return false
                            }
                            if (url.startsWith("blob:") || url.startsWith("data:")) {
                                triggerDownloadConfirmation(
                                    url,
                                    view?.settings?.userAgentString,
                                    null,
                                    null,
                                    null,
                                    webView.url,
                                    webView
                                )
                                return true
                            }
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                ctx.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            return true
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            url?.let {
                                if (it.isNotBlank() && it != "about:blank") {
                                    currentUrl = it
                                    inputUrl = it
                                }
                            }
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                            errorMessage = null
                            url?.let {
                                if (it.isNotBlank() && it != "about:blank") {
                                    currentUrl = it
                                    inputUrl = it
                                    BrowserStateManager.saveLastVisitedUrl(ctx, it)
                                    onUrlNavigated?.invoke(it)
                                }
                            }
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                            view?.evaluateJavascript(INJECTED_DOWNLOAD_HOOK, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            url?.let {
                                if (it.isNotBlank() && it != "about:blank") {
                                    currentUrl = it
                                    inputUrl = it
                                    BrowserStateManager.saveLastVisitedUrl(ctx, it)
                                    onUrlNavigated?.invoke(it)
                                }
                            }
                            pageTitle = view?.title ?: ""
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true

                            // Persist full WebBackForwardList history into state bundle
                            BrowserStateManager.saveState(view)

                            view?.evaluateJavascript(
                                """
                                (function() {
                                    if (!document.querySelector('meta[name="viewport"]')) {
                                        var meta = document.createElement('meta');
                                        meta.name = 'viewport';
                                        meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';
                                        document.getElementsByTagName('head')[0].appendChild(meta);
                                    }
                                })();
                                """.trimIndent(),
                                null
                            )
                            view?.evaluateJavascript(INJECTED_DOWNLOAD_HOOK, null)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                isLoading = false
                                errorMessage = error?.description?.toString() ?: "Network error"
                            }
                        }
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            loadingProgress = newProgress
                            if (newProgress == 100) {
                                isLoading = false
                            }
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                            if (newProgress >= 25) {
                                view?.evaluateJavascript(INJECTED_DOWNLOAD_HOOK, null)
                            }
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            super.onReceivedTitle(view, title)
                            if (!title.isNullOrBlank()) {
                                pageTitle = title
                            }
                        }

                        override fun onShowFileChooser(
                            view: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            fileChooserCallback?.onReceiveValue(null)
                            fileChooserCallback = filePathCallback

                            val isMultiple = fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                            val acceptTypes = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
                            val isImageUpload = acceptTypes.isEmpty() ||
                                acceptTypes.any { it.contains("image", ignoreCase = true) || it == "*/*" }

                            val intent = if (isImageUpload) {
                                // Open Gallery instead of file explorer
                                Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                                    type = "image/*"
                                    if (isMultiple) {
                                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                    }
                                }
                            } else {
                                try {
                                    fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "*/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    }
                                } catch (e: Exception) {
                                    Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "*/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    }
                                }
                            }

                            try {
                                fileChooserLauncher.launch(intent)
                                return true
                            } catch (e: Exception) {
                                val fallback = if (isImageUpload) {
                                    Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "image/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        if (isMultiple) {
                                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                        }
                                    }
                                } else {
                                    Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "*/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    }
                                }
                                try {
                                    fileChooserLauncher.launch(fallback)
                                    return true
                                } catch (ex: Exception) {
                                    fileChooserCallback?.onReceiveValue(null)
                                    fileChooserCallback = null
                                    return false
                                }
                            }
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            if (request == null) return
                            val resources = request.resources
                            if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    request.grant(resources)
                                } else {
                                    pendingWebPermissionRequest = request
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            } else {
                                request.grant(resources)
                            }
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            if (resultMsg == null) return false
                            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                            val tempWebView = WebView(view?.context ?: return false).apply {
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        v: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val targetUrl = request?.url?.toString() ?: return false
                                        val clean = targetUrl.substringBefore('?').lowercase()
                                        if (targetUrl.startsWith("blob:") || targetUrl.startsWith("data:") ||
                                            clean.endsWith(".png") || clean.endsWith(".jpg") || clean.endsWith(".jpeg") ||
                                            clean.endsWith(".webp") || clean.endsWith(".gif") || clean.endsWith(".svg") ||
                                            clean.endsWith(".bmp") || clean.endsWith(".zip") || clean.endsWith(".pdf") ||
                                            clean.endsWith(".apk") || clean.endsWith(".rar") || clean.endsWith(".tar") ||
                                            clean.endsWith(".gz") || clean.endsWith(".csv") || clean.endsWith(".txt")
                                        ) {
                                            triggerDownloadConfirmation(
                                                targetUrl,
                                                webView.settings.userAgentString,
                                                null,
                                                null,
                                                null,
                                                webView.url,
                                                webView
                                            )
                                            return true
                                        }
                                        view?.loadUrl(targetUrl)
                                        return true
                                    }
                                }
                            }
                            transport.webView = tempWebView
                            resultMsg.sendToTarget()
                            return true
                        }
                    }

                    webView.setOnLongClickListener {
                        (webView as? WebView)?.evaluateJavascript(INJECTED_DOWNLOAD_HOOK, null)
                        val hitResult = webView.hitTestResult
                        val target = hitResult.extra
                        when (hitResult.type) {
                            WebView.HitTestResult.IMAGE_TYPE,
                            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                                if (!target.isNullOrBlank()) {
                                    triggerDownloadConfirmation(
                                        target,
                                        webView.settings.userAgentString,
                                        null,
                                        "image/*",
                                        null,
                                        webView.url,
                                        webView
                                    )
                                    true
                                } else false
                            }
                            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                                if (!target.isNullOrBlank()) {
                                    val cleanTarget = target.substringBefore('?').lowercase()
                                    val isDownloadable = cleanTarget.endsWith(".apk") || cleanTarget.endsWith(".zip") || cleanTarget.endsWith(".pdf") ||
                                        cleanTarget.endsWith(".rar") || cleanTarget.endsWith(".7z") || cleanTarget.endsWith(".tar.gz") ||
                                        cleanTarget.endsWith(".tar") || cleanTarget.endsWith(".iso") || cleanTarget.endsWith(".dmg") ||
                                        cleanTarget.endsWith(".bin") || cleanTarget.endsWith(".epub") || cleanTarget.endsWith(".csv") ||
                                        cleanTarget.endsWith(".mp3") || cleanTarget.endsWith(".m4a") || cleanTarget.endsWith(".mp4") ||
                                        cleanTarget.endsWith(".docx") || cleanTarget.endsWith(".xlsx") || cleanTarget.endsWith(".pptx") ||
                                        cleanTarget.endsWith(".doc") || cleanTarget.endsWith(".xls") || cleanTarget.endsWith(".ppt") ||
                                        cleanTarget.endsWith(".txt") || cleanTarget.endsWith(".png") || cleanTarget.endsWith(".jpg") ||
                                        cleanTarget.endsWith(".jpeg") || cleanTarget.endsWith(".webp") || cleanTarget.endsWith(".gif") ||
                                        cleanTarget.endsWith(".svg") || cleanTarget.endsWith(".bmp") || cleanTarget.endsWith(".json")

                                    if (isDownloadable) {
                                        triggerDownloadConfirmation(
                                            target,
                                            webView.settings.userAgentString,
                                            null,
                                            null,
                                            null,
                                            webView.url,
                                            webView
                                        )
                                    } else {
                                        openExternally(target)
                                    }
                                    true
                                } else false
                            }
                            else -> {
                                // For complex AI generation pages (Midjourney, ChatGPT, Copilot, Leonardo, Canvas renders)
                                val density = ctx.resources.displayMetrics.density
                                val cssX = (lastTouchX / density).toInt()
                                val cssY = (lastTouchY / density).toInt()
                                val js = """
                                    (function(x, y) {
                                        function getCleanUrl(u) {
                                            if (!u) return null;
                                            var match = u.match(/url\(['"]?(.*?)['"]?\)/);
                                            return match ? match[1] : u;
                                        }
                                        var elements = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [document.elementFromPoint(x, y)];
                                        for (var i = 0; i < elements.length; i++) {
                                            var el = elements[i];
                                            if (!el) continue;
                                            if (el.tagName === 'IMG' && (el.currentSrc || el.src)) {
                                                var src = el.currentSrc || el.src;
                                                if (src && !src.startsWith('data:image/svg') && src !== 'about:blank') {
                                                    window.AndroidDownloadBridge.onImageDetectedAtPoint(JSON.stringify({ url: src, alt: el.alt || 'AI Picture' }));
                                                    return;
                                                }
                                            }
                                            if (el.tagName === 'CANVAS') {
                                                try {
                                                    var cvData = el.toDataURL('image/png');
                                                    if (cvData && cvData.length > 200) {
                                                        window.AndroidDownloadBridge.onImageDetectedAtPoint(JSON.stringify({ url: cvData, alt: 'AI Canvas' }));
                                                        return;
                                                    }
                                                } catch(e) {}
                                            }
                                            try {
                                                var bg = window.getComputedStyle(el).backgroundImage;
                                                if (bg && bg !== 'none') {
                                                    var bgUrl = getCleanUrl(bg);
                                                    if (bgUrl && (bgUrl.startsWith('http') || bgUrl.startsWith('blob:') || bgUrl.startsWith('data:'))) {
                                                        window.AndroidDownloadBridge.onImageDetectedAtPoint(JSON.stringify({ url: bgUrl, alt: 'AI Background Image' }));
                                                        return;
                                                    }
                                                }
                                            } catch(e) {}
                                            var nestedImg = el.querySelector && el.querySelector('img');
                                            if (nestedImg && (nestedImg.currentSrc || nestedImg.src)) {
                                                var nSrc = nestedImg.currentSrc || nestedImg.src;
                                                window.AndroidDownloadBridge.onImageDetectedAtPoint(JSON.stringify({ url: nSrc, alt: nestedImg.alt || 'AI Picture' }));
                                                return;
                                            }
                                            var nestedCv = el.querySelector && el.querySelector('canvas');
                                            if (nestedCv) {
                                                try {
                                                    var nCvData = nestedCv.toDataURL('image/png');
                                                    if (nCvData && nCvData.length > 200) {
                                                        window.AndroidDownloadBridge.onImageDetectedAtPoint(JSON.stringify({ url: nCvData, alt: 'AI Canvas' }));
                                                        return;
                                                    }
                                                } catch(e) {}
                                            }
                                        }
                                    })($cssX, $cssY);
                                """.trimIndent()
                                webView.evaluateJavascript(js, null)
                                true
                            }
                        }
                    }

                    // Restore or initialize URL/State
                    val savedBundle = BrowserStateManager.getSavedBundle()
                    val currentNavReq = BrowserStateManager.navigationRequests.value
                    if (currentNavReq != null && currentNavReq.forceClearAndRefresh) {
                        val target = normalizeUrl(currentNavReq.url)
                        currentUrl = target
                        inputUrl = target
                        pageTitle = ""
                        errorMessage = null
                        isLoading = true
                        loadingProgress = 0
                        canGoBack = false
                        canGoForward = false
                        webView.stopLoading()
                        webView.clearHistory()
                        if (webView.url == target) {
                            webView.reload()
                        } else {
                            webView.loadUrl(target)
                        }
                    } else if (initialUrl.isNotBlank() && initialUrl != webView.url) {
                        val target = normalizeUrl(initialUrl)
                        currentUrl = target
                        inputUrl = target
                        webView.loadUrl(target)
                    } else if (webView.url.isNullOrBlank() || webView.url == "about:blank") {
                        if (savedBundle != null) {
                            webView.restoreState(savedBundle)
                        } else {
                            val startUrl = BrowserStateManager.getLastVisitedUrl(ctx)
                            currentUrl = startUrl
                            inputUrl = startUrl
                            webView.loadUrl(normalizeUrl(startUrl))
                        }
                    } else {
                        // Already active and retained!
                        currentUrl = webView.url ?: BrowserStateManager.getLastVisitedUrl(ctx)
                        inputUrl = currentUrl
                        pageTitle = webView.title ?: ""
                        canGoBack = webView.canGoBack()
                        canGoForward = webView.canGoForward()
                    }

                    BrowserStateManager.resumeWebView()
                    webViewInstance = webView
                    webView
                },
                update = { webView ->
                    webViewInstance = webView
                    val targetUserAgent = if (isDesktopMode) {
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    } else {
                        WebSettings.getDefaultUserAgent(context)
                    }
                    if (webView.settings.userAgentString != targetUserAgent) {
                        webView.settings.userAgentString = targetUserAgent
                        webView.reload()
                    }
                }
            )

            // Offline / Error Overlay
            if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1017))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = "Offline",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(id = R.string.browser_load_error),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = errorMessage ?: "Unable to load page.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    errorMessage = null
                                    webViewInstance?.reload()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(id = R.string.retry_label),
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(id = R.string.retry_label), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    val homeUrl = SearchEnginePreferences.getSelectedEngine(context).homeUrl
                                    errorMessage = null
                                    navigateTo(homeUrl)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222840)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "Home",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Home", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Bottom Navigation Bar Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF101322))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Home / Reset Button
                IconButton(
                    onClick = {
                        val homeUrl = SearchEnginePreferences.getSelectedEngine(context).homeUrl
                        errorMessage = null
                        currentUrl = homeUrl
                        inputUrl = homeUrl
                        BrowserStateManager.resetToHome(context, webViewInstance)
                        onUrlNavigated?.invoke(homeUrl)
                        Toast.makeText(context, context.getString(R.string.browser_reset_home), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.12f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = stringResource(id = R.string.browser_home),
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Back Button
                IconButton(
                    onClick = { webViewInstance?.goBack() },
                    enabled = canGoBack,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (canGoBack) Color.White else TextSecondary.copy(alpha = 0.35f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Forward Button
                IconButton(
                    onClick = { webViewInstance?.goForward() },
                    enabled = canGoForward,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (canGoForward) Color.White else TextSecondary.copy(alpha = 0.35f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Refresh / Stop Button
                IconButton(
                    onClick = {
                        if (isLoading) webViewInstance?.stopLoading() else webViewInstance?.reload()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                        contentDescription = if (isLoading) "Stop" else "Refresh",
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            val standingOnUrl = hoveredLink?.takeIf { it.isNotBlank() } ?: currentUrl
            val currentEngine = remember(context, engineChangeTrigger) {
                SearchEnginePreferences.getSelectedEngine(context)
            }

            // Middle Status: Current link user is standing on / viewing
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("URL", standingOnUrl)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied: $standingOnUrl", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = stringResource(id = R.string.browser_standing_on_link),
                    tint = accentColor.copy(alpha = 0.85f),
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = standingOnUrl.ifBlank { "about:blank" },
                    color = TextSecondary.copy(alpha = 0.95f),
                    fontSize = 10.5.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Small Search Engine Switcher Button with Dropdown Menu
                Box {
                    IconButton(
                        onClick = { showEngineMenu = true },
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = stringResource(id = R.string.browser_switch_engine),
                            tint = accentColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showEngineMenu,
                        onDismissRequest = { showEngineMenu = false },
                        modifier = Modifier
                            .background(Color(0xFF131728))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    ) {
                        SearchEnginePreferences.ENGINES.forEach { engine ->
                            val isSelected = engine.id == currentEngine.id
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = engine.name,
                                        color = if (isSelected) accentColor else Color.White,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 12.5.sp
                                    )
                                },
                                leadingIcon = {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = accentColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.size(16.dp))
                                    }
                                },
                                onClick = {
                                    showEngineMenu = false
                                    SearchEnginePreferences.setSelectedEngine(context, engine.id)
                                    navigateTo(engine.homeUrl)
                                    Toast.makeText(context, "Engine: ${engine.name}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }

                // Desktop Mode Toggle Button
                IconButton(
                    onClick = {
                        val nextMode = !isDesktopMode
                        isDesktopMode = nextMode
                        BrowserStateManager.setDesktopMode(context, nextMode)
                    },
                    modifier = Modifier
                        .size(28.dp)
                        .background(if (isDesktopMode) accentColor.copy(alpha = 0.25f) else Color.Transparent, CircleShape)
                ) {
                    Icon(
                        imageVector = if (isDesktopMode) Icons.Default.DesktopWindows else Icons.Default.PhoneAndroid,
                        contentDescription = "Toggle Desktop Mode",
                        tint = if (isDesktopMode) accentColor else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Open in External Default Browser Button
                IconButton(
                    onClick = { openExternally(currentUrl) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open in Default Browser",
                        tint = accentColor,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }

    // Confirmation dialog before downloading any file or media
    pendingDownloadPrompt?.let { prompt ->
        DownloadConfirmationDialog(
            prompt = prompt,
            accentColor = accentColor,
            onDismiss = { pendingDownloadPrompt = null }
        )
    }

    // Page Media Picker Sheet (for AI generated pictures and media scanned from the page)
    detectedMediaList?.let { mediaList ->
        PageMediaPickerSheet(
            mediaList = mediaList,
            accentColor = accentColor,
            onSelect = { item ->
                triggerDownloadConfirmation(
                    item.url,
                    webViewInstance?.settings?.userAgentString,
                    null,
                    "image/png",
                    item.title,
                    webViewInstance?.url,
                    webViewInstance
                )
            },
            onOpenDownloads = {
                try {
                    val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Downloads saved in Downloads folder", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { detectedMediaList = null }
        )
    }

    // State Preservation on Composable disposal (Keep history and state alive!)
    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.let { wv ->
                BrowserStateManager.saveState(wv)
                wv.url?.let { url ->
                    if (url.isNotBlank() && url != "about:blank") {
                        BrowserStateManager.saveLastVisitedUrl(context, url)
                    }
                }
                BrowserStateManager.pauseWebView()
                BrowserStateManager.detachFromParent(wv)
            }
            webViewInstance = null
        }
    }
}

@Composable
fun DownloadConfirmationDialog(
    prompt: PendingDownloadPrompt,
    accentColor: Color,
    onDismiss: () -> Unit
) {
    val isImg = prompt.originUrl.startsWith("data:image") ||
        prompt.mimeType?.startsWith("image/") == true ||
        prompt.filename.endsWith(".png", true) || prompt.filename.endsWith(".jpg", true) ||
        prompt.filename.endsWith(".jpeg", true) || prompt.filename.endsWith(".webp", true) ||
        prompt.filename.endsWith(".gif", true)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    prompt.onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.download_label), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Text(stringResource(R.string.cancel), color = Color.White.copy(alpha = 0.8f))
            }
        },
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f))
                    .border(1.dp, accentColor.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.download_file_confirm),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                if (isImg && (prompt.originUrl.startsWith("http") || prompt.originUrl.startsWith("data:image"))) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = prompt.originUrl,
                            contentDescription = prompt.filename,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Text(
                    text = prompt.filename,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                val fallbackWeb = stringResource(R.string.download_domain_web)
                val blobText = stringResource(R.string.download_domain_blob)
                val canvasText = stringResource(R.string.download_domain_canvas)
                val parsedHost = remember(prompt.originUrl) {
                    try {
                        if (prompt.originUrl.startsWith("http")) {
                            Uri.parse(prompt.originUrl).host
                        } else null
                    } catch (_: Exception) { null }
                }
                val domain = when {
                    parsedHost != null -> parsedHost
                    prompt.originUrl.startsWith("blob:") -> blobText
                    prompt.originUrl.startsWith("data:") -> canvasText
                    else -> fallbackWeb
                }
                val ext = prompt.filename.substringAfterLast('.', "").uppercase()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (ext.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accentColor.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = ext,
                                color = accentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = domain,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        containerColor = Color(0xFF1B1F2A),
        shape = RoundedCornerShape(22.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageMediaPickerSheet(
    mediaList: List<DetectedMediaItem>,
    accentColor: Color,
    onSelect: (DetectedMediaItem) -> Unit,
    onOpenDownloads: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1F2A),
        contentColor = Color.White,
        tonalElevation = 8.dp,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.4f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.page_media_title),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.page_media_detected_count, mediaList.size),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
                TextButton(onClick = {
                    onDismiss()
                    onOpenDownloads()
                }) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.downloads_title), color = accentColor, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
            ) {
                items(mediaList) { item ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(item)
                                onDismiss()
                            }
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = item.url,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (item.width > 0 && item.height > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(4.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.Black.copy(alpha = 0.75f))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "${item.width}×${item.height}",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.title,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(accentColor.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = stringResource(R.string.download_label),
                                        tint = accentColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class AndroidDownloadBridge(
    private val onBlobReceived: (String, String, String, Boolean) -> Unit,
    private val onInterceptDownload: (String, String) -> Unit,
    private val onImageDetectedAtPoint: (String) -> Unit,
    private val onFallbackHttpDownload: (String, String) -> Unit,
    private val onMediaScanned: (String) -> Unit
) {
    private val chunkBuffers = java.util.concurrent.ConcurrentHashMap<String, StringBuilder>()
    private val chunkMetadata = java.util.concurrent.ConcurrentHashMap<String, Triple<String, String, Boolean>>()

    @JavascriptInterface
    fun onChunkStart(transferId: String, totalLength: Int, mimeType: String, suggestedFilename: String, directSave: Boolean) {
        chunkBuffers[transferId] = StringBuilder(totalLength.coerceAtLeast(1024))
        chunkMetadata[transferId] = Triple(mimeType, suggestedFilename, directSave)
    }

    @JavascriptInterface
    fun onChunkPart(transferId: String, chunk: String) {
        chunkBuffers[transferId]?.append(chunk)
    }

    @JavascriptInterface
    fun onChunkEnd(transferId: String) {
        val buffer = chunkBuffers.remove(transferId)?.toString() ?: return
        val meta = chunkMetadata.remove(transferId)
        val mime = meta?.first ?: ""
        val filename = meta?.second ?: ""
        val direct = meta?.third ?: false
        onBlobReceived(buffer, mime, filename, direct)
    }

    @JavascriptInterface
    fun onBlobData(dataUrl: String, mimeType: String, suggestedFilename: String) {
        onBlobReceived(dataUrl, mimeType, suggestedFilename, false)
    }

    @JavascriptInterface
    fun onBlobDataDirectSave(dataUrl: String, mimeType: String, suggestedFilename: String) {
        onBlobReceived(dataUrl, mimeType, suggestedFilename, true)
    }

    @JavascriptInterface
    fun onInterceptDownload(url: String, filename: String) {
        onInterceptDownload(url, filename)
    }

    @JavascriptInterface
    fun onImageDetected(url: String, filename: String) {
        onInterceptDownload(url, filename)
    }

    @JavascriptInterface
    fun onImageDetectedAtPoint(json: String) {
        onImageDetectedAtPoint(json)
    }

    @JavascriptInterface
    fun onFallbackHttpDownload(url: String, filename: String) {
        onFallbackHttpDownload(url, filename)
    }

    @JavascriptInterface
    fun onMediaScanned(json: String) {
        onMediaScanned(json)
    }
}

const val INJECTED_DOWNLOAD_HOOK = """
(function() {
    if (window.__orbit_download_hooked) return;
    window.__orbit_download_hooked = true;

    // In-memory Blob registry to instantly retrieve Blobs created by ChatGPT, Claude, Gemini, Poe, DeepSeek, etc.
    var blobStore = new Map();
    window.__orbitBlobStore = blobStore;

    // 1. Hook URL.createObjectURL
    if (window.URL && window.URL.createObjectURL) {
        var origCreate = window.URL.createObjectURL;
        window.URL.createObjectURL = function(blob) {
            var u = origCreate.apply(this, arguments);
            try {
                if (blob) {
                    blobStore.set(u, blob);
                }
            } catch(e) {}
            return u;
        };
    }

    // 2. Prevent immediate revocation of blob URLs so async processing can complete
    if (window.URL && window.URL.revokeObjectURL) {
        var origRevoke = window.URL.revokeObjectURL;
        window.URL.revokeObjectURL = function(url) {
            setTimeout(function() {
                try { origRevoke.call(window.URL, url); } catch(e) {}
                try { blobStore.delete(url); } catch(e) {}
            }, 120000);
        };
    }

    function sendPayload(dataUrl, mime, filename, directSave) {
        if (!dataUrl || !window.AndroidDownloadBridge) return;
        var CHUNK_SIZE = 400000;
        if (dataUrl.length > CHUNK_SIZE && window.AndroidDownloadBridge.onChunkStart) {
            var tid = 'tid_' + Date.now() + '_' + Math.random().toString(36).substr(2, 6);
            window.AndroidDownloadBridge.onChunkStart(tid, dataUrl.length, mime || '', filename || '', !!directSave);
            for (var offset = 0; offset < dataUrl.length; offset += CHUNK_SIZE) {
                var chunk = dataUrl.substring(offset, offset + CHUNK_SIZE);
                window.AndroidDownloadBridge.onChunkPart(tid, chunk);
            }
            window.AndroidDownloadBridge.onChunkEnd(tid);
        } else {
            if (directSave && window.AndroidDownloadBridge.onBlobDataDirectSave) {
                window.AndroidDownloadBridge.onBlobDataDirectSave(dataUrl, mime || '', filename || '');
            } else {
                window.AndroidDownloadBridge.onBlobData(dataUrl, mime || '', filename || '');
            }
        }
    }

    function convertBlobToBase64(blob, mimeHint, filename, directSave) {
        var reader = new FileReader();
        reader.onloadend = function() {
            if (reader.result) {
                var actualMime = mimeHint || blob.type || 'image/png';
                sendPayload(reader.result, actualMime, filename || '', directSave);
            }
        };
        reader.readAsDataURL(blob);
    }

    function triggerNativeDownload(href, filename, directSave) {
        if (!href || !window.AndroidDownloadBridge) return;

        // A. Data URLs
        if (href.indexOf('data:') === 0) {
            var dataMime = '';
            var m = href.match(/^data:([^;,]+)/);
            if (m && m[1]) dataMime = m[1];
            sendPayload(href, dataMime, filename || '', directSave);
            return;
        }

        // B. Blob URLs
        if (href.indexOf('blob:') === 0) {
            if (blobStore.has(href)) {
                var storedBlob = blobStore.get(href);
                convertBlobToBase64(storedBlob, storedBlob.type, filename, directSave);
                return;
            }

            fetch(href)
                .then(function(res) {
                    var ct = (res.headers && res.headers.get('Content-Type')) || '';
                    return res.blob().then(function(b) {
                        return { blob: b, type: ct || b.type || '' };
                    });
                })
                .then(function(info) {
                    convertBlobToBase64(info.blob, info.type, filename, directSave);
                })
                .catch(function() {
                    try {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', href, true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            if (xhr.response) {
                                convertBlobToBase64(xhr.response, xhr.response.type || '', filename, directSave);
                            } else {
                                window.AndroidDownloadBridge.onInterceptDownload(href, filename || '');
                            }
                        };
                        xhr.onerror = function() {
                            window.AndroidDownloadBridge.onInterceptDownload(href, filename || '');
                        };
                        xhr.send();
                    } catch(e) {
                        window.AndroidDownloadBridge.onInterceptDownload(href, filename || '');
                    }
                });
            return;
        }

        // C. HTTP/HTTPS or other URLs
        window.AndroidDownloadBridge.onInterceptDownload(href, filename || '');
    }

    function extractMediaFromElement(container) {
        if (!container) return null;

        // Check canvases
        if (container.tagName === 'CANVAS') {
            try {
                var d = container.toDataURL('image/png');
                if (d && d.length > 200) return { url: d, name: 'orbit_canvas.png' };
            } catch(e) {}
        }
        var canvases = container.querySelectorAll ? container.querySelectorAll('canvas') : [];
        for (var c = 0; c < canvases.length; c++) {
            var cv = canvases[c];
            if (cv && cv.width > 60 && cv.height > 60) {
                try {
                    var cd = cv.toDataURL('image/png');
                    if (cd && cd.length > 200) return { url: cd, name: 'orbit_canvas.png' };
                } catch(e) {}
            }
        }

        // Check images: pick the largest image in the container
        var images = [];
        if (container.tagName === 'IMG') images.push(container);
        if (container.querySelectorAll) {
            var foundImgs = container.querySelectorAll('img');
            for (var j = 0; j < foundImgs.length; j++) images.push(foundImgs[j]);
        }
        var bestImg = null;
        var maxArea = 0;
        for (var k = 0; k < images.length; k++) {
            var img = images[k];
            var s = img.currentSrc || img.src || img.getAttribute('data-src') || img.getAttribute('src');
            if (!s || s.indexOf('data:image/svg') === 0 || s === 'about:blank') continue;
            var w = img.naturalWidth || img.width || img.offsetWidth || 0;
            var h = img.naturalHeight || img.height || img.offsetHeight || 0;
            if (w > 0 && h > 0 && (w < 40 || h < 40)) continue;
            var area = (w > 0 && h > 0) ? (w * h) : 500;
            if (area > maxArea) {
                maxArea = area;
                bestImg = { url: s, name: (img.alt || 'orbit_image').replace(/[^a-zA-Z0-9_\u0600-\u06FF\s-]/g, '_') };
            }
        }
        if (bestImg) return bestImg;

        // Check background-image
        var all = container.querySelectorAll ? container.querySelectorAll('*') : [];
        for (var b = 0; b < all.length; b++) {
            var node = all[b];
            try {
                var bg = window.getComputedStyle(node).backgroundImage;
                if (bg && bg !== 'none' && bg.indexOf('url(') !== -1) {
                    var m = bg.match(/url\(['"]?(.*?)['"]?\)/);
                    if (m && m[1] && (m[1].indexOf('http') === 0 || m[1].indexOf('blob:') === 0 || m[1].indexOf('data:') === 0)) {
                        if (m[1].indexOf('data:image/svg') !== 0) {
                            return { url: m[1], name: 'orbit_image' };
                        }
                    }
                }
            } catch(e) {}
        }
        return null;
    }

    function findMediaForButton(btn) {
        if (!btn) return null;

        // 1. Direct parent link ONLY if it actually has download attribute or points to media
        var parentLink = btn.closest ? btn.closest('a') : null;
        if (parentLink && parentLink.href) {
            var ph = parentLink.href;
            var isFile = /\.(png|jpe?g|webp|gif|svg|bmp|pdf|zip|apk|rar|7z|tar(\.gz)?|gz|mp3|mp4|m4a|wav)(\?.*)?$/i.test(ph);
            var hasDownload = parentLink.hasAttribute('download');
            if (hasDownload || ph.indexOf('blob:') === 0 || ph.indexOf('data:') === 0 || isFile) {
                return { url: ph, name: parentLink.getAttribute('download') || parentLink.download || 'download' };
            }
            // If parent link is a normal web URL, it is NOT media
        }

        // 2. Inside modal, lightbox or preview dialog
        var modal = btn.closest ? btn.closest('[role="dialog"], .modal, .lightbox, .fullscreen-preview, [data-testid*="modal"], [aria-modal="true"]') : null;
        if (modal) {
            var mm = extractMediaFromElement(modal);
            if (mm) return mm;
        }

        // 3. Image card or generation turn
        var card = btn.closest ? btn.closest('figure, .image-card, .generated-image, [class*="image-wrapper"], [data-testid*="image"]') : null;
        if (card) {
            var cm = extractMediaFromElement(card);
            if (cm) return cm;
        }

        // 4. Message container / Conversation turn (ChatGPT, Claude, Gemini, Poe, DeepSeek)
        var messageContainer = btn.closest ? btn.closest('article, [data-message-author-role], [data-testid*="conversation"], .message, [class*="message"], .group') : null;
        if (messageContainer) {
            var mcMedia = extractMediaFromElement(messageContainer);
            if (mcMedia) return mcMedia;
        }

        // 5. Immediate parent hierarchy up to 2 levels
        var curr = btn.parentElement;
        for (var depth = 0; depth < 2 && curr && curr !== document.body && curr !== document.documentElement; depth++) {
            var pMedia = extractMediaFromElement(curr);
            if (pMedia) return pMedia;
            curr = curr.parentElement;
        }

        return null;
    }

    // 3. Intercept programmatic anchor clicks for actual downloads
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
        try {
            var href = this.href || this.getAttribute('href');
            var download = this.getAttribute('download') || this.download;
            var hasDownload = this.hasAttribute('download') || (download !== null && download !== undefined && download !== '');
            var isBlobOrData = href && (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0);
            var isMediaExt = href && /\.(png|jpe?g|webp|gif|svg|bmp|pdf|zip|apk|rar|7z|tar(\.gz)?|gz|iso|dmg|bin|epub|mp3|mp4|m4a|wav|docx|xlsx|pptx)(\?.*)?${'$'}/i.test(href);

            if (isBlobOrData || hasDownload || isMediaExt) {
                if (href && href.indexOf('#') !== 0 && href.indexOf('javascript:') !== 0) {
                    triggerNativeDownload(href, download || '');
                    return;
                }
            }
        } catch(e) {}
        return origClick.apply(this, arguments);
    };

    // 4. Intercept dispatchEvent for anchor clicks
    var origDispatch = EventTarget.prototype.dispatchEvent;
    EventTarget.prototype.dispatchEvent = function(event) {
        try {
            if (this instanceof HTMLAnchorElement && event && event.type === 'click') {
                var href = this.href || this.getAttribute('href');
                var download = this.getAttribute('download') || this.download;
                var hasDownload = this.hasAttribute('download') || (download !== null && download !== undefined && download !== '');
                var isBlobOrData = href && (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0);
                var isMediaExt = href && /\.(png|jpe?g|webp|gif|svg|bmp|pdf|zip|apk|rar|7z|tar(\.gz)?|gz|iso|dmg|bin|epub|mp3|mp4|m4a|wav|docx|xlsx|pptx)(\?.*)?${'$'}/i.test(href);

                if (isBlobOrData || hasDownload || isMediaExt) {
                    if (href && href.indexOf('#') !== 0 && href.indexOf('javascript:') !== 0) {
                        triggerNativeDownload(href, download || '');
                        return false;
                    }
                }
            }
        } catch(e) {}
        return origDispatch.apply(this, arguments);
    };

    // 5. Intercept user clicks on real download buttons only (never normal links or search results)
    document.addEventListener('click', function(e) {
        try {
            var el = e.target;
            if (!el) return;

            // Priority 1: Check if clicked element is an anchor or inside an anchor
            var anchor = el.closest ? el.closest('a') : null;
            if (anchor && anchor.href) {
                var h = anchor.href;
                var hasDl = anchor.hasAttribute('download');
                var isBlobOrData = h.indexOf('blob:') === 0 || h.indexOf('data:') === 0;
                var isDirectMedia = /\.(png|jpe?g|webp|gif|svg|bmp|pdf|zip|apk|rar|7z|tar(\.gz)?|gz|iso|dmg|bin|epub|mp3|mp4|m4a|wav|docx|xlsx|pptx)(\?.*)?${'$'}/i.test(h);

                if (hasDl || isBlobOrData || isDirectMedia) {
                    triggerNativeDownload(h, anchor.getAttribute('download') || anchor.download || '');
                    e.preventDefault();
                    e.stopPropagation();
                    return;
                }
                // It is a regular web hyperlink (search result link, website, AI chat link)!
                // NEVER intercept as a download! Allow standard browser navigation!
                return;
            }

            // Priority 2: Only check if clicked element is an interactive button
            var isButtonLike = el.tagName === 'BUTTON' ||
                               (el.tagName === 'INPUT' && (el.type === 'button' || el.type === 'submit')) ||
                               el.getAttribute('role') === 'button' ||
                               (el.closest && el.closest('button, [role="button"]'));

            if (!isButtonLike) return;

            var btn = (el.tagName === 'BUTTON' || el.getAttribute('role') === 'button') ? el : (el.closest ? el.closest('button, [role="button"]') : el);
            if (!btn) return;

            var ariaLabel = (btn.getAttribute('aria-label') || btn.getAttribute('title') || '').trim();
            var testId = (btn.getAttribute('data-testid') || '').trim();
            var className = (typeof btn.className === 'string' ? btn.className : '').trim();
            var textContent = (btn.innerText || btn.textContent || '').trim();

            var dlRegex = /(^|\s)(download|save\s*image|download\s*image|export\s*image|download\s*file|تحميل|تنزيل|حفظ\s*الصورة|تصدير)(\s|${'$'})/i;
            var isDownloadBtn = (ariaLabel && dlRegex.test(ariaLabel)) ||
                                (testId && dlRegex.test(testId)) ||
                                /(download-btn|download-button|btn-download|save-button|btn-save)/i.test(className) ||
                                (textContent.length <= 30 && dlRegex.test(textContent));

            if (!isDownloadBtn) {
                var svg = btn.querySelector ? btn.querySelector('svg') : null;
                if (svg) {
                    var svgInfo = (svg.getAttribute('class') || '') + ' ' + (svg.getAttribute('aria-label') || '') + ' ' + (svg.getAttribute('data-icon') || '');
                    if (/download|arrow-down|lucide-download|fa-download/i.test(svgInfo)) {
                        isDownloadBtn = true;
                    }
                }
            }

            if (isDownloadBtn) {
                var media = findMediaForButton(btn);
                if (media && media.url) {
                    triggerNativeDownload(media.url, media.name || '');
                    e.preventDefault();
                    e.stopPropagation();
                    return;
                }
            }
        } catch(err) {}
    }, true);

    // 6. Intercept window.open for direct downloadable files only
    var origOpen = window.open;
    window.open = function(url) {
        if (url && (url.indexOf('blob:') === 0 || url.indexOf('data:') === 0 || /\.(png|jpe?g|webp|gif|svg|bmp|pdf|zip|apk|rar|7z|tar(\.gz)?|gz|iso|dmg|bin|epub|mp3|mp4|m4a|wav|docx|xlsx|pptx)(\?.*)?${'$'}/i.test(url))) {
            triggerNativeDownload(url, '');
            return null;
        }
        return origOpen.apply(this, arguments);
    };
})();
"""

fun detectRealExtensionAndMime(
    bytes: ByteArray,
    currentMime: String?,
    currentFilename: String
): Pair<String, String> {
    // 1. Magic Bytes detection for accurate format identification
    if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8 && (bytes[2].toInt() and 0xFF) == 0xFF) {
        return Pair("jpg", "image/jpeg")
    }
    if (bytes.size >= 8 && (bytes[0].toInt() and 0xFF) == 0x89 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()) {
        return Pair("png", "image/png")
    }
    if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()) {
        return Pair("webp", "image/webp")
    }
    if (bytes.size >= 4 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
        return Pair("gif", "image/gif")
    }
    if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
        return Pair("bmp", "image/bmp")
    }
    if (bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
        return Pair("pdf", "application/pdf")
    }
    if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) {
        val ext = if (currentFilename.endsWith(".apk", true)) "apk" else "zip"
        val mime = if (ext == "apk") "application/vnd.android.package-archive" else "application/zip"
        return Pair(ext, mime)
    }
    if (bytes.size >= 5) {
        val header = try {
            String(bytes.copyOfRange(0, minOf(bytes.size, 512)), Charsets.UTF_8).lowercase()
        } catch (e: Exception) { "" }
        if (header.contains("<svg") || (header.contains("<?xml") && header.contains("<svg"))) {
            return Pair("svg", "image/svg+xml")
        }
    }

    // 2. Check currentMime if concrete and clean
    val cleanMime = currentMime?.takeIf {
        it.isNotBlank() && it != "application/octet-stream" && it != "image/*" && it != "*/*"
    }
    if (cleanMime != null) {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(cleanMime)
            ?: when {
                cleanMime.contains("jpeg") || cleanMime.contains("jpg") -> "jpg"
                cleanMime.contains("png") -> "png"
                cleanMime.contains("webp") -> "webp"
                cleanMime.contains("gif") -> "gif"
                cleanMime.contains("svg") -> "svg"
                cleanMime.contains("pdf") -> "pdf"
                cleanMime.contains("zip") -> "zip"
                cleanMime.contains("json") -> "json"
                cleanMime.contains("csv") -> "csv"
                cleanMime.contains("text") -> "txt"
                else -> "bin"
            }
        return Pair(ext, cleanMime)
    }

    // 3. Check filename extension if valid and non-generic
    val ext = currentFilename.substringAfterLast('.', "").lowercase()
    if (ext.isNotBlank() && ext != "bin") {
        val mappedMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
        return Pair(ext, mappedMime)
    }

    if (currentMime?.startsWith("image/") == true) {
        return Pair("jpg", "image/jpeg")
    }

    return Pair("bin", "application/octet-stream")
}

fun isDownloadableFileUrl(url: String): Boolean {
    if (url.startsWith("blob:") || url.startsWith("data:")) return true
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".apk") || clean.endsWith(".zip") || clean.endsWith(".pdf") ||
        clean.endsWith(".rar") || clean.endsWith(".7z") || clean.endsWith(".tar.gz") ||
        clean.endsWith(".tar") || clean.endsWith(".gz") || clean.endsWith(".iso") ||
        clean.endsWith(".dmg") || clean.endsWith(".bin") || clean.endsWith(".epub") ||
        clean.endsWith(".png") || clean.endsWith(".jpg") || clean.endsWith(".jpeg") ||
        clean.endsWith(".webp") || clean.endsWith(".gif") || clean.endsWith(".bmp") ||
        clean.endsWith(".svg") || clean.endsWith(".mp3") || clean.endsWith(".m4a") ||
        clean.endsWith(".mp4") || clean.endsWith(".wav") || clean.endsWith(".docx") ||
        clean.endsWith(".xlsx") || clean.endsWith(".pptx")
}

fun resolveDownloadFilename(
    url: String,
    contentDisposition: String? = null,
    mimeType: String? = null,
    suggestedFilename: String? = null
): String {
    var name = suggestedFilename?.takeIf { it.isNotBlank() }
    if (name != null && (name == "2Q==.bin" || name.contains("==") || name == "downloadfile.bin")) {
        name = null
    }

    // 1. Data URLs
    if (url.startsWith("data:")) {
        val dataMime = url.substringAfter("data:").substringBefore(';').substringBefore(',').trim()
        val effectiveMime = if (dataMime.isNotBlank() && dataMime != "application/octet-stream" && dataMime != "image/*") {
            dataMime
        } else {
            mimeType?.takeIf { it.isNotBlank() && it != "image/*" } ?: "image/png"
        }
        val ext = when {
            effectiveMime.contains("jpeg") || effectiveMime.contains("jpg") -> "jpg"
            effectiveMime.contains("png") -> "png"
            effectiveMime.contains("webp") -> "webp"
            effectiveMime.contains("gif") -> "gif"
            effectiveMime.contains("svg") -> "svg"
            else -> "png"
        }
        val base = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() && !it.contains("==") } ?: "orbit_image_${System.currentTimeMillis()}"
        return "$base.$ext"
    }

    // 2. Blob URLs
    if (url.startsWith("blob:")) {
        val ext = when {
            mimeType?.contains("jpeg") == true || mimeType?.contains("jpg") == true -> "jpg"
            mimeType?.contains("png") == true -> "png"
            mimeType?.contains("webp") == true -> "webp"
            mimeType?.contains("gif") == true -> "gif"
            else -> "png"
        }
        val base = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() && !it.contains("==") } ?: "orbit_image_${System.currentTimeMillis()}"
        return "$base.$ext"
    }

    // 3. Content-Disposition parsing
    if (name.isNullOrBlank() && !contentDisposition.isNullOrBlank()) {
        val utf8Match = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(contentDisposition)
        if (utf8Match != null) {
            name = try {
                java.net.URLDecoder.decode(utf8Match.groupValues[1].trim('"', '\''), "UTF-8")
            } catch (e: Exception) {
                utf8Match.groupValues[1].trim('"', '\'')
            }
        }
        if (name.isNullOrBlank()) {
            val normalMatch = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(contentDisposition)
            if (normalMatch != null) {
                name = normalMatch.groupValues[1].trim()
            }
        }
    }

    // 4. URL path segment (clean path without query string)
    if (name.isNullOrBlank() || name == "downloadfile.bin") {
        try {
            val cleanUrl = url.substringBefore('?')
            val uri = Uri.parse(cleanUrl)
            val segment = uri.lastPathSegment
            if (!segment.isNullOrBlank() && segment.contains(".") && !segment.contains("==")) {
                name = segment
            }
        } catch (e: Exception) {}
    }

    // 5. Try URLUtil on clean URL
    if (name.isNullOrBlank() || name == "downloadfile.bin" || name.contains("==")) {
        try {
            val cleanUrl = url.substringBefore('?')
            val guessed = URLUtil.guessFileName(cleanUrl, contentDisposition, mimeType)
            if (!guessed.isNullOrBlank() && guessed != "downloadfile.bin" && !guessed.contains("==")) {
                name = guessed
            }
        } catch (e: Exception) {}
    }

    // 6. Fallback if still null, binary default, or contains base64 garbage
    val cleanUrl = url.substringBefore('?').substringBefore('#').lowercase()
    val isImageContext = mimeType?.startsWith("image/") == true || url.startsWith("data:image/") ||
        cleanUrl.endsWith(".png") || cleanUrl.endsWith(".jpg") || cleanUrl.endsWith(".jpeg") ||
        cleanUrl.endsWith(".webp") || cleanUrl.endsWith(".gif") || cleanUrl.endsWith(".svg")
    if (name.isNullOrBlank() || name == "downloadfile.bin" || name.contains("==") || (name.endsWith(".bin") && isImageContext)) {
        val ext = when {
            mimeType?.contains("jpeg") == true || mimeType?.contains("jpg") == true -> "jpg"
            mimeType?.contains("png") == true -> "png"
            mimeType?.contains("webp") == true -> "webp"
            mimeType?.contains("gif") == true -> "gif"
            isImageContext -> "jpg"
            else -> "bin"
        }
        name = if (isImageContext) "orbit_image_${System.currentTimeMillis()}.$ext" else "orbit_download_${System.currentTimeMillis()}.$ext"
    }

    // Sanitize illegal characters
    name = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim()

    // Ensure extension exists
    if (!name.contains(".") && !mimeType.isNullOrBlank()) {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: when {
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                mimeType.contains("png") -> "png"
                mimeType.contains("webp") -> "webp"
                mimeType.contains("gif") -> "gif"
                mimeType.contains("svg") -> "svg"
                mimeType.contains("pdf") -> "pdf"
                mimeType.contains("zip") -> "zip"
                else -> if (isImageContext) "jpg" else "bin"
            }
        name = "$name.$ext"
    }

    return name
}

fun saveBytesToStorage(
    context: Context,
    bytes: ByteArray,
    filename: String,
    mimeType: String?
): Uri? {
    val (realExt, realMime) = detectRealExtensionAndMime(bytes, mimeType, filename)
    var baseName = filename.substringBeforeLast('.')
    if (baseName.isBlank() || baseName.contains("==") || baseName == "downloadfile" || baseName == "2Q==" || baseName.endsWith("==")) {
        val prefix = if (realMime.startsWith("image/")) "orbit_image" else "orbit_download"
        baseName = "${prefix}_${System.currentTimeMillis()}"
    }
    val cleanFilename = "$baseName.$realExt"
    val isImage = realMime.startsWith("image/")

    var resultUri: Uri? = null

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        if (isImage) {
            try {
                val imageValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, cleanFilename)
                    put(MediaStore.Images.Media.MIME_TYPE, realMime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Orbit")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                var imgUri = try {
                    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageValues)
                } catch (e: Exception) {
                    val safeName = "${baseName}_${System.currentTimeMillis()}.$realExt"
                    imageValues.put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageValues)
                }
                if (imgUri != null) {
                    context.contentResolver.openOutputStream(imgUri)?.use { os ->
                        os.write(bytes)
                    }
                    imageValues.clear()
                    imageValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(imgUri, imageValues, null, null)
                    resultUri = imgUri
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // Save non-images strictly to MediaStore.Downloads
            try {
                val dlValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, cleanFilename)
                    put(MediaStore.Downloads.MIME_TYPE, realMime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Orbit")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                var dlUri = try {
                    context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlValues)
                } catch (e: Exception) {
                    val safeName = "${baseName}_${System.currentTimeMillis()}.$realExt"
                    dlValues.put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlValues)
                }
                if (dlUri != null) {
                    context.contentResolver.openOutputStream(dlUri)?.use { os ->
                        os.write(bytes)
                    }
                    dlValues.clear()
                    dlValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(dlUri, dlValues, null, null)
                    resultUri = dlUri
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Direct filesystem fallback if MediaStore didn't produce a URI or on pre-Q
    if (resultUri == null) {
        try {
            val targetDir = if (isImage) {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Orbit").apply { mkdirs() }
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Orbit").apply { mkdirs() }
            }
            var targetFile = File(targetDir, cleanFilename)
            var counter = 1
            val base = cleanFilename.substringBeforeLast('.')
            val ext = if (cleanFilename.contains('.')) ".${cleanFilename.substringAfterLast('.')}" else ""
            while (targetFile.exists()) {
                targetFile = File(targetDir, "$base ($counter)$ext")
                counter++
            }
            targetFile.writeBytes(bytes)
            resultUri = Uri.fromFile(targetFile)
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(realMime), null)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val cacheDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir, "Orbit").apply { mkdirs() }
                val targetFile = File(cacheDir, cleanFilename)
                targetFile.writeBytes(bytes)
                resultUri = Uri.fromFile(targetFile)
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    try {
        if (resultUri != null && resultUri.scheme == "file") {
            MediaScannerConnection.scanFile(context, arrayOf(resultUri.path ?: ""), arrayOf(realMime), null)
        }
    } catch (e: Exception) {}

    return resultUri
}

fun downloadHttpUrlUniversal(
    context: Context,
    url: String,
    userAgent: String? = null,
    contentDisposition: String? = null,
    mimeType: String? = null,
    suggestedFilename: String? = null,
    referer: String? = null
) {
    val initialName = resolveDownloadFilename(url, contentDisposition, mimeType, suggestedFilename)

    android.os.Handler(android.os.Looper.getMainLooper()).post {
        Toast.makeText(context.applicationContext, "Downloading $initialName...", Toast.LENGTH_SHORT).show()
    }

    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val reqBuilder = Request.Builder().url(url)

            val effectiveUserAgent = userAgent?.takeIf { it.isNotBlank() }
                ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
            reqBuilder.addHeader("User-Agent", effectiveUserAgent)

            try {
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    reqBuilder.addHeader("Cookie", cookies)
                }
            } catch (e: Exception) {}

            if (!referer.isNullOrBlank()) {
                reqBuilder.addHeader("Referer", referer)
            }
            reqBuilder.addHeader("Accept", "*/*")
            reqBuilder.addHeader("Accept-Language", "en-US,en;q=0.9")

            val response = client.newCall(reqBuilder.build()).execute()
            if (response.isSuccessful) {
                val respDisposition = response.header("Content-Disposition") ?: contentDisposition
                val respMime = response.header("Content-Type")?.substringBefore(';') ?: mimeType
                val finalName = resolveDownloadFilename(url, respDisposition, respMime, suggestedFilename)

                val body = response.body
                if (body != null) {
                    val bytes = body.bytes()
                    val uri = saveBytesToStorage(context, bytes, finalName, respMime)
                    if (uri != null) {
                        val isImage = respMime?.startsWith("image/") == true ||
                            finalName.endsWith(".png", true) || finalName.endsWith(".jpg", true) ||
                            finalName.endsWith(".jpeg", true) || finalName.endsWith(".webp", true)
                        val message = if (isImage) "Saved $finalName to Gallery" else "Saved $finalName to Downloads"
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        withContext(Dispatchers.Main) {
            fallbackSystemDownload(context, url, userAgent, contentDisposition, mimeType, initialName)
        }
    }
}

fun fallbackSystemDownload(
    context: Context,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
    filename: String
) {
    try {
        val uri = Uri.parse(url)
        val request = DownloadManager.Request(uri).apply {
            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
            if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
            try {
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) addRequestHeader("Cookie", cookies)
            } catch (e: Exception) {}
            setTitle(filename)
            setDescription("Downloading via Orbit")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        dm?.enqueue(request)
        Toast.makeText(context.applicationContext, "Starting system download: $filename", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(context.applicationContext, "Opening download...", Toast.LENGTH_SHORT).show()
        } catch (ex: Exception) {
            Toast.makeText(context.applicationContext, "Download failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}

private val recentUniversalDownloads = java.util.concurrent.ConcurrentHashMap<String, Long>()

fun downloadFileUniversal(
    context: Context,
    url: String,
    userAgent: String? = null,
    contentDisposition: String? = null,
    mimeType: String? = null,
    suggestedFilename: String? = null,
    referer: String? = null,
    webView: WebView? = null,
    skipConfirmation: Boolean = false,
    onPromptConfirmation: ((PendingDownloadPrompt) -> Unit)? = null
) {
    if (url.isBlank()) return

    val now = System.currentTimeMillis()
    val dedupKey = "$url|$suggestedFilename"
    val lastTime = recentUniversalDownloads[dedupKey] ?: 0L
    if (now - lastTime < 1000L && !skipConfirmation) {
        return
    }
    recentUniversalDownloads[dedupKey] = now
    if (recentUniversalDownloads.size > 50) {
        val iter = recentUniversalDownloads.entries.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value > 10000L) iter.remove()
        }
    }

    val resolvedName = resolveDownloadFilename(url, contentDisposition, mimeType, suggestedFilename)

    if (!skipConfirmation) {
        if (onPromptConfirmation != null) {
            onPromptConfirmation(
                PendingDownloadPrompt(
                    filename = resolvedName,
                    mimeType = mimeType,
                    originUrl = url,
                    onConfirm = {
                        downloadFileUniversal(
                            context = context,
                            url = url,
                            userAgent = userAgent,
                            contentDisposition = contentDisposition,
                            mimeType = mimeType,
                            suggestedFilename = resolvedName,
                            referer = referer,
                            webView = webView,
                            skipConfirmation = true
                        )
                    }
                )
            )
            return
        }

        val showFallbackDialog = Runnable {
            try {
                android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Download file?")
                    .setMessage("Do you want to download:\n$resolvedName")
                    .setIcon(android.R.drawable.stat_sys_download)
                    .setPositiveButton("Download") { _, _ ->
                        downloadFileUniversal(
                            context = context,
                            url = url,
                            userAgent = userAgent,
                            contentDisposition = contentDisposition,
                            mimeType = mimeType,
                            suggestedFilename = resolvedName,
                            referer = referer,
                            webView = webView,
                            skipConfirmation = true
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (e: Exception) {
                downloadFileUniversal(
                    context = context,
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    suggestedFilename = resolvedName,
                    referer = referer,
                    webView = webView,
                    skipConfirmation = true
                )
            }
        }

        if (context is Activity) {
            context.runOnUiThread(showFallbackDialog)
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(showFallbackDialog)
        }
        return
    }

    if (url.startsWith("data:")) {
        saveDataUrlToDownloads(context, url, mimeType, resolvedName)
        return
    }

    if (url.startsWith("blob:")) {
        if (webView != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, "Extracting file...", Toast.LENGTH_SHORT).show()
            }
            downloadBlob(webView, url, resolvedName)
        } else {
            Toast.makeText(context.applicationContext, "Cannot extract blob: WebView unavailable", Toast.LENGTH_SHORT).show()
        }
        return
    }

    if (url.startsWith("http://") || url.startsWith("https://")) {
        downloadHttpUrlUniversal(
            context = context,
            url = url,
            userAgent = userAgent ?: webView?.settings?.userAgentString,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            suggestedFilename = resolvedName,
            referer = referer ?: webView?.url
        )
        return
    }

    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context.applicationContext, "Cannot handle URL: $url", Toast.LENGTH_SHORT).show()
    }
}

fun downloadBlob(webView: WebView, blobUrl: String, suggestedFilename: String? = null) {
    val escapedUrl = blobUrl.replace("'", "\\'")
    val filename = suggestedFilename?.replace("'", "\\'") ?: "orbit_image_${System.currentTimeMillis()}"
    val js = """
        (function() {
            var url = '$escapedUrl';
            var name = '$filename';
            function send(dataUrl, mime) {
                if (!window.AndroidDownloadBridge || !dataUrl) return;
                var CHUNK_SIZE = 400000;
                if (dataUrl.length > CHUNK_SIZE && window.AndroidDownloadBridge.onChunkStart) {
                    var tid = 'blob_' + Date.now() + '_' + Math.random().toString(36).substr(2, 6);
                    window.AndroidDownloadBridge.onChunkStart(tid, dataUrl.length, mime || '', name, true);
                    for (var offset = 0; offset < dataUrl.length; offset += CHUNK_SIZE) {
                        var chunk = dataUrl.substring(offset, offset + CHUNK_SIZE);
                        window.AndroidDownloadBridge.onChunkPart(tid, chunk);
                    }
                    window.AndroidDownloadBridge.onChunkEnd(tid);
                } else if (window.AndroidDownloadBridge.onBlobDataDirectSave) {
                    window.AndroidDownloadBridge.onBlobDataDirectSave(dataUrl, mime || '', name);
                } else {
                    window.AndroidDownloadBridge.onBlobData(dataUrl, mime || '', name);
                }
            }
            if (window.__orbitBlobStore && window.__orbitBlobStore.has(url)) {
                try {
                    var directBlob = window.__orbitBlobStore.get(url);
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        if (reader.result) send(reader.result, directBlob.type || 'image/png');
                    };
                    reader.readAsDataURL(directBlob);
                    return;
                } catch(e) {}
            }
            fetch(url)
                .then(function(res) {
                    var ct = (res.headers && res.headers.get('Content-Type')) || '';
                    return res.blob().then(function(b) {
                        return { blob: b, type: ct || b.type || '' };
                    });
                })
                .then(function(info) {
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        if (reader.result) send(reader.result, info.type || 'image/png');
                    };
                    reader.readAsDataURL(info.blob);
                })
                .catch(function() {
                    try {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', url, true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            if (xhr.response) {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    if (reader.result) send(reader.result, xhr.response.type || 'image/png');
                                };
                                reader.readAsDataURL(xhr.response);
                            }
                        };
                        xhr.send();
                    } catch(e) {}
                });
        })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

fun saveDataUrlToDownloads(
    context: Context,
    dataUrl: String,
    mimeType: String?,
    suggestedName: String? = null
) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        try {
            val commaIndex = dataUrl.indexOf(",")
            if (commaIndex == -1) return@launch
            val metadata = dataUrl.substring(0, commaIndex)
            val base64Data = dataUrl.substring(commaIndex + 1)
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

            val headerMime = if (metadata.contains(";base64")) {
                metadata.removePrefix("data:").removeSuffix(";base64").substringBefore(';').trim()
            } else null

            val effectiveMime = headerMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" && it != "image/*" }
                ?: mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" && it != "image/*" }
                ?: "image/png"

            val filename = resolveDownloadFilename(
                url = "data:$effectiveMime",
                contentDisposition = null,
                mimeType = effectiveMime,
                suggestedFilename = suggestedName
            )

            val uri = saveBytesToStorage(context, bytes, filename, effectiveMime)
            if (uri != null) {
                val isImage = effectiveMime.startsWith("image/") ||
                    filename.endsWith(".png", true) || filename.endsWith(".jpg", true) ||
                    filename.endsWith(".jpeg", true) || filename.endsWith(".webp", true)
                val msg = if (isImage) "Saved to Gallery" else "Saved $filename to Downloads"
                withContext(Dispatchers.Main) {
                    Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context.applicationContext, "Save error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

fun shareImage(context: Context, bytes: ByteArray, mimeType: String?, filename: String?) {
    try {
        val ext = when {
            mimeType?.contains("png") == true -> "png"
            mimeType?.contains("jpeg") == true || mimeType?.contains("jpg") == true -> "jpg"
            mimeType?.contains("webp") == true -> "webp"
            else -> "png"
        }
        val safeName = "share_${System.currentTimeMillis()}.$ext"
        val cacheFile = File(context.cacheDir, safeName)
        cacheFile.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType?.takeIf { it.isNotBlank() } ?: "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share Picture").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Toast.makeText(context, "Share failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

// Backwards-compatibility aliases
fun downloadAndSaveImage(
    context: Context,
    webView: WebView?,
    url: String,
    suggestedName: String? = null,
    mimetype: String? = null
) {
    downloadFileUniversal(
        context = context,
        url = url,
        userAgent = webView?.settings?.userAgentString,
        mimeType = mimetype,
        suggestedFilename = suggestedName,
        referer = webView?.url,
        webView = webView
    )
}

fun downloadHttpFile(
    context: Context,
    url: String,
    userAgent: String? = null,
    contentDisposition: String? = null,
    mimetype: String? = null
) {
    downloadFileUniversal(
        context = context,
        url = url,
        userAgent = userAgent,
        contentDisposition = contentDisposition,
        mimeType = mimetype
    )
}

