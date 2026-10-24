package com.flux.codebuddy

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.flux.codebuddy.databinding.ActivityMainBinding
import com.flux.codebuddy.ui.ServerConfigView
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), WebShellListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ServerStore
    private lateinit var webShell: WebShell

    private var currentTarget: ServerTarget? = null
    private var currentConfig: ServerConfig? = null
    private var configView: ServerConfigView? = null

    private var setupMode = false
    private var connectionState = ConnectionState.IDLE
    private var lastStableState = ConnectionState.IDLE
    private var lastLoadFailed = false
    private var contentLoadedOnce = false

    private var imeVisible = false
    private var wasImeVisible = false
    private var lastBackAt = 0L

    private val probeExecutor = Executors.newSingleThreadExecutor()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var restoredUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = ServerStore(this)
        restoredUrl = savedInstanceState?.getString(KEY_LAST_URL)

        setupWindow()
        setupInsets()
        webShell = WebShell(this, binding.webContainer, binding.webView, this)
        setupToolbar()
        setupBack()
        registerNetworkCallback()

        val saved = store.load()
        if (saved != null) {
            when (val result = ServerUrl.parse(saved.url)) {
                is UrlResult.Ok -> {
                    currentConfig = saved
                    currentTarget = result.target
                    setupMode = false
                    binding.toolbar.visibility = View.VISIBLE
                    val startUrl = restoredUrl
                        ?.takeIf { isSameOrigin(it, result.target) }
                        ?: result.target.startUrl
                    connectCurrent(saved, result.target, startUrl)
                }

                is UrlResult.Error -> {
                    showSetup(saved, messageFor(result.reason))
                }
            }
        } else {
            showSetup()
        }
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeNow = insets.isVisible(WindowInsetsCompat.Type.ime())

            if (wasImeVisible && !imeNow) {
                binding.root.postDelayed({ hideSystemBars() }, 120)
            }
            wasImeVisible = imeNow
            imeVisible = imeNow

            binding.appColumn.setPadding(
                cutout.left,
                cutout.top,
                cutout.right,
                maxOf(cutout.bottom, ime.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupToolbar() {
        binding.statusBlock.setOnClickListener { openSettings() }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnReload.setOnClickListener { reconnect() }
        binding.btnSidebar.setOnClickListener { webShell.toggleSidebar() }
        binding.btnErrorRetry.setOnClickListener { reconnect() }
        binding.btnErrorSettings.setOnClickListener { openSettings() }
        setStatus(ConnectionState.IDLE)
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    private fun handleBack() {
        if (imeVisible) {
            hideIme()
            return
        }

        if (binding.configContainer.visibility == View.VISIBLE) {
            if (setupMode) {
                handleExit()
            } else {
                closeSettings()
            }
            return
        }

        webShell.closeSidebarIfOpen { closed ->
            if (isFinishing || isDestroyed) return@closeSidebarIfOpen
            if (closed) return@closeSidebarIfOpen
            if (webShell.canGoBack()) {
                webShell.goBack()
            } else {
                handleExit()
            }
        }
    }

    private fun handleExit() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt < 2000L) {
            finish()
        } else {
            lastBackAt = now
            Toast.makeText(this, R.string.double_back_exit, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideIme() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun showSetup(prefill: ServerConfig? = null, initialError: String? = null) {
        setupMode = true
        currentTarget = null
        currentConfig = prefill
        binding.toolbar.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.errorOverlay.visibility = View.GONE
        binding.loadingOverlay.visibility = View.GONE
        binding.configContainer.visibility = View.VISIBLE
        binding.configContainer.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        showConfig(isSetup = true, config = prefill, initialError = initialError)
    }

    private fun openSettings() {
        if (currentTarget == null) {
            showSetup(currentConfig)
            return
        }
        hideIme()
        binding.configContainer.setBackgroundResource(R.drawable.bg_overlay)
        binding.configContainer.visibility = View.VISIBLE
        showConfig(isSetup = false, config = currentConfig)
    }

    private fun closeSettings() {
        if (setupMode) return
        binding.configContainer.visibility = View.GONE
        hideIme()
    }

    private fun showConfig(
        isSetup: Boolean,
        config: ServerConfig?,
        initialError: String? = null,
    ) {
        val view = configView ?: ServerConfigView(this).also { created ->
            created.onConnect = ::handleConnect
            created.onTest = ::handleProbe
            created.onCancel = ::closeSettings
            binding.configContainer.addView(
                created.root,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            configView = created
        }

        view.bind(config, isSetup)
        if (isSetup) {
            view.setStatus(getString(R.string.setup_status_idle), R.color.text_secondary)
            binding.configContainer.post { view.focusUrl() }
        } else {
            view.setStatus(currentStatusText(), connectionState.colorRes)
        }
        initialError?.let { view.showUrlError(it) }
        binding.configContainer.visibility = View.VISIBLE
    }

    private fun handleConnect(url: String, name: String, remember: Boolean) {
        val view = configView ?: return
        when (val result = ServerUrl.parse(url)) {
            is UrlResult.Error -> view.showUrlError(messageFor(result.reason))

            is UrlResult.Ok -> {
                val config = ServerConfig(
                    url = result.target.startUrl,
                    displayName = name.trim().take(40),
                    remember = remember,
                )
                store.save(config)

                val previous = currentTarget
                currentConfig = config
                currentTarget = result.target
                setupMode = false
                binding.toolbar.visibility = View.VISIBLE
                binding.configContainer.visibility = View.GONE
                hideIme()

                val changed = previous == null ||
                    previous.origin != result.target.origin ||
                    previous.startUrl != result.target.startUrl
                val needsReload = changed ||
                    webShell.currentUrl() == null ||
                    binding.errorOverlay.visibility == View.VISIBLE ||
                    connectionState == ConnectionState.ERROR

                if (needsReload) {
                    connectCurrent(
                        config = config,
                        target = result.target,
                        url = result.target.startUrl,
                        fullScreenLoading = !contentLoadedOnce ||
                            previous == null ||
                            previous.origin != result.target.origin,
                    )
                } else {
                    setStatus(connectionState)
                }
            }
        }
    }

    private fun connectCurrent(
        config: ServerConfig,
        target: ServerTarget,
        url: String,
        fullScreenLoading: Boolean = !contentLoadedOnce,
    ) {
        currentConfig = config
        currentTarget = target
        lastLoadFailed = false
        binding.toolbar.visibility = View.VISIBLE
        binding.errorOverlay.visibility = View.GONE
        binding.configContainer.visibility = View.GONE
        binding.loadingOverlay.visibility = if (fullScreenLoading) View.VISIBLE else View.GONE
        binding.progress.visibility = View.VISIBLE
        setStatus(ConnectionState.CONNECTING)
        webShell.load(target, url)
    }

    private fun reconnect() {
        if (setupMode || currentTarget == null) return
        if (binding.configContainer.visibility == View.VISIBLE) {
            closeSettings()
        }
        binding.errorOverlay.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        lastLoadFailed = false
        setStatus(ConnectionState.CONNECTING)
        webShell.reconnect()
    }

    private fun handleProbe(url: String) {
        val view = configView ?: return
        when (val result = ServerUrl.parse(url)) {
            is UrlResult.Error -> view.showUrlError(messageFor(result.reason))

            is UrlResult.Ok -> {
                if (!isNetworkAvailable()) {
                    view.showTestStatus(getString(R.string.probe_no_network), R.color.error)
                    return
                }
                view.setBusy(true)
                probeExecutor.execute {
                    val probe = ServerProbe.probe(result.target)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        view.setBusy(false)
                        when (probe) {
                            is ProbeResult.Reachable -> view.showTestStatus(
                                getString(
                                    if (probe.loginRequired) R.string.test_auth
                                    else R.string.test_ok,
                                ),
                                R.color.success,
                            )

                            is ProbeResult.Failed -> view.showTestStatus(
                                getString(R.string.test_failed, probeMessage(probe)),
                                R.color.error,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun probeMessage(failed: ProbeResult.Failed): String {
        return when (failed.kind) {
            ProbeResult.Failure.NOT_CODEBUDDY -> getString(R.string.probe_not_codebuddy)
            ProbeResult.Failure.HTTP -> getString(R.string.probe_http, failed.httpCode ?: 0)
            ProbeResult.Failure.TIMEOUT -> getString(R.string.probe_timeout)
            ProbeResult.Failure.REFUSED -> getString(R.string.probe_refused)
            ProbeResult.Failure.UNKNOWN_HOST -> getString(R.string.probe_unknown_host)
            ProbeResult.Failure.TLS -> getString(R.string.probe_tls)
            ProbeResult.Failure.GENERIC -> getString(R.string.probe_generic)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (connectionState == ConnectionState.CONNECTED ||
                        connectionState == ConnectionState.LOGIN_REQUIRED ||
                        connectionState == ConnectionState.CONNECTING
                    ) {
                        setStatus(ConnectionState.OFFLINE)
                    }
                }
            }

            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (binding.configContainer.visibility == View.VISIBLE) return@runOnUiThread
                    if (lastLoadFailed || binding.errorOverlay.visibility == View.VISIBLE) {
                        reconnect()
                    } else if (connectionState == ConnectionState.OFFLINE) {
                        setStatus(lastStableState)
                    }
                }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
            // ACCESS_NETWORK_STATE is declared; ignore rare OEM failures.
        }
    }

    private fun setStatus(state: ConnectionState) {
        connectionState = state
        val color = ContextCompat.getColor(this, state.colorRes)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
        val label = getString(state.labelRes)
        binding.statusDot.contentDescription = label

        val host = currentTarget?.displayHost
        binding.toolbarSubtitle.text = if (host.isNullOrBlank()) {
            label
        } else {
            getString(R.string.server_status_format, host, label)
        }
        binding.toolbarTitle.text = currentConfig?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.toolbar_title)
    }

    private fun currentStatusText(): String {
        val label = getString(connectionState.labelRes)
        val host = currentTarget?.displayHost ?: return label
        return getString(R.string.server_status_format, host, label)
    }

    private fun messageFor(reason: UrlError): String {
        return getString(
            when (reason) {
                UrlError.EMPTY -> R.string.url_required
                UrlError.INVALID -> R.string.url_invalid
                UrlError.CREDENTIALS -> R.string.url_credentials
                UrlError.PASSWORD_QUERY -> R.string.url_password_query
                UrlError.PUBLIC_HTTP -> R.string.url_public_http
                UrlError.BAD_PORT -> R.string.url_bad_port
                UrlError.BAD_PATH -> R.string.url_bad_path
            },
        )
    }

    private fun isSameOrigin(url: String, target: ServerTarget): Boolean {
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        val host = uri.host?.removeSurrounding("[", "]")?.lowercase(Locale.ROOT) ?: return false
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        return scheme == target.scheme && host == target.host && port == target.port
    }

    override fun onLoading(progress: Int) {
        if (progress >= 100) {
            binding.progress.visibility = View.GONE
            binding.loadingOverlay.visibility = View.GONE
        } else {
            if (binding.errorOverlay.visibility == View.VISIBLE) {
                binding.errorOverlay.visibility = View.GONE
            }
            binding.progress.visibility = View.VISIBLE
            if (!contentLoadedOnce) {
                binding.loadingOverlay.visibility = View.VISIBLE
            }
        }
    }

    override fun onContentVisible() {
        contentLoadedOnce = true
        binding.loadingOverlay.visibility = View.GONE
    }

    override fun onLoaded(loginRequired: Boolean) {
        contentLoadedOnce = true
        binding.loadingOverlay.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.errorOverlay.visibility = View.GONE
        lastLoadFailed = false
        val state = if (loginRequired) {
            ConnectionState.LOGIN_REQUIRED
        } else {
            ConnectionState.CONNECTED
        }
        lastStableState = state
        setStatus(state)
    }

    override fun onFailure(failure: ShellFailure) {
        lastLoadFailed = true
        binding.loadingOverlay.visibility = View.GONE
        binding.progress.visibility = View.GONE

        binding.errorMessage.text = when (failure) {
            is ShellFailure.Network -> when (failure.errorCode) {
                android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> getString(R.string.error_host_lookup)
                android.webkit.WebViewClient.ERROR_CONNECT -> getString(R.string.error_refused)
                android.webkit.WebViewClient.ERROR_TIMEOUT -> getString(R.string.error_timeout)
                else -> getString(R.string.error_generic)
            }

            is ShellFailure.Http -> if (failure.code == 404) {
                getString(R.string.error_not_found)
            } else {
                getString(R.string.error_http, failure.code)
            }

            ShellFailure.Ssl -> getString(R.string.error_ssl)
            ShellFailure.Renderer -> getString(R.string.error_renderer)
        }

        binding.errorOverlay.visibility = View.VISIBLE
        setStatus(ConnectionState.ERROR)
    }

    override fun onSidebarUnavailable() {
        Toast.makeText(this, R.string.sidebar_unavailable, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        webShell.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        webShell.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::binding.isInitialized) {
            binding.root.postDelayed({ hideSystemBars() }, 200)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webShell.currentUrl()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { outState.putString(KEY_LAST_URL, it) }
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            try {
                getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
                // Already unregistered or the process is going away.
            }
        }
        networkCallback = null
        probeExecutor.shutdownNow()
        webShell.destroy()
        super.onDestroy()
    }

    private enum class ConnectionState(
        @StringRes val labelRes: Int,
        @ColorRes val colorRes: Int,
    ) {
        IDLE(R.string.toolbar_status_idle, R.color.text_tertiary),
        CONNECTING(R.string.toolbar_status_connecting, R.color.warning),
        CONNECTED(R.string.toolbar_status_connected, R.color.success),
        LOGIN_REQUIRED(R.string.toolbar_status_login, R.color.warning),
        ERROR(R.string.toolbar_status_error, R.color.error),
        OFFLINE(R.string.toolbar_status_offline, R.color.text_tertiary),
    }

    private companion object {
        const val KEY_LAST_URL = "last_url"
    }
}
