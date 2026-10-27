package com.flux.codebuddy

import android.content.Intent
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
import androidx.activity.result.contract.ActivityResultContracts
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

/**
 * Single-activity host for three native screens: home, chat (WebView) and the
 * settings sheet. No screen is a website; the chat surface is the only WebView
 * and it is retained across configuration changes and saved across process
 * death.
 */
class MainActivity : ComponentActivity(), WebShellListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ServerStore
    private lateinit var recents: RecentsStore
    private lateinit var webShell: WebShell

    private var currentTarget: ServerTarget? = null
    private var currentConfig: ServerConfig? = null
    private var configView: ServerConfigView? = null
    private var appliedScale = UiScale.DEFAULT

    private var screen = Screen.HOME
    private var setupMode = false
    private var connectionState = ConnectionState.IDLE
    private var lastStableState = ConnectionState.IDLE
    private var lastLoadFailed = false
    private var contentLoadedOnce = false
    private var pendingUrl: String? = null

    private var imeVisible = false
    private var wasImeVisible = false
    private var lastBackAt = 0L

    private var awaitingLoad = false
    private var suppressFailure = false
    private var connectCheck: Runnable? = null
    private var statusCheck: Runnable? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        webShell.onFileChooserResult(result.resultCode, result.data)
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        webShell.onStoragePermissionResult(granted)
    }

    private val probeExecutor = Executors.newSingleThreadExecutor()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = ServerStore(this)
        recents = RecentsStore(this)
        appliedScale = store.loadScale()

        setupWindow()
        setupInsets()

        lastStableState = savedInstanceState?.getString(KEY_STABLE_STATE)
            ?.let { name -> ConnectionState.entries.firstOrNull { it.name == name } }
            ?: ConnectionState.IDLE
        contentLoadedOnce = webShellContentLoaded(savedInstanceState)

        val retained = lastCustomNonConfigurationInstance as? WebShell.RetainedShell
        webShell = WebShell(this, binding.webSlot, this, retained)
        webShell.setScale(appliedScale)
        webShell.launchFileChooser = fileChooserLauncher::launch
        webShell.launchStoragePermission = storagePermissionLauncher::launch

        setupChrome()
        setupHome()
        setupBack()
        registerNetworkCallback()

        val saved = store.load()
        if (saved != null) {
            when (val result = ServerUrl.parse(saved.url)) {
                is UrlResult.Ok -> {
                    currentConfig = saved
                    currentTarget = result.target
                    setupMode = false
                    if (retained != null && retained.contentLoaded) {
                        // Same process, same WebView: the chat is already live.
                        showChat()
                        restoreConnectionState()
                    } else if (webShell.restoreState(savedInstanceState?.getBundle(KEY_SHELL_STATE))) {
                        // Process was killed: history is back, paint the cache.
                        webShell.prepare(result.target, appliedScale)
                        showChat()
                        setStatus(ConnectionState.CONNECTING)
                        armConnectCheck()
                        armRestoreFallback(saved, result.target)
                    } else {
                        showHome()
                        refreshHome()
                    }
                }

                is UrlResult.Error -> showSetup(saved, messageFor(result.reason))
            }
        } else {
            showSetup()
        }

        if (screen == Screen.HOME) refreshHome()
        applyKeepAwake()
    }

    override fun onRetainCustomNonConfigurationInstance(): Any = webShell.retainedInstance()

    private fun webShellContentLoaded(savedInstanceState: Bundle?): Boolean =
        savedInstanceState?.getBoolean(KEY_CONTENT_LOADED, false) == true

    // ------------------------------------------------------------------
    // Window and insets
    // ------------------------------------------------------------------

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

            binding.chatScreen.setPadding(
                cutout.left,
                cutout.top,
                cutout.right,
                maxOf(cutout.bottom, ime.bottom),
            )
            binding.homeScreen.root.setPadding(
                cutout.left,
                cutout.top,
                cutout.right,
                cutout.bottom,
            )
            binding.settingsContainer.setPadding(
                cutout.left,
                cutout.top,
                cutout.right,
                maxOf(cutout.bottom, ime.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    // ------------------------------------------------------------------
    // Chrome wiring
    // ------------------------------------------------------------------

    private fun setupChrome() {
        binding.btnBack.setOnClickListener { showHome() }
        binding.btnReload.setOnClickListener { reconnect() }
        binding.btnMore.setOnClickListener { showMoreSheet() }
        binding.btnErrorRetry.setOnClickListener { reconnect() }
        binding.btnErrorSettings.setOnClickListener { openSettings() }
        setStatus(ConnectionState.IDLE)
    }

    private fun setupHome() {
        binding.homeScreen.btnHomePrimary.setOnClickListener {
            if (currentTarget == null) showSetup(currentConfig) else openChat(pendingUrl)
        }
        binding.homeScreen.btnHomeNewChat.setOnClickListener {
            if (currentTarget == null) showSetup(currentConfig) else openNewChat()
        }
        binding.homeScreen.btnHomeTest.setOnClickListener {
            if (currentTarget == null) showSetup(currentConfig) else testCurrentServer()
        }
        binding.homeScreen.btnHomeSettings.setOnClickListener { openSettings() }
        binding.homeScreen.btnHomeCopy.setOnClickListener { copyServerUrl() }
        binding.homeScreen.btnHomeBrowser.setOnClickListener { openServerInBrowser() }
        binding.homeScreen.btnClearRecents.setOnClickListener {
            recents.clear()
            refreshHome()
            toast(R.string.home_recents_cleared)
        }
        binding.homeScreen.homeVersion.text = getString(
            R.string.home_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            primaryAbi(),
        )
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

        if (binding.settingsContainer.visibility == View.VISIBLE) {
            if (setupMode) {
                handleExit()
            } else {
                closeSettings()
            }
            return
        }

        if (screen == Screen.CHAT) {
            webShell.closeSidebarIfOpen { closed ->
                if (isFinishing || isDestroyed) return@closeSidebarIfOpen
                if (closed) return@closeSidebarIfOpen
                if (webShell.canGoBack()) {
                    webShell.goBack()
                } else {
                    showHome()
                }
            }
            return
        }

        handleExit()
    }

    private fun handleExit() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt < 2000L) {
            finish()
        } else {
            lastBackAt = now
            toast(R.string.double_back_exit)
        }
    }

    private fun hideIme() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    // ------------------------------------------------------------------
    // Screens
    // ------------------------------------------------------------------

    private fun showHome() {
        screen = Screen.HOME
        hideIme()
        binding.chatScreen.visibility = View.GONE
        binding.homeScreen.root.visibility = View.VISIBLE
        refreshHome()
    }

    private fun showChat() {
        screen = Screen.CHAT
        binding.homeScreen.root.visibility = View.GONE
        binding.chatScreen.visibility = View.VISIBLE
    }

    private fun openChat(url: String? = null) {
        val target = currentTarget ?: run { showSetup(currentConfig); return }
        val config = currentConfig
        if (config == null) {
            showSetup(null)
            return
        }
        val requested = url ?: pendingUrl
        pendingUrl = null
        showChat()
        if (requested == null &&
            webShell.hasContent() &&
            !lastLoadFailed &&
            binding.errorOverlay.visibility != View.VISIBLE
        ) {
            // Already showing this session; just come back to it.
            restoreConnectionState()
            return
        }
        connectCurrent(config, target, requested ?: target.startUrl)
    }

    private fun openNewChat() {
        val target = currentTarget ?: return
        val config = currentConfig ?: return
        pendingUrl = null
        showChat()
        connectCurrent(config, target, target.startUrl)
    }

    private fun showSetup(prefill: ServerConfig? = null, initialError: String? = null) {
        setupMode = true
        binding.homeScreen.root.visibility = View.VISIBLE
        binding.chatScreen.visibility = View.GONE
        binding.settingsContainer.visibility = View.VISIBLE
        binding.settingsContainer.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        showConfig(isSetup = true, config = prefill, initialError = initialError)
        screen = Screen.HOME
        refreshHome()
    }

    private fun openSettings() {
        if (currentTarget == null) {
            showSetup(currentConfig)
            return
        }
        hideIme()
        binding.settingsContainer.setBackgroundResource(R.drawable.bg_overlay)
        binding.settingsContainer.visibility = View.VISIBLE
        showConfig(isSetup = false, config = currentConfig)
    }

    private fun closeSettings() {
        if (setupMode) return
        binding.settingsContainer.visibility = View.GONE
        hideIme()
        if (screen == Screen.HOME) refreshHome()
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
            created.onClearSession = ::confirmClearSession
            created.onKeepAwakeChanged = { enabled ->
                store.saveKeepAwake(enabled)
                applyKeepAwake()
            }
            binding.settingsContainer.addView(
                created.root,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            configView = created
        }

        view.bind(config, isSetup, appliedScale, store.loadKeepAwake())
        if (isSetup) {
            view.setStatus(getString(R.string.setup_status_idle), R.color.text_secondary)
            binding.settingsContainer.post { view.focusUrl() }
        } else {
            view.setStatus(currentStatusText(), connectionState.colorRes)
        }
        initialError?.let { view.showUrlError(it) }
        binding.settingsContainer.visibility = View.VISIBLE
    }

    private fun applyKeepAwake() {
        if (store.loadKeepAwake()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------------
    // Home screen
    // ------------------------------------------------------------------

    private fun refreshHome() {
        val config = currentConfig
        val target = currentTarget

        if (config == null || target == null) {
            binding.homeScreen.homeServerName.setText(R.string.home_no_server)
            binding.homeScreen.homeServerChip.visibility = View.GONE
            binding.homeScreen.homeServerUrl.visibility = View.GONE
            binding.homeScreen.homeServerActions.visibility = View.GONE
            binding.homeScreen.btnHomePrimary.setText(R.string.connect)
            binding.homeScreen.btnHomeNewChat.isEnabled = false
            binding.homeScreen.btnHomeTest.isEnabled = false
            setHomeStatus(getString(R.string.toolbar_status_idle), R.color.text_tertiary)
            renderRecents()
            return
        }

        val label = config.displayName.takeIf { it.isNotBlank() }
            ?: getString(R.string.app_name)
        binding.homeScreen.homeServerName.text = label
        binding.homeScreen.homeServerChip.text = target.displayHost
        binding.homeScreen.homeServerChip.visibility = View.VISIBLE
        binding.homeScreen.homeServerUrl.text = target.startUrl
        binding.homeScreen.homeServerUrl.visibility = View.VISIBLE
        binding.homeScreen.homeServerActions.visibility = View.VISIBLE
        binding.homeScreen.btnHomePrimary.setText(R.string.home_continue)
        binding.homeScreen.btnHomeNewChat.isEnabled = true
        binding.homeScreen.btnHomeTest.isEnabled = true
        setHomeStatus(currentStatusText(), connectionState.colorRes)
        renderRecents()
    }

    private fun setHomeStatus(text: String, @ColorRes colorRes: Int) {
        binding.homeScreen.homeStatusLabel.text = text
        binding.homeScreen.homeStatusLabel.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.homeScreen.homeStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun renderRecents() {
        val list = binding.homeScreen.recentsList
        list.removeAllViews()
        val entries = recents.list()
        binding.homeScreen.recentsEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.homeScreen.btnClearRecents.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        for (entry in entries) {
            val row = layoutInflater.inflate(R.layout.view_recent_row, list, false)
            row.findViewById<android.widget.TextView>(R.id.recentTitle).text =
                entry.title.takeIf { it.isNotBlank() } ?: pathLabel(entry.url)
            row.findViewById<android.widget.TextView>(R.id.recentMeta).text = getString(
                R.string.home_recent_meta,
                pathLabel(entry.url),
                relativeTime(entry.updatedAt),
            )
            row.setOnClickListener {
                pendingUrl = entry.url
                openChat(entry.url)
            }
            row.setOnLongClickListener {
                promptRename(entry)
                true
            }
            list.addView(row)
        }
    }

    private fun pathLabel(url: String): String = RecentsCodec.labelOf(url)

    private fun relativeTime(at: Long): String {
        if (at <= 0L) return ""
        val delta = System.currentTimeMillis() - at
        val minutes = delta / 60_000L
        return when {
            minutes < 1L -> getString(R.string.home_just_now)
            minutes < 60L -> getString(R.string.home_minutes_ago, minutes.toInt())
            minutes < 1_440L -> getString(R.string.home_hours_ago, (minutes / 60).toInt())
            else -> getString(R.string.home_days_ago, (minutes / 1_440).toInt())
        }
    }

    private fun promptRename(entry: RecentEntry) {
        val input = android.widget.EditText(this).apply {
            setText(entry.title.ifBlank { pathLabel(entry.url) })
            setSelection(text.length)
            setSingleLine()
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
        }
        val wrapper = FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 4, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        android.app.AlertDialog.Builder(this, R.style.Theme_CodeBuddy_Dialog)
            .setTitle(R.string.home_rename_title)
            .setView(wrapper)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                recents.rename(entry.url, input.text.toString())
                renderRecents()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copyServerUrl() {
        val url = currentTarget?.startUrl ?: return
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(getString(R.string.server_url_label), url),
        )
        toast(R.string.home_url_copied)
    }

    private fun openServerInBrowser() {
        val url = currentTarget?.startUrl ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast(R.string.external_link_error)
        }
    }

    private fun testCurrentServer() {
        val target = currentTarget ?: return
        if (!isNetworkAvailable()) {
            toast(R.string.probe_no_network)
            return
        }
        setHomeStatus(getString(R.string.test_testing), R.color.warning)
        probeExecutor.execute {
            val probe = ServerProbe.probe(target)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (probe) {
                    is ProbeResult.Reachable -> {
                        val message = if (probe.loginRequired) {
                            getString(R.string.test_auth)
                        } else {
                            getString(R.string.test_ok)
                        }
                        setHomeStatus(message, R.color.success)
                    }

                    is ProbeResult.Failed -> setHomeStatus(
                        getString(R.string.test_failed, probeMessage(probe)),
                        R.color.error,
                    )
                }
            }
        }
    }

    private fun showMoreSheet() {
        val content = layoutInflater.inflate(R.layout.view_more_sheet, null, false)
        content.findViewById<android.widget.TextView>(R.id.sheetTitle).text =
            currentConfig?.displayName?.takeIf { it.isNotBlank() }
                ?: currentTarget?.displayHost
                ?: getString(R.string.toolbar_title)

        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_CodeBuddy_Dialog)
            .setView(content)
            .create()
        dialog.setCanceledOnTouchOutside(true)

        fun bind(rowId: Int, action: () -> Unit) {
            content.findViewById<View>(rowId).setOnClickListener {
                dialog.dismiss()
                action()
            }
        }

        bind(R.id.sheetRowChat) { openChat() }
        bind(R.id.sheetRowNew) { openNewChat() }
        bind(R.id.sheetRowSidebar) { webShell.toggleSidebar() }
        bind(R.id.sheetRowBrowser) { openServerInBrowser() }
        bind(R.id.sheetRowHome) { showHome() }
        bind(R.id.sheetRowSettings) { openSettings() }

        dialog.show()
    }

    // ------------------------------------------------------------------
    // Connection handling
    // ------------------------------------------------------------------

    private fun handleConnect(url: String, name: String, remember: Boolean, scale: Float) {
        val view = configView ?: return
        when (val result = ServerUrl.parse(url)) {
            is UrlResult.Error -> view.showUrlError(messageFor(result.reason))

            is UrlResult.Ok -> {
                val config = ServerConfig(
                    url = result.target.startUrl,
                    displayName = name.trim().take(40),
                    remember = remember,
                )
                val nextScale = UiScale.clamp(scale)
                val scaleChanged = nextScale != appliedScale
                store.save(config)
                store.saveScale(nextScale)
                if (scaleChanged) {
                    appliedScale = nextScale
                    webShell.applyUiScale(nextScale)
                }

                val previous = currentTarget
                val changed = previous == null || previous.origin != result.target.origin
                currentConfig = config
                currentTarget = result.target
                if (changed) recents.retainOrigin(result.target.origin)
                setupMode = false
                binding.settingsContainer.visibility = View.GONE
                hideIme()

                pendingUrl = null
                if (changed) {
                    webShell.setScale(nextScale)
                    showChat()
                    connectCurrent(
                        config = config,
                        target = result.target,
                        url = result.target.startUrl,
                    )
                } else if (scaleChanged) {
                    showChat()
                    connectCurrent(
                        config = config,
                        target = result.target,
                        url = webShell.currentUrl() ?: result.target.startUrl,
                        fullScreenLoading = false,
                    )
                } else if (webShell.hasContent()) {
                    showChat()
                    restoreConnectionState()
                } else {
                    showChat()
                    connectCurrent(config, result.target, result.target.startUrl)
                }
            }
        }
    }

    private fun connectCurrent(
        config: ServerConfig,
        target: ServerTarget,
        url: String,
        fullScreenLoading: Boolean = true,
    ) {
        currentConfig = config
        currentTarget = target
        lastLoadFailed = false
        suppressFailure = false
        awaitingLoad = true
        binding.errorOverlay.visibility = View.GONE
        binding.loadingOverlay.visibility = if (fullScreenLoading) View.VISIBLE else View.GONE
        binding.progress.visibility = View.VISIBLE
        setStatus(ConnectionState.CONNECTING)
        webShell.load(target, url)
        armConnectCheck()
    }

    private fun reconnect() {
        val target = currentTarget ?: return
        if (binding.settingsContainer.visibility == View.VISIBLE) closeSettings()
        binding.errorOverlay.visibility = View.GONE

        if (lastLoadFailed || connectionState == ConnectionState.ERROR) {
            val config = currentConfig ?: return
            showChat()
            connectCurrent(config, target, target.startUrl)
            return
        }

        showChat()
        binding.progress.visibility = View.VISIBLE
        lastLoadFailed = false
        suppressFailure = false
        awaitingLoad = true
        setStatus(ConnectionState.CONNECTING)
        webShell.reconnect()
        armConnectCheck()
    }

    /**
     * A restored WebView can render an empty document when the cache entry is
     * gone. If nothing painted shortly after restoring, load the saved page
     * instead of leaving a blank chat.
     */
    private fun armRestoreFallback(config: ServerConfig, target: ServerTarget) {
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (contentLoadedOnce || screen != Screen.CHAT) return@postDelayed
            val url = webShell.currentUrl()?.takeIf { it.startsWith(target.origin) }
                ?: target.startUrl
            connectCurrent(config, target, url)
        }, RESTORE_FALLBACK_MS)
    }

    private fun restoreConnectionState() {
        contentLoadedOnce = contentLoadedOnce || webShell.hasContent()
        setStatus(
            when {
                !contentLoadedOnce -> ConnectionState.CONNECTING
                lastStableState == ConnectionState.IDLE -> ConnectionState.CONNECTED
                else -> lastStableState
            },
        )
    }

    /**
     * A WebView request to an unreachable host can stay pending for minutes with
     * no error callback. Probe in parallel and surface a specific failure.
     */
    private fun armConnectCheck() {
        cancelChecks()
        val runnable = Runnable { onConnectStalled() }
        connectCheck = runnable
        binding.root.postDelayed(runnable, CONNECT_PROBE_DELAY_MS)
    }

    private fun onConnectStalled() {
        connectCheck = null
        if (!awaitingLoad || connectionState != ConnectionState.CONNECTING) return
        val target = currentTarget ?: return

        if (!isNetworkAvailable()) {
            showConnectFailure(getString(R.string.error_offline))
            return
        }

        probeExecutor.execute {
            val probe = ServerProbe.probe(target)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!awaitingLoad || connectionState != ConnectionState.CONNECTING) {
                    return@runOnUiThread
                }
                when (probe) {
                    is ProbeResult.Reachable -> armHardTimeout()
                    is ProbeResult.Failed -> showConnectFailure(probeFailureMessage(probe))
                }
            }
        }
    }

    private fun armHardTimeout() {
        cancelChecks()
        val runnable = Runnable {
            connectCheck = null
            if (awaitingLoad && connectionState == ConnectionState.CONNECTING) {
                showConnectFailure(getString(R.string.error_stalled))
            }
        }
        connectCheck = runnable
        binding.root.postDelayed(runnable, CONNECT_HARD_TIMEOUT_MS)
    }

    private fun showConnectFailure(message: String) {
        cancelChecks()
        awaitingLoad = false
        lastLoadFailed = true
        suppressFailure = true
        webShell.abortLoad()
        binding.loadingOverlay.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.errorMessage.text = message
        binding.errorOverlay.visibility = View.VISIBLE
        setStatus(ConnectionState.ERROR)
    }

    private fun probeFailureMessage(failed: ProbeResult.Failed): String {
        return when (failed.kind) {
            ProbeResult.Failure.REFUSED -> if (isLoopbackHost()) {
                getString(R.string.error_loopback)
            } else {
                getString(R.string.error_refused_lan)
            }

            ProbeResult.Failure.TIMEOUT -> getString(R.string.error_timeout_lan)
            ProbeResult.Failure.UNKNOWN_HOST -> getString(R.string.error_host_lookup)
            ProbeResult.Failure.TLS -> getString(R.string.error_ssl)
            ProbeResult.Failure.NOT_CODEBUDDY -> getString(R.string.probe_not_codebuddy)
            ProbeResult.Failure.HTTP -> getString(R.string.error_http, failed.httpCode ?: 0)
            ProbeResult.Failure.GENERIC -> getString(R.string.error_stalled)
        }
    }

    private fun isLoopbackHost(): Boolean {
        val host = currentTarget?.host ?: return false
        return host == "localhost" ||
            host == "::1" ||
            host == "0:0:0:0:0:0:0:1" ||
            host.startsWith("127.")
    }

    private fun cancelChecks() {
        connectCheck?.let { binding.root.removeCallbacks(it) }
        connectCheck = null
        statusCheck?.let { binding.root.removeCallbacks(it) }
        statusCheck = null
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

    private fun confirmClearSession() {
        android.app.AlertDialog.Builder(this, R.style.Theme_CodeBuddy_Dialog)
            .setTitle(R.string.clear_session_title)
            .setMessage(R.string.clear_session_message)
            .setPositiveButton(R.string.clear_session) { _, _ ->
                webShell.clearBrowsingData {
                    recents.clear()
                    contentLoadedOnce = false
                    lastLoadFailed = false
                    binding.errorOverlay.visibility = View.GONE
                    setStatus(ConnectionState.IDLE)
                    val target = currentTarget
                    val config = currentConfig
                    if (target != null && config != null) {
                        showChat()
                        connectCurrent(config, target, target.startUrl)
                    } else {
                        showHome()
                    }
                    toast(R.string.clear_session_done)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                    if (connectionState == ConnectionState.OFFLINE) setStatus(lastStableState)
                    if (screen == Screen.HOME) refreshHome()
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
        binding.chatSubtitle.text = if (host.isNullOrBlank()) {
            label
        } else {
            getString(R.string.server_status_format, host, label)
        }
        binding.chatTitle.text = currentConfig?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.toolbar_title)

        if (screen == Screen.HOME) setHomeStatus(currentStatusText(), state.colorRes)
        if (state == ConnectionState.CONNECTED || state == ConnectionState.LOGIN_REQUIRED) {
            lastStableState = state
        }
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

    private fun primaryAbi(): String {
        val abis = Build.SUPPORTED_ABIS
        return if (abis.isNotEmpty()) abis[0] else "unknown"
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // WebShell callbacks
    // ------------------------------------------------------------------

    override fun onLoading(progress: Int) {
        if (progress >= 100) {
            binding.progress.visibility = View.GONE
            binding.loadingOverlay.visibility = View.GONE
        } else {
            if (binding.errorOverlay.visibility == View.VISIBLE) {
                binding.errorOverlay.visibility = View.GONE
            }
            binding.progress.visibility = View.VISIBLE
            if (!contentLoadedOnce) binding.loadingOverlay.visibility = View.VISIBLE
        }
    }

    override fun onContentVisible() {
        contentLoadedOnce = true
        awaitingLoad = false
        binding.loadingOverlay.visibility = View.GONE
        cancelChecks()
        scheduleStatusCheck()
    }

    private fun scheduleStatusCheck() {
        if (connectionState != ConnectionState.CONNECTING) return
        val runnable = Runnable {
            statusCheck = null
            if (isFinishing || isDestroyed) return@Runnable
            if (connectionState != ConnectionState.CONNECTING) return@Runnable
            webShell.refreshLoginState { login ->
                if (!isFinishing && !isDestroyed) onLoaded(login)
            }
        }
        statusCheck = runnable
        binding.root.postDelayed(runnable, 1_500)
    }

    override fun onLoaded(loginRequired: Boolean) {
        cancelChecks()
        awaitingLoad = false
        suppressFailure = false
        contentLoadedOnce = true
        binding.loadingOverlay.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.errorOverlay.visibility = View.GONE
        lastLoadFailed = false
        setStatus(
            if (loginRequired) ConnectionState.LOGIN_REQUIRED else ConnectionState.CONNECTED,
        )
    }

    override fun onFailure(failure: ShellFailure) {
        if (suppressFailure) return
        cancelChecks()
        awaitingLoad = false
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

        if (screen == Screen.CHAT) binding.errorOverlay.visibility = View.VISIBLE
        setStatus(ConnectionState.ERROR)
    }

    override fun onLocationChanged(url: String, title: String?) {
        if (url.isBlank()) return
        val target = currentTarget ?: return
        if (!url.startsWith(target.origin)) return
        recents.touch(url, title)
        if (screen == Screen.HOME) renderRecents()
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        webShell.attach(this)
        webShell.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        webShell.onPause()
        webShell.currentUrl()?.let { url ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                recents.touch(url)
            }
        }
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
        outState.putBoolean(KEY_CONTENT_LOADED, contentLoadedOnce || webShell.hasContent())
        outState.putString(KEY_STABLE_STATE, lastStableState.name)
        if (!isChangingConfigurations) {
            val state = Bundle()
            webShell.saveState(state)
            outState.putBundle(KEY_SHELL_STATE, state)
        }
    }

    override fun onDestroy() {
        cancelChecks()
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
        if (isChangingConfigurations) {
            // Keep the live WebView for the new activity instance.
            webShell.detach()
        } else {
            webShell.onDestroy()
        }
        super.onDestroy()
    }

    private enum class Screen { HOME, CHAT }

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
        const val KEY_SHELL_STATE = "shell_state"
        const val KEY_CONTENT_LOADED = "content_loaded"
        const val KEY_STABLE_STATE = "stable_state"
        const val CONNECT_PROBE_DELAY_MS = 8_000L
        const val RESTORE_FALLBACK_MS = 3_500L
        const val CONNECT_HARD_TIMEOUT_MS = 20_000L
    }
}
