package com.flux.codebuddy

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    /** A SPA route change or finished load, used to keep the recents list fresh. */
    fun onLocationChanged(url: String, title: String?)
}

/**
 * Owns the system WebView.
 *
 * The WebView is created in code (never inflated) so it can survive an activity
 * recreation: [MainActivity] hands the live instance over through
 * `onRetainCustomNonConfigurationInstance` and this class re-attaches it. The
 * chat document therefore keeps its DOM, scroll position and WebSocket instead
 * of re-fetching the site, which is what made reopening the app look like a
 * fresh website load.
 *
 * For process death the activity saves [saveState] and calls [restoreState] on
 * the next launch; that restores history and scroll and reloads from the HTTP
 * cache instead of the site root.
 */
class WebShell(
    activity: ComponentActivity,
    private val container: FrameLayout,
    private val listener: WebShellListener,
    retained: RetainedShell? = null,
) {

    private var activity: ComponentActivity = activity

    /**
     * The WebView must not hold a destroyed activity, but it also cannot be
     * built on the application context: `<select>` popups, date pickers and
     * file inputs create windows from the WebView's context and crash with
     * "token null is not for an application" without an activity. A wrapper
     * whose base we swap on every [attach] gives the WebView the *current*
     * activity without leaking the previous one.
     */
    private val contextWrapper = object : ContextWrapper(activity) {
        fun rebase(next: Context) {
            attachBaseContext(next)
        }
    }

    /** Everything that must outlive an activity recreation. */
    class RetainedShell internal constructor(
        internal var webView: WebView,
    ) {
        internal var target: ServerTarget? = null
        internal var lastUrl: String? = null
        internal var uiScale: Float = UiScale.DEFAULT
        internal var documentStartOrigins = mutableSetOf<String>()
        internal var documentStartSupported =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        internal var contentLoaded = false
    }

    private var state: RetainedShell = retained ?: RetainedShell(createWebView())

    private val webView: WebView get() = state.webView

    private var destroyed = false
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null

    /**
     * Activity result plumbing lives in the activity, not here: launchers are
     * bound to the activity that registered them, and this shell outlives that
     * activity across configuration changes.
     */
    var launchFileChooser: ((Intent) -> Unit)? = null
    var launchStoragePermission: ((String) -> Unit)? = null

    /** Called by the activity with the result of [launchFileChooser]. */
    fun onFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = fileChooserCallback
        fileChooserCallback = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(resultCode, data),
        )
    }

    /** Called by the activity with the result of [launchStoragePermission]. */
    fun onStoragePermissionResult(granted: Boolean) {
        val pending = pendingDownload
        pendingDownload = null
        if (granted && pending != null) {
            enqueueDownload(pending)
        } else if (!granted) {
            toast(R.string.download_permission)
        }
    }

    /** Re-parents the WebView into the current activity's container. */
    fun attach(next: ComponentActivity? = null) {
        if (next != null && next !== activity) {
            activity = next
            contextWrapper.rebase(next)
        }
        if (webView.parent === container) return
        (webView.parent as? ViewGroup)?.removeView(webView)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * Called before an activity goes away. On a configuration change the new
     * activity has already re-parented the WebView by the time this runs, so it
     * must only remove the view when it is still in *this* container -
     * otherwise the chat would be detached from the fresh activity.
     */
    fun detach() {
        if (webView.parent === container) {
            container.removeView(webView)
        }
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
    }

    fun retainedInstance(): RetainedShell = state

    fun hasContent(): Boolean = state.contentLoaded

    fun currentTarget(): ServerTarget? = state.target

    fun setScale(scale: Float) {
        state.uiScale = UiScale.clamp(scale)
    }

    /**
     * Points the shell at [target] without navigating. Needed after a process
     * death, where the WebView state is restored but the origin policy and the
     * document-start script have to be re-armed before anything is clicked.
     */
    fun prepare(target: ServerTarget, scale: Float) {
        state.target = target
        state.uiScale = UiScale.clamp(scale)
        registerDocumentStart(target)
    }

    private fun registerDocumentStart(target: ServerTarget) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            state.documentStartSupported = false
            return
        }
        if (!state.documentStartSupported) return
        if (!state.documentStartOrigins.add(target.origin)) return
        try {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                MobileInjection.documentStartScript(state.uiScale),
                setOf(target.origin),
            )
        } catch (_: Exception) {
            state.documentStartSupported = false
        }
    }

    fun load(target: ServerTarget, url: String = target.startUrl) {
        state.target = target
        state.lastUrl = url
        state.contentLoaded = false
        prepare(target, state.uiScale)
        webView.loadUrl(url)
    }

    /**
     * Restores a previously saved WebView state. Returns true when a history
     * entry was restored, so the caller can skip a fresh `load`.
     */
    fun restoreState(bundle: Bundle?): Boolean {
        if (bundle == null) return false
        val view = webView
        val saved = bundle.getBundle(KEY_WEB_STATE) ?: return false
        return try {
            view.restoreState(saved) != null
        } catch (_: Exception) {
            false
        }
    }

    fun saveState(outState: Bundle) {
        if (destroyed) return
        try {
            val bundle = Bundle()
            webView.saveState(bundle)
            outState.putBundle(KEY_WEB_STATE, bundle)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "saveState failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Applies a new UI size to the loaded document and remembers it for the
     * next load. The document-start script is registered once per origin, so
     * localStorage carries the value into later loads.
     */
    fun applyUiScale(scale: Float) {
        val next = UiScale.clamp(scale)
        if (next == state.uiScale) return
        state.uiScale = next
        if (webView.url == null) return
        webView.evaluateJavascript(MobileInjection.applyScaleScript(next), null)
    }

    fun reconnect() {
        state.contentLoaded = false
        webView.reload()
    }

    fun canGoBack(): Boolean = try {
        webView.canGoBack()
    } catch (_: Exception) {
        false
    }

    fun goBack() {
        webView.goBack()
    }

    /** Abandons a navigation after the shell already reported a failure. */
    fun abortLoad() {
        webView.stopLoading()
    }

    fun refreshLoginState(onResult: (Boolean) -> Unit) {
        val view = webView
        view.evaluateJavascript(MobileInjection.LOGIN_CHECK_JS) { result ->
            onResult(result?.contains("login") == true)
        }
    }

    fun currentUrl(): String? = webView.url ?: state.lastUrl

    fun documentTitle(onResult: (String?) -> Unit) {
        try {
            webView.evaluateJavascript(TITLE_JS) { raw ->
                onResult(raw?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" })
            }
        } catch (_: Exception) {
            onResult(null)
        }
    }

    fun toggleSidebar(): Boolean {
        webView.evaluateJavascript(MobileInjection.TOGGLE_SIDEBAR_JS, null)
        return true
    }

    fun closeSidebarIfOpen(onResult: (Boolean) -> Unit) {
        webView.evaluateJavascript(MobileInjection.CLOSE_SIDEBAR_JS) { result ->
            onResult(result == "true")
        }
    }

    fun clearBrowsingData(onDone: () -> Unit) {
        try {
            CookieManager.getInstance().removeAllCookies { }
            CookieManager.getInstance().flush()
            webView.clearHistory()
            webView.clearCache(true)
            webView.clearFormData()
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
            // Best effort; the caller still reloads.
        }
        onDone()
    }

    fun onResume() {
        webView.onResume()
        webView.resumeTimers()
    }

    fun onPause() {
        // Only pause the WebView's own rendering/timers. Calling the global
        // WebView.pauseTimers() froze every page in the process and left the
        // chat socket stale, which is one reason a resume looked like a reload.
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    fun onDestroy() {
        destroyed = true
        detach()
        contextWrapper.rebase(activity.applicationContext)
        webView.stopLoading()
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }

    /**
     * The renderer process can be killed by the system (low memory, GPU crash).
     * The WebView object is then unusable, so a retry has to start from a fresh
     * instance instead of reloading a dead one.
     */
    private fun recreateWebView() {
        val dead = webView
        (dead.parent as? ViewGroup)?.removeView(dead)
        try {
            dead.stopLoading()
            dead.webChromeClient = WebChromeClient()
            dead.webViewClient = WebViewClient()
            dead.destroy()
        } catch (_: Exception) {
            // Already torn down by the framework.
        }
        val fresh = createWebView()
        configure(fresh)
        state.webView = fresh
        state.contentLoaded = false
        state.documentStartOrigins.clear()
        attach()
    }

    // ---------------------------------------------------------------------
    // WebView construction
    // ---------------------------------------------------------------------

    /**
     * Created with the application context on purpose: the instance outlives
     * the activity across configuration changes, so holding an activity here
     * would leak it. Everything that needs the current activity (dialogs, file
     * pickers, downloads) receives it as a parameter instead.
     */
    private fun createWebView(): WebView = WebView(contextWrapper).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(Color.BLACK)
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true
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

    private fun isSameServer(uri: Uri): Boolean {
        val current = state.target ?: return false
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
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE),
            )
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
            val launcher = launchStoragePermission
            if (launcher == null) {
                toast(R.string.download_permission)
                return
            }
            pendingDownload = pending
            launcher(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
        (value * webView.resources.displayMetrics.density).toInt()

    private val shellClient = object : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            state.lastUrl = url ?: state.lastUrl
            listener.onLoading(0)
            if (!state.documentStartSupported) {
                view.evaluateJavascript(MobileInjection.documentStartScript(state.uiScale), null)
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            listener.onContentVisible()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!state.documentStartSupported) {
                view.evaluateJavascript(MobileInjection.documentStartScript(state.uiScale), null)
            } else {
                view.evaluateJavascript(MobileInjection.applyScaleScript(state.uiScale), null)
            }
            state.contentLoaded = true
            view.evaluateJavascript(MobileInjection.LOGIN_CHECK_JS) { result ->
                if (destroyed) return@evaluateJavascript
                listener.onLoading(100)
                listener.onLoaded(result?.contains("login") == true)
            }
            view.evaluateJavascript(TITLE_JS) { raw ->
                if (destroyed) return@evaluateJavascript
                val title = raw?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                listener.onLocationChanged(url ?: view.url.orEmpty(), title)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url.isNullOrBlank()) return
            state.lastUrl = url
            view.evaluateJavascript(TITLE_JS) { raw ->
                if (destroyed) return@evaluateJavascript
                val title = raw?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                listener.onLocationChanged(url, title)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
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
            if (code >= 400) listener.onFailure(ShellFailure.Http(code))
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: android.net.http.SslError,
        ) {
            handler.cancel()
            listener.onFailure(ShellFailure.Ssl)
        }

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
            request.cancel()
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            if (view === webView && !destroyed) {
                recreateWebView()
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
            val launcher = launchFileChooser
            if (launcher == null) {
                filePathCallback.onReceiveValue(null)
                toast(R.string.file_chooser_unavailable)
                return false
            }
            fileChooserCallback = filePathCallback
            return try {
                launcher(fileChooserParams.createIntent())
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
            AlertDialog.Builder(activity, R.style.Theme_CodeBuddy_Dialog)
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
            AlertDialog.Builder(activity, R.style.Theme_CodeBuddy_Dialog)
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
            AlertDialog.Builder(activity, R.style.Theme_CodeBuddy_Dialog)
                .setTitle(R.string.js_dialog_title)
                .setMessage(message.orEmpty())
                .setView(wrapper)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.confirm(input.text.toString())
                }
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
            AlertDialog.Builder(activity, R.style.Theme_CodeBuddy_Dialog)
                .setTitle(R.string.leave_page_title)
                .setMessage(
                    message?.takeIf { it.isNotBlank() }
                        ?: activity.getString(R.string.leave_page_message),
                )
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

    init {
        // Must stay last: Kotlin runs property initializers and init blocks in
        // declaration order, so shellClient/shellChromeClient have to exist
        // before configure() installs them on the WebView.
        configure(webView)
        attach()
    }

    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
    )

    companion object {
        private const val TAG = "CodeBuddyWeb"
        const val KEY_WEB_STATE = "cbm_web_state"

        private const val TITLE_JS =
            "(function(){try{" +
                "var t=document.title||'';" +
                "var h=document.querySelector('.chat-header-title,.sessions-panel-title,h1');" +
                "if(h&&h.textContent&&h.textContent.trim().length>2)t=h.textContent.trim();" +
                "return t;}catch(e){return ''}})()"
    }
}
