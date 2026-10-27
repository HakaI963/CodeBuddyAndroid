package com.flux.codebuddy.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.flux.codebuddy.BuildConfig
import com.flux.codebuddy.R
import com.flux.codebuddy.ServerConfig
import com.flux.codebuddy.UiScale
import java.util.Locale

/**
 * Server form used both as the first-run screen and as the settings sheet.
 * Built from platform widgets so it stays small and matches the native theme.
 */
class ServerConfigView(private val activity: ComponentActivity) {

    val root: View = activity.layoutInflater.inflate(
        R.layout.view_server_config,
        FrameLayout(activity),
        false,
    )

    private val title: TextView = root.findViewById(R.id.configTitle)
    private val subtitle: TextView = root.findViewById(R.id.configSubtitle)
    private val status: TextView = root.findViewById(R.id.configStatus)
    private val urlInput: EditText = root.findViewById(R.id.urlInput)
    private val nameInput: EditText = root.findViewById(R.id.nameInput)
    private val rememberCheck: CheckBox = root.findViewById(R.id.rememberCheck)
    private val keepAwakeCheck: CheckBox = root.findViewById(R.id.keepAwakeCheck)
    private val scaleSeek: SeekBar = root.findViewById(R.id.scaleSeek)
    private val scaleValue: TextView = root.findViewById(R.id.scaleValue)
    private val urlError: TextView = root.findViewById(R.id.urlError)
    private val testStatus: TextView = root.findViewById(R.id.testStatus)
    private val btnTest: Button = root.findViewById(R.id.btnTest)
    private val btnConnect: Button = root.findViewById(R.id.btnConnect)
    private val btnCancel: Button = root.findViewById(R.id.btnCancel)
    private val btnClose: ImageButton = root.findViewById(R.id.btnCloseSettings)
    private val btnClearSession: Button = root.findViewById(R.id.btnClearSession)
    private val version: TextView = root.findViewById(R.id.settingsVersion)

    private var scale: Float = UiScale.DEFAULT

    var onConnect: ((url: String, name: String, remember: Boolean, scale: Float) -> Unit)? = null
    var onTest: ((url: String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onClearSession: (() -> Unit)? = null
    var onKeepAwakeChanged: ((Boolean) -> Unit)? = null

    init {
        btnConnect.setOnClickListener {
            onConnect?.invoke(
                urlInput.text.toString(),
                nameInput.text.toString(),
                rememberCheck.isChecked,
                scale,
            )
        }
        scaleSeek.max = UiScale.STEPS.size - 1
        scaleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                scale = UiScale.STEPS[progress.coerceIn(0, UiScale.STEPS.size - 1)]
                renderScale()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        btnTest.setOnClickListener { onTest?.invoke(urlInput.text.toString()) }
        btnCancel.setOnClickListener { onCancel?.invoke() }
        btnClose.setOnClickListener { onCancel?.invoke() }
        btnClearSession.setOnClickListener { onClearSession?.invoke() }
        keepAwakeCheck.setOnCheckedChangeListener { _, checked ->
            onKeepAwakeChanged?.invoke(checked)
        }
        urlInput.doAfterTextChanged { clearUrlError() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                nameInput.requestFocus()
                true
            } else {
                false
            }
        }
        nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnConnect.performClick()
                true
            } else {
                false
            }
        }
        version.text = activity.getString(
            R.string.settings_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
    }

    fun bind(config: ServerConfig?, isSetup: Boolean, uiScale: Float, keepAwake: Boolean) {
        title.setText(if (isSetup) R.string.setup_title else R.string.settings_title)
        subtitle.setText(if (isSetup) R.string.setup_subtitle else R.string.settings_subtitle)
        btnCancel.visibility = if (isSetup) View.GONE else View.VISIBLE
        btnClose.visibility = if (isSetup) View.GONE else View.VISIBLE
        btnConnect.setText(if (isSetup) R.string.connect else R.string.save_and_connect)
        btnClearSession.visibility = if (isSetup) View.GONE else View.VISIBLE
        urlInput.setText(config?.url.orEmpty())
        nameInput.setText(config?.displayName.orEmpty())
        rememberCheck.isChecked = config?.remember ?: true
        keepAwakeCheck.isChecked = keepAwake
        scale = UiScale.nearest(uiScale)
        scaleSeek.progress = UiScale.indexOf(scale)
        renderScale()
        clearUrlError()
        clearTestStatus()
        setBusy(false)
        if (isSetup) {
            setStatus(activity.getString(R.string.setup_status_idle), R.color.text_secondary)
        }
    }

    private fun renderScale() {
        val value = String.format(Locale.US, "%.2f", scale)
        scaleValue.text = if (scale >= UiScale.MAX) {
            activity.getString(R.string.ui_scale_largest, value)
        } else {
            activity.getString(
                R.string.ui_scale_value,
                value,
                String.format(Locale.US, "%.1f", 1f / scale),
            )
        }
    }

    fun focusUrl() {
        urlInput.requestFocus()
        urlInput.setSelection(urlInput.text.length)
    }

    fun setStatus(text: String, colorRes: Int) {
        status.text = text
        status.setTextColor(ContextCompat.getColor(activity, colorRes))
    }

    fun showUrlError(message: String?) {
        if (message.isNullOrBlank()) {
            urlError.visibility = View.GONE
            urlError.text = ""
        } else {
            urlError.text = message
            urlError.visibility = View.VISIBLE
            urlInput.requestFocus()
        }
    }

    fun clearUrlError() {
        urlError.visibility = View.GONE
        urlError.text = ""
    }

    fun showTestStatus(text: String, colorRes: Int) {
        testStatus.text = text
        testStatus.setTextColor(ContextCompat.getColor(activity, colorRes))
        testStatus.visibility = View.VISIBLE
    }

    fun clearTestStatus() {
        testStatus.visibility = View.GONE
        testStatus.text = ""
    }

    fun setBusy(busy: Boolean) {
        btnTest.isEnabled = !busy
        btnConnect.isEnabled = !busy
        urlInput.isEnabled = !busy
        nameInput.isEnabled = !busy
        rememberCheck.isEnabled = !busy
        scaleSeek.isEnabled = !busy
        if (busy) {
            showTestStatus(activity.getString(R.string.test_testing), R.color.warning)
        }
    }
}
