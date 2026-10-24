package com.flux.codebuddy

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Locale

sealed class ShellFailure {
    data class Network(val errorCode: Int) : ShellFailure()
    data class Http(val code: Int) : ShellFailure()
    data object Ssl : ShellFailure()
    data object Renderer : ShellFailure()
}

interface WebShellListener {
    fun onLoading(progress: Int)
    fun onContentVisible()
    fun onLoaded(loginRequired: Boolean)
    fun onFailure(failure: ShellFailure)
    fun onSidebarUnavailable()
}

/**
 * Owns the system WebView. The existing CodeBuddy web app remains the source of
 * truth for chat, sessions, streaming, tools and authentication. This class only
 * configures the container, adapts the layout, and handles Android integration.
 */
class WebShell(
    private val activity: ComponentActivity,
    private val container: FrameLayout,
    initialWebView: WebView,
    private val listener: WebShellListener,
) {

    private var webView: WebView? = initialWebView
    private var target: ServerTarget? = null
    private var lastUrl: String? = null
    private var mainFrameError = false
    private var documentStartSupported =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private val injectedOrigins = mutableSetOf<String>()
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null
    private var destroyed = false

    private val fileChooserLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }

    private val storagePermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingDownload
        pendingDownload = null
        if (granted && pending != null) {
            enqueueDownload(pending)
        } else if (!granted) {
            toast(R.string.download_permission)
        }
    }

    init {
        configure(initialWebView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView) {
        webView.setBackgroundColor(Color.BLACK)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            geolocationEnabled = false
            setSupportMultipleWindows(false)
            textZoom = 100
            safeBrowsingEnabled = true
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.webViewClient = shellClient
        webView.webChromeClient = shellChromeClient
        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                handleDownload(url, userAgent, contentDisposition, mimeType)
            },
        )

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    fun load(target: ServerTarget, url: String = target.startUrl) {
        this.target = target
        lastUrl = url
        mainFrameError = false
        val view = ensureWebView()

        if (documentStartSupported && injectedOrigins.add(target.origin)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(
                    view,
                    MobileInjection.DOCUMENT_START_SCRIPT,
                    setOf(target.origin),
                )
            } catch (_: Exception) {
                // If the origin is rejected, fall back to per-page injection.
                documentStartSupported = false
            }
        }

        view.loadUrl(url)
    }

    fun reconnect() {
        val view = webView
        if (view == null) {
            val current = target ?: return
            load(current, lastUrl ?: current.startUrl)
            return
        }
        mainFrameError = false
        view.reload()
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() {
        webView?.goBack()
    }

    fun currentUrl(): String? = webView?.url ?: lastUrl

    fun toggleSidebar() {
        val view = webView
        if (view == null) {
            listener.onSidebarUnavailable()
            return
        }
        view.evaluateJavascript(MobileInjection.TOGGLE_SIDEBAR_JS) { result ->
            if (result.isNullOrBlank() || result.contains("not-found")) {
                listener.onSidebarUnavailable()
            }
        }
    }

    /**
     * Used by Back handling so a Back press closes the web session drawer
     * before navigating WebView history.
     */
    fun closeSidebarIfOpen(onResult: (Boolean) -> Unit) {
        val view = webView
        if (view == null) {
            onResult(false)
            return
        }
        view.evaluateJavascript(MobileInjection.CLOSE_SIDEBAR_JS) { result ->
            onResult(result == "true")
        }
    }

    fun onPause() {
        webView?.onPause()
        webView?.pauseTimers()
        CookieManager.getInstance().flush()
    }

    fun onResume() {
        webView?.onResume()
        webView?.resumeTimers()
    }

    fun destroy() {
        destroyed = true
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        webView?.let { view ->
            container.removeView(view)
            view.stopLoading()
            view.webChromeClient = null
            view.webViewClient = null
            view.destroy()
        }
        webView = null
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val view = WebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        configure(view)
        container.addView(view, 0)
        webView = view
        return view
    }

    private fun isSameServer(uri: Uri): Boolean {
        val current = target ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        val host = uri.host?.removeSurrounding("[", "]")?.lowercase(Locale.ROOT) ?: return false
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        return scheme == current.scheme && host == current.host && port == current.port
    }

    private fun openExternal(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(R.string.external_link_error)
        } catch (_: SecurityException) {
            toast(R.string.external_link_error)
        }
    }

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (url.startsWith("blob:", ignoreCase = true) ||
            url.startsWith("data:", ignoreCase = true)
        ) {
            toast(R.string.download_unsupported)
            return
        }

        val pending = PendingDownload(url, userAgent, contentDisposition, mimeType)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = pending
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        enqueueDownload(pending)
    }

    private fun enqueueDownload(download: PendingDownload) {
        try {
            val guessed = URLUtil.guessFileName(
                download.url,
                download.contentDisposition,
                download.mimeType,
            ).ifBlank { "download" }
            val fileName = guessed.replace(Regex("[\\\\/:*?\"<>|]"), "_")

            val request = DownloadManager.Request(Uri.parse(download.url))
                .setTitle(fileName)
                .setDescription(activity.getString(R.string.app_name))
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            download.userAgent?.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("User-Agent", it)
            }
            CookieManager.getInstance().getCookie(download.url)
                ?.takeIf { it.isNotBlank() }
                ?.let { request.addRequestHeader("Cookie", it) }
            download.mimeType?.takeIf { it.isNotBlank() }?.let { request.setMimeType(it) }

            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            toast(R.string.download_started)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "download failed: ${e.javaClass.simpleName}")
            toast(R.string.download_failed)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private val shellClient = object : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            mainFrameError = false
            listener.onLoading(0)
            if (!documentStartSupported) {
                view.evaluateJavascript(MobileInjection.DOCUMENT_START_SCRIPT, null)
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            listener.onContentVisible()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (mainFrameError) return
            if (!documentStartSupported) {
                view.evaluateJavascript(MobileInjection.DOCUMENT_START_SCRIPT, null)
            }
            view.evaluateJavascript(MobileInjection.LOGIN_CHECK_JS) { result ->
                if (destroyed || mainFrameError) return@evaluateJavascript
                listener.onLoading(100)
                listener.onLoaded(result?.contains("login") == true)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            mainFrameError = true
            listener.onFailure(ShellFailure.Network(error.errorCode))
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: android.webkit.WebResourceResponse,
        ) {
            if (!request.isForMainFrame) return
            val code = errorResponse.statusCode
            // 401/403 usually mean the web UI will render its login page.
            if (code == 401 || code == 403) return
            if (code >= 400) {
                mainFrameError = true
                listener.onFailure(ShellFailure.Http(code))
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: android.net.http.SslError,
        ) {
            handler.cancel()
            mainFrameError = true
            listener.onFailure(ShellFailure.Ssl)
        }

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
            request.cancel()
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            if (webView === view) {
                container.removeView(view)
                view.destroy()
                webView = null
            }
            listener.onFailure(ShellFailure.Renderer)
            return true
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase(Locale.ROOT)) {
                "http", "https" -> {
                    if (isSameServer(uri)) {
                        false
                    } else {
                        openExternal(uri)
                        true
                    }
                }
                "about", "blob", "data" -> false
                "mailto", "tel" -> {
                    openExternal(uri)
                    true
                }
                else -> {
                    toast(R.string.external_link_error)
                    true
                }
            }
        }
    }

    private val shellChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            listener.onLoading(newProgress)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback
            return try {
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                true
            } catch (_: Exception) {
                fileChooserCallback = null
                filePathCallback.onReceiveValue(null)
                toast(R.string.file_chooser_unavailable)
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback,
        ) {
            callback.invoke(origin, false, false)
        }

        override fun onJsAlert(
            view: WebView,
            url: String?,
            message: String?,
            result: JsResult,
        ): Boolean {
            AlertDialog.Builder(activity)
                .setTitle(R.string.js_dialog_title)
                .setMessage(message.orEmpty())
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(
            view: WebView,
            url: String?,
            message: String?,
            result: JsResult,
        ): Boolean {
            AlertDialog.Builder(activity)
                .setTitle(R.string.js_dialog_title)
                .setMessage(message.orEmpty())
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult,
        ): Boolean {
            val input = EditText(activity).apply {
                setText(defaultValue.orEmpty())
                setSelection(text.length)
                setSingleLine()
                setTextColor(ContextCompat.getColor(activity, R.color.text))
                setHintTextColor(ContextCompat.getColor(activity, R.color.text_tertiary))
            }
            val wrapper = FrameLayout(activity).apply {
                setPadding(dp(16), dp(4), dp(16), 0)
                addView(
                    input,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.js_dialog_title)
                .setMessage(message.orEmpty())
                .setView(wrapper)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsBeforeUnload(
            view: WebView,
            url: String?,
            message: String?,
            result: JsResult,
        ): Boolean {
            AlertDialog.Builder(activity)
                .setTitle(R.string.leave_page_title)
                .setMessage(message?.takeIf { it.isNotBlank() } ?: activity.getString(R.string.leave_page_message))
                .setPositiveButton(R.string.leave_page_leave) { _, _ -> result.confirm() }
                .setNegativeButton(R.string.leave_page_stay) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message,
        ): Boolean = false

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "console ${consoleMessage.messageLevel()} " +
                        "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                )
            }
            return true
        }
    }

    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
    )

    private companion object {
        const val TAG = "CodeBuddyWeb"
    }
}
