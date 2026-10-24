package com.flux.codebuddy.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.flux.codebuddy.R
import com.flux.codebuddy.ServerConfig

/**
 * Compact two-column server form used both as the first-run screen and as the
 * settings overlay. It is intentionally built from platform widgets to keep
 * the APK small and to match the dark web UI.
 */
class ServerConfigView(private val activity: ComponentActivity) {

    val root: View = activity.layoutInflater.inflate(R.layout.view_server_config, null, false)

    private val title: TextView = root.findViewById(R.id.configTitle)
    private val subtitle: TextView = root.findViewById(R.id.configSubtitle)
    private val status: TextView = root.findViewById(R.id.configStatus)
    private val urlInput: EditText = root.findViewById(R.id.urlInput)
    private val nameInput: EditText = root.findViewById(R.id.nameInput)
    private val rememberCheck: CheckBox = root.findViewById(R.id.rememberCheck)
    private val urlError: TextView = root.findViewById(R.id.urlError)
    private val testStatus: TextView = root.findViewById(R.id.testStatus)
    private val btnTest: Button = root.findViewById(R.id.btnTest)
    private val btnConnect: Button = root.findViewById(R.id.btnConnect)
    private val btnCancel: Button = root.findViewById(R.id.btnCancel)

    var onConnect: ((url: String, name: String, remember: Boolean) -> Unit)? = null
    var onTest: ((url: String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    init {
        btnConnect.setOnClickListener {
            onConnect?.invoke(
                urlInput.text.toString(),
                nameInput.text.toString(),
                rememberCheck.isChecked,
            )
        }
        btnTest.setOnClickListener {
            onTest?.invoke(urlInput.text.toString())
        }
        btnCancel.setOnClickListener {
            onCancel?.invoke()
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
    }

    fun bind(config: ServerConfig?, isSetup: Boolean) {
        title.setText(if (isSetup) R.string.setup_title else R.string.settings_title)
        subtitle.setText(if (isSetup) R.string.setup_subtitle else R.string.settings_subtitle)
        btnCancel.visibility = if (isSetup) View.GONE else View.VISIBLE
        btnConnect.setText(if (isSetup) R.string.connect else R.string.save_and_connect)
        urlInput.setText(config?.url.orEmpty())
        nameInput.setText(config?.displayName.orEmpty())
        rememberCheck.isChecked = config?.remember ?: true
        clearUrlError()
        clearTestStatus()
        setBusy(false)
        if (isSetup) {
            setStatus(activity.getString(R.string.setup_status_idle), R.color.text_secondary)
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
        if (busy) {
            showTestStatus(activity.getString(R.string.test_testing), R.color.warning)
        }
    }
}
