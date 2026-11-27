package ru.misis.devicemanager.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ru.misis.devicemanager.R
import ru.misis.devicemanager.data.Device
import ru.misis.devicemanager.data.DeviceStatus
import ru.misis.devicemanager.qr.*

class QrPortraitActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<TextView>(com.google.zxing.client.android.R.id.zxing_status_view).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            val density = resources.displayMetrics.density
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            layoutParams = layoutParams.apply { width = android.view.ViewGroup.LayoutParams.MATCH_PARENT }
        }
        window.navigationBarColor = android.graphics.Color.BLACK
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
    }
}

class QrActivity : DeviceActivity() {
    private lateinit var model: QrViewModel
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var busyLabel: TextView
    private var scroll: ScrollView? = null
    private var lastRendered: QrState? = null
    private val scanResult = registerForActivityResult(ScanContract()) { it.contents?.let(model::read) }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) launchCamera() else message("Разрешите доступ к камере в настройках или откройте изображение QR.")
    }
    private val imageFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.readFile(uri, true) } }
    private val jsonFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.readFile(uri, false) } }
    private val keyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importPassword) }
    private val savePng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { model.writeFile(it) }
    private val saveJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { model.writeFile(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[QrViewModel::class.java]
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.background)) }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        model.state.observe(this) { state ->
            val visual = state.copy(message = null)
            if (lastRendered != visual) { render(visual); lastRendered = visual }
            state.message?.let { text ->
                val name = model.pendingFileName
                model.consumeMessage()
                when (text) {
                    "PNG_FILE_READY" -> savePng.launch(name ?: "DeviceManager.png")
                    "JSON_FILE_READY", "KEY_FILE_READY" -> saveJson.launch(name ?: "DeviceManager.json")
                    else -> message(text)
                }
            }
        }
        model.initialize(intent.getLongExtra(EXTRA_ID, 0))
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_SCAN, false)) camera()
    }

    private fun render(state: QrState) {
        // File preparation can finish before a replacement ScrollView has laid out.
        // Keep the existing view (and its focus/scroll) when only loading changes.
        if (lastRendered?.copy(busy = state.busy) == state) {
            updateBusy(state.busy)
            return
        }
        val restoreY = if (lastRendered?.page == state.page) scroll?.scrollY ?: 0 else 0
        root.removeAllViews()
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(16), 0) }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_device_back)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.text_primary))
            setBackgroundResource(android.R.drawable.list_selector_background)
            contentDescription = getString(R.string.back); setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(48), dp(56)))
        bar.addView(TextView(this).apply {
            text = when (state.page) { QrPage.HOME -> "QR-метки"; QrPage.EXPORT -> "QR устройства"; QrPage.PREVIEW -> "Считано из QR"; QrPage.KEYS -> "Ключи организации" }
            androidx.core.widget.TextViewCompat.setTextAppearance(this, R.style.DeviceScreenTitle); setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        busyLabel = TextView(this).apply {
            text = "Подготовка данных…"; textSize = 14f; setTextColor(color(R.color.device_secondary))
            setPadding(dp(20), dp(8), dp(20), dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(busyLabel)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        val newScroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        scroll = newScroll
        root.addView(newScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (state.page) {
            QrPage.HOME -> home()
            QrPage.EXPORT -> export(state)
            QrPage.PREVIEW -> preview(state)
            QrPage.KEYS -> keys(state)
        }
        updateBusy(state.busy)
        newScroll.post { newScroll.scrollTo(0, restoreY) }
    }

    private fun home() {
        heading("Сканирование и обмен")
        paragraph("Считайте QR с корпуса устройства или откройте изображение. Перед добавлением вы увидите все поля карточки.")
        button("Сканировать камерой", true) { camera() }
        button("Открыть изображение QR") { imageFile.launch(arrayOf("image/*")) }
        button("Открыть JSON-файл") { jsonFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        section("Создать метку")
        paragraph("Выберите устройство в реестре и нажмите «QR-код» в меню записи. Открытый QR читается всеми, защищённый — сотрудниками с ключом организации.")
        button("Ключи организации") { model.keys() }
    }

    private fun export(state: QrState) {
        val d = state.device ?: return
        heading(d.name); paragraph(d.inventoryNumber + " · " + d.organization + "\n" + d.location)
        val modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        fun radio(label: String) = RadioButton(this).apply { id = View.generateViewId(); text = label; minHeight = dp(48); setTextColor(color(R.color.text_primary)) }
        val open = radio("Открытый\nJSON"); val secure = radio("Защищённый\nAES + RSA")
        modes.addView(open, RadioGroup.LayoutParams(0, -2, 1f)); modes.addView(secure, RadioGroup.LayoutParams(0, -2, 1f))
        modes.check(if (state.secure) secure.id else open.id)
        modes.setOnCheckedChangeListener { _, id -> model.generate(id == secure.id) }; content.addView(modes)
        state.bitmap?.let { bitmap ->
            content.addView(ImageView(this).apply {
                setImageBitmap(bitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(android.graphics.Color.WHITE); contentDescription = "QR-код " + d.name + ", " + d.inventoryNumber
            }, LinearLayout.LayoutParams(-1, dp(280)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        }
        paragraph(if (state.secure) "Для чтения нужен тот же ключ организации. Метка — снимок карточки; после изменения данных её нужно создать заново."
            else "Все поля, включая ответственного и заметки, доступны любому сканеру. После изменения карточки метку нужно создать заново.")
        if (state.bitmap != null) button("Сохранить QR как PNG", true) { model.prepareOutput(true) }
        state.qrIssue?.let { paragraph(it) }
        button("Сохранить JSON-файл") { model.prepareOutput(false) }
        button("Ключи организации") { model.keys() }
    }

    private fun preview(state: QrState) {
        val d = state.device ?: return
        heading(d.name)
        paragraph(if (state.secure) "Защищённый QR расшифрован. Проверьте данные перед сохранением." else "Открытый QR прочитан. Проверьте источник и данные перед сохранением.")
        listOf("Инвентарный номер" to d.inventoryNumber, "Серийный номер" to d.serialNumber,
            "Тип" to d.category, "Организация" to d.organization, "Корпус" to d.building,
            "Кабинет / аудитория" to d.room, "Ответственный" to d.responsible,
            "Статус" to if (d.status == DeviceStatus.ACTIVE) "Действующее" else "На списание",
            "Причина списания" to d.writeOffReason, "Заметки" to d.notes).filter { it.second.isNotBlank() }
            .forEach { (label, value) -> detail(label, value) }
        val existing = state.existing
        when {
            existing == null -> button("Добавить в реестр", true) { editor(d) }
            existing.deletedAt != null -> {
                section("Запись уже в корзине")
                paragraph("Восстановите устройство через корзину реестра. Повторная запись с тем же номером и организацией не создаётся.")
            }
            else -> {
                section("Уже есть в реестре")
                paragraph(existing.name + "\nТекущее место: " + existing.location + "\n" + if (existing.status == DeviceStatus.ACTIVE) "Действующее" else "На списание")
                button("Открыть текущую карточку", true) {
                    startActivity(Intent(this, DeviceEditorActivity::class.java).putExtra(DeviceEditorActivity.EXTRA_ID, existing.id))
                }
                button("Перенести данные из QR") {
                    MaterialAlertDialogBuilder(this).setTitle("Обновить карточку из QR?")
                        .setMessage("Откроется редактор с данными метки. Проверьте их: метка может содержать устаревшее место или статус. Изменения вступят в силу только после сохранения.")
                        .setNegativeButton(R.string.cancel, null).setPositiveButton("Перейти к проверке") { _, _ -> editor(d, existing.id) }.show()
                }
            }
        }
        button("Сканировать ещё") { camera() }
    }

    private fun keys(state: QrState) {
        heading("Доступ к защищённым меткам")
        paragraph("Передайте сотрудникам файл ключа под паролем, а пароль — отдельно. Ключи хранятся на этом телефоне. После удаления приложения восстановить доступ можно только из сохранённого файла.")
        if (state.keys.isEmpty()) paragraph("Ключей пока нет.")
        state.keys.forEach { entry ->
            section(entry.label)
            paragraph(entry.id.chunked(4).joinToString(" ") + "\n" + if (entry.id == state.activeId) "Выбран для новых QR" else "Доступен для расшифровки")
            if (entry.id != state.activeId) button("Использовать этот ключ") { model.selectKey(entry.id) }
        }
        button("Создать ключ") { inputDialog("Название ключа", "Например, МИСИС · корпус А", false) { chars ->
            model.createKey(chars.concatToString()); chars.fill('\u0000')
        } }
        button("Импортировать ключ") { keyFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
        if (state.keys.isNotEmpty()) button("Сохранить выбранный ключ", true) {
            inputDialog("Пароль файла ключа", "Не менее 12 символов", true, 12, model::prepareKeyFile)
        }
        if (state.device != null) button("Вернуться к QR устройства") { model.generate(state.secure) }
    }

    private fun importPassword(uri: Uri) { inputDialog("Пароль файла ключа", "Пароль от владельца ключа", true) { model.importKey(uri, it) } }
    private fun inputDialog(title: String, hint: String, secret: Boolean, minLength: Int = 1, action: (CharArray) -> Unit) {
        val layout = TextInputLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); this.hint = hint }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
            isSaveEnabled = false; setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(if (secret) 256 else 120))
        }
        layout.addView(input)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(title).setView(layout)
            .setNegativeButton(R.string.cancel, null).setPositiveButton("Продолжить", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val chars = input.text?.toString().orEmpty().toCharArray()
            if (chars.size < minLength) { chars.fill('\u0000'); layout.error = "Минимум $minLength символов" }
            else { input.text?.clear(); dialog.dismiss(); action(chars) }
        } }
        dialog.setOnDismissListener { input.text?.clear() }; dialog.show()
    }
    private fun editor(d: Device, id: Long = 0) {
        startActivity(Intent(this, DeviceEditorActivity::class.java).putExtra(DeviceEditorActivity.EXTRA_ID, id)
            .putExtra(DeviceEditorActivity.EXTRA_IMPORT, DeviceQrCodec.encodeOpen(d)))
    }
    private fun camera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    private fun launchCamera() {
        scanResult.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Наведите камеру на QR устройства")
            .setBeepEnabled(false).setOrientationLocked(true).setCaptureActivity(QrPortraitActivity::class.java))
    }
    private fun back() {
        if (model.state.value!!.busy) { finish(); return }
        if (model.state.value!!.page == QrPage.HOME || intent.getLongExtra(EXTRA_ID, 0) != 0L && model.state.value!!.page == QrPage.EXPORT) finish() else model.home()
    }
    private fun heading(value: String) { content.addView(TextView(this).apply { text = value; androidx.core.widget.TextViewCompat.setTextAppearance(this, R.style.DeviceHeadline); setPadding(0, dp(8), 0, dp(12)) }) }
    private fun section(value: String) { content.addView(TextView(this).apply { text = value; androidx.core.widget.TextViewCompat.setTextAppearance(this, R.style.DeviceSection); setPadding(0, dp(20), 0, dp(4)) }) }
    private fun detail(label: String, value: String) {
        content.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(color(R.color.device_secondary)); setPadding(0, dp(8), 0, dp(3)) })
        paragraph(value, false)
    }
    private fun paragraph(value: String, secondary: Boolean = true) { content.addView(TextView(this).apply {
        text = value; textSize = 16f; setTextColor(color(if (secondary) R.color.device_secondary else R.color.text_primary))
        setTextIsSelectable(true); setPadding(0, 0, 0, dp(12))
    }) }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) {
        content.addView(MaterialButton(this, null, if (primary) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; minHeight = dp(52); setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    private fun updateBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        busyLabel.visibility = if (busy) View.VISIBLE else View.GONE
        setEnabled(content, !busy)
    }
    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) setEnabled(view.getChildAt(i), enabled)
    }
    private fun message(text: String) {
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).apply {
            view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.maxLines = 6
        }.show()
    }
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { const val EXTRA_ID = "qr_device_id"; const val EXTRA_SCAN = "qr_start_camera" }
}
