package com.apsu.gamestream

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.apsu.gamestream.encoder.EncoderSelector
import com.apsu.gamestream.model.CodecPreference
import com.apsu.gamestream.model.ResolutionPreset
import com.apsu.gamestream.model.ServerState
import com.apsu.gamestream.model.StreamConfig
import com.apsu.gamestream.nativebridge.NativeBridge
import com.apsu.gamestream.pairing.PairingPin
import com.apsu.gamestream.server.GameStreamProtocol
import com.apsu.gamestream.server.Ports
import com.apsu.gamestream.service.ProjectionStreamService
import java.net.NetworkInterface

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var connectionText: TextView
    private lateinit var encoderText: TextView
    private lateinit var codecSpinner: Spinner
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var bitrateInput: EditText
    private lateinit var pairingPinInput: EditText
    private lateinit var audioSwitch: Switch

    private val statusPoll = object : Runnable {
        override fun run() {
            updateStatusUi()
            connectionText.text = connectionSummary()
            syncPairingPinInput()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        styleSystemBars(window)
        setContentView(buildContent())
        refreshEncoderPreview()
        handler.post(statusPoll)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusPoll)
        super.onDestroy()
    }

    @Deprecated("MediaProjection still uses startActivityForResult without AndroidX here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_LONG).show()
            return
        }
        val config = selectedConfig()
        val startIntent = ProjectionStreamService.startIntent(this, resultCode, data, config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(COLOR_BLACK)
        }

        root.addView(TextView(this).apply {
            text = "Apsu"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            letterSpacing = 0f
        })
        root.addView(TextView(this).apply {
            text = "Android GameStream host"
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setPadding(0, 0, 0, dp(8))
        })

        statusText = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).withBottom(dp(12))
        }
        root.addView(statusText)

        connectionText = TextView(this).apply {
            text = connectionSummary()
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setLineSpacing(0f, 1.12f)
        }
        root.addView(section("HOST", connectionText))

        pairingPinInput = EditText(this).apply {
            hint = "PIN from Artemis/Moonlight"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            styleInput()
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val pairingSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Pairing PIN shown by client"))
            addView(pairingPinInput)
            addView(actionButton("Use pairing PIN", ButtonTone.ACCENT).apply {
                setOnClickListener { applyPairingPinFromInput() }
            })
        }
        root.addView(section("PAIR", pairingSection))

        codecSpinner = spinner(CodecPreference.entries.map { it.name })
        codecSpinner.setSelection(CodecPreference.H264.ordinal)
        resolutionSpinner = spinner(ResolutionPreset.DEFAULTS.map { "${it.label} (${it.width}x${it.height})" })
        resolutionSpinner.setSelection(1)
        fpsSpinner = spinner(listOf("30", "45", "60", "90", "120"))
        fpsSpinner.setSelection(2)
        bitrateInput = EditText(this).apply {
            setText("16")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Mbps"
            styleInput()
        }
        audioSwitch = Switch(this).apply {
            text = "Audio capture"
            textSize = 15f
            setTextColor(COLOR_TEXT)
            isChecked = true
            setPadding(0, dp(10), 0, dp(2))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                thumbTintList = ColorStateList.valueOf(COLOR_ACCENT)
                trackTintList = ColorStateList.valueOf(COLOR_ACCENT_DIM)
            }
        }
        val streamSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(labeled("Codec", codecSpinner))
            addView(labeled("Resolution", resolutionSpinner))
            addView(labeled("FPS", fpsSpinner))
            addView(labeled("Bitrate (Mbps)", bitrateInput))
            addView(audioSwitch)
        }
        root.addView(section("STREAM", streamSection))

        encoderText = TextView(this).apply {
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setLineSpacing(0f, 1.12f)
        }
        val diagnosticsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(encoderText)
            addView(actionButton("Check hardware encoder", ButtonTone.SECONDARY).apply {
                setOnClickListener { refreshEncoderPreview() }
            })
        }
        root.addView(section("DIAGNOSTICS", diagnosticsSection))

        root.addView(actionButton("Start server", ButtonTone.PRIMARY).apply {
            setOnClickListener { requestProjectionAndStart() }
        })
        root.addView(actionButton("Stop server", ButtonTone.DANGER).apply {
            setOnClickListener { startService(ProjectionStreamService.stopIntent(this@MainActivity)) }
        })

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshEncoderPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        codecSpinner.onItemSelectedListener = listener
        resolutionSpinner.onItemSelectedListener = listener
        fpsSpinner.onItemSelectedListener = listener

        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BLACK)
            addView(root)
        }
    }

    private fun section(title: String, child: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_PANEL, COLOR_STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).withBottom(dp(12))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_ACCENT)
                setPadding(0, 0, 0, dp(8))
            })
            addView(child)
        }

    private fun labeled(label: String, child: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(label(label))
            addView(child)
        }

    private fun label(label: String): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(0, 0, 0, dp(5))
        }

    private fun spinner(items: List<String>): Spinner =
        Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                items,
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    spinnerText(convertView, getItem(position).orEmpty(), dropdown = false)

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    spinnerText(convertView, getItem(position).orEmpty(), dropdown = true)
            }
            background = rounded(COLOR_FIELD, COLOR_STROKE)
            setPadding(dp(10), 0, dp(10), 0)
        }

    private fun spinnerText(convertView: View?, value: String, dropdown: Boolean): TextView =
        (convertView as? TextView ?: TextView(this)).apply {
            text = value
            textSize = 16f
            setTextColor(COLOR_TEXT)
            setBackgroundColor(if (dropdown) COLOR_PANEL_ALT else COLOR_FIELD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

    private fun EditText.styleInput() {
        setTextColor(COLOR_TEXT)
        setHintTextColor(COLOR_MUTED)
        background = rounded(COLOR_FIELD, COLOR_STROKE)
        setSingleLine(true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun actionButton(textValue: String, tone: ButtonTone): Button =
        Button(this).apply {
            text = textValue
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(tone.textColor)
            background = rounded(tone.background, tone.stroke)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).withTop(dp(10))
        }

    private fun updateStatusUi() {
        val state = ProjectionStreamService.currentState
        val tone = when (state) {
            ServerState.STREAMING -> ButtonTone.STREAMING
            ServerState.ERROR -> ButtonTone.DANGER
            ServerState.WAITING_FOR_PROJECTION -> ButtonTone.WARNING
            ServerState.READY -> ButtonTone.ACCENT
            ServerState.IDLE -> ButtonTone.SECONDARY
        }
        statusText.text = "${state.name}: ${ProjectionStreamService.lastMessage}"
        statusText.setTextColor(tone.textColor)
        statusText.background = rounded(tone.background, tone.stroke)
    }

    private fun requestProjectionAndStart() {
        val config = selectedConfig()
        val encoder = runCatching {
            EncoderSelector.select(
                config.codecPreference,
                config.width,
                config.height,
                config.fps,
                config.bitrate,
            )
        }.getOrElse {
            Toast.makeText(this, it.message ?: "No hardware encoder", Toast.LENGTH_LONG).show()
            refreshEncoderPreview()
            return
        }
        Toast.makeText(this, "Using ${encoder.codecName}", Toast.LENGTH_SHORT).show()
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    private fun applyPairingPinFromInput() {
        val pin = pairingPinInput.text.toString().trim()
        if (!ProjectionStreamService.setPendingPairingPin(pin)) {
            Toast.makeText(this, "Enter the 4-digit PIN shown by Artemis/Moonlight", Toast.LENGTH_LONG).show()
            return
        }
        if (ProjectionStreamService.currentState == ServerState.STREAMING ||
            ProjectionStreamService.currentState == ServerState.READY ||
            ProjectionStreamService.currentState == ServerState.WAITING_FOR_PROJECTION
        ) {
            startService(ProjectionStreamService.setPinIntent(this, pin))
        }
        Toast.makeText(this, "Pairing PIN active", Toast.LENGTH_SHORT).show()
        connectionText.text = connectionSummary()
    }

    private fun syncPairingPinInput() {
        if (!::pairingPinInput.isInitialized || pairingPinInput.hasFocus()) return
        val currentText = pairingPinInput.text.toString()
        val pin = PairingPin.normalize(ProjectionStreamService.currentPin)
        if (pin == null) {
            if (currentText == "----") {
                pairingPinInput.setText("")
            }
            return
        }
        if (pin != currentText) {
            pairingPinInput.setText(pin)
        }
    }

    private fun selectedConfig(): StreamConfig {
        val resolution = ResolutionPreset.DEFAULTS[resolutionSpinner.selectedItemPosition]
        val fps = fpsSpinner.selectedItem.toString().toInt()
        val bitrateMbps = bitrateInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 16
        return StreamConfig(
            codecPreference = CodecPreference.entries[codecSpinner.selectedItemPosition],
            width = resolution.width,
            height = resolution.height,
            fps = fps,
            bitrate = bitrateMbps * 1_000_000,
            audioEnabled = audioSwitch.isChecked,
        )
    }

    private fun refreshEncoderPreview() {
        if (!::encoderText.isInitialized || !::codecSpinner.isInitialized) return
        val config = selectedConfig()
        val result = runCatching {
            EncoderSelector.select(
                config.codecPreference,
                config.width,
                config.height,
                config.fps,
                config.bitrate,
            )
        }
        encoderText.text = result.fold(
            onSuccess = {
                "Hardware encoder: ${it.codecName}\nVendor: ${it.vendor}, CBR=${it.cbrSupported}, lowLatency=${it.lowLatencyFeature}"
            },
            onFailure = {
                "No compatible hardware encoder for ${config.codecPreference.name} ${config.resolutionLabel} ${config.fps}fps at ${config.bitrate / 1_000_000} Mbps.\n${it.message}"
            },
        )
    }

    private fun connectionSummary(): String {
        val ips = localIpv4Addresses().ifEmpty { listOf("IP unavailable") }
        val activePin = PairingPin.normalize(ProjectionStreamService.currentPin) ?: "not set"
        return "Pairing PIN: $activePin\n" +
            "Protocol ${GameStreamProtocol.APP_VERSION} legacy TCP control\n" +
            "HTTP ${Ports.HTTP}  HTTPS ${Ports.HTTPS}  RTSP ${Ports.RTSP}  Video UDP ${Ports.VIDEO}\n" +
            "Control ${Ports.LEGACY_CONTROL}  Input ${Ports.LEGACY_INPUT}  Audio UDP ${Ports.AUDIO}\n" +
            "Host IP: ${ips.joinToString()}"
    }

    private fun localIpv4Addresses(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .map { it.hostAddress.orEmpty() }
                .filter { address -> address.count { it == '.' } == 3 && !address.startsWith("127.") }
                .toList()
        }.getOrDefault(emptyList())

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        if (permissions.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(permissions, REQUEST_RUNTIME_PERMISSIONS)
        }
    }

    private fun styleSystemBars(window: Window) {
        window.statusBarColor = COLOR_BLACK
        window.navigationBarColor = COLOR_BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = 0
        }
    }

    private fun rounded(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun LinearLayout.LayoutParams.withBottom(margin: Int): LinearLayout.LayoutParams =
        apply { bottomMargin = margin }

    private fun LinearLayout.LayoutParams.withTop(margin: Int): LinearLayout.LayoutParams =
        apply { topMargin = margin }

    private enum class ButtonTone(
        val background: Int,
        val stroke: Int,
        val textColor: Int,
    ) {
        PRIMARY(COLOR_ACCENT, COLOR_ACCENT, COLOR_BLACK),
        ACCENT(COLOR_LIME_DIM, COLOR_LIME, COLOR_TEXT),
        STREAMING(COLOR_LIME_DIM, COLOR_LIME, COLOR_LIME),
        SECONDARY(COLOR_FIELD, COLOR_STROKE, COLOR_TEXT),
        WARNING(COLOR_WARN_DIM, COLOR_WARN, COLOR_WARN),
        DANGER(COLOR_DANGER_DIM, COLOR_DANGER, COLOR_TEXT),
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_RUNTIME_PERMISSIONS = 1002

        private const val COLOR_BLACK = 0xFF000000.toInt()
        private const val COLOR_PANEL = 0xFF070B10.toInt()
        private const val COLOR_PANEL_ALT = 0xFF0C121A.toInt()
        private const val COLOR_FIELD = 0xFF0B1118.toInt()
        private const val COLOR_STROKE = 0xFF203140.toInt()
        private const val COLOR_TEXT = 0xFFEAF2FF.toInt()
        private const val COLOR_MUTED = 0xFF8B9AAA.toInt()
        private const val COLOR_ACCENT = 0xFF00D5FF.toInt()
        private const val COLOR_ACCENT_DIM = 0x3325D8FF
        private const val COLOR_LIME = 0xFF74FF6A.toInt()
        private const val COLOR_LIME_DIM = 0x192EFF68
        private const val COLOR_WARN = 0xFFFFC857.toInt()
        private const val COLOR_WARN_DIM = 0x22FFC857
        private const val COLOR_DANGER = 0xFFFF4D6D.toInt()
        private const val COLOR_DANGER_DIM = 0x22FF4D6D
    }
}
