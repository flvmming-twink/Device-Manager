package ru.misis.devicemanager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import ru.misis.devicemanager.DeviceManagerApplication
import ru.misis.devicemanager.R
import ru.misis.devicemanager.data.CloudSyncState

class CloudSyncActivity : DeviceActivity() {
    private val manager by lazy { (application as DeviceManagerApplication).cloudSync }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_sync)
        manager.startIfConnected()
        findViewById<ImageButton>(R.id.cloudBack).setOnClickListener { finish() }
        findViewById<View>(R.id.cloudCreate).setOnClickListener {
            lifecycleScope.launch {
                runCatching { manager.createWorkspace() }
                    .onSuccess { Toast.makeText(this@CloudSyncActivity, R.string.cloud_create_success, Toast.LENGTH_LONG).show() }
            }
        }
        findViewById<View>(R.id.cloudJoin).setOnClickListener {
            val code = findViewById<TextInputEditText>(R.id.cloudCodeInput).text?.toString().orEmpty()
            lifecycleScope.launch {
                runCatching { manager.joinWorkspace(code) }
                    .onSuccess { Toast.makeText(this@CloudSyncActivity, R.string.cloud_join_success, Toast.LENGTH_LONG).show() }
            }
        }
        findViewById<View>(R.id.cloudCopy).setOnClickListener {
            manager.shareCode()?.let { code ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.cloud_title), code))
                Toast.makeText(this, R.string.cloud_code_copied, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.cloudShare).setOnClickListener {
            manager.shareCode()?.let { code ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, code)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.cloud_title))
                }, getString(R.string.cloud_share_code)))
            }
        }
        findViewById<View>(R.id.cloudSetup).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cloud_setup_dialog_title)
                .setMessage(R.string.cloud_setup_dialog_body)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        manager.state.observe(this, ::render)
    }

    private fun render(state: CloudSyncState) {
        val configured = state.configured
        val code = manager.shareCode()
        findViewById<TextView>(R.id.cloudStatus).text = state.error ?: when {
            !configured -> getString(R.string.cloud_status_unavailable)
            code == null -> getString(R.string.cloud_status_not_joined)
            state.connected -> getString(R.string.cloud_status_online)
            else -> getString(R.string.cloud_status_offline)
        }
        findViewById<ProgressBar>(R.id.cloudProgress).isVisible = state.busy
        findViewById<Button>(R.id.cloudCreate).isEnabled = configured && !state.busy
        findViewById<Button>(R.id.cloudJoin).isEnabled = configured && !state.busy
        findViewById<EditText>(R.id.cloudCodeInput).isEnabled = configured && !state.busy
        findViewById<View>(R.id.cloudSetup).isVisible = !configured
        findViewById<View>(R.id.cloudCodeGroup).isVisible = code != null
        findViewById<TextView>(R.id.cloudCode).text = code.orEmpty()
    }
}
