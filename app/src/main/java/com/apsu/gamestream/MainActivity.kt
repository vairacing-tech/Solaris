package com.apsu.gamestream

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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
import com.apsu.gamestream.model.StreamConfig
import com.apsu.gamestream.nativebridge.NativeBridge
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
    private lateinit var audioSwitch: Switch

    private val statusPoll = object : Runnable {
        override fun run() {
            statusText.text = "${ProjectionStreamService.currentState}: ${ProjectionStreamService.lastMessage}"
            connectionText.text = connectionSummary()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
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
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Apsu GameStream"
            textSize = 26f
            setTextColor(0xFF101820.toInt())
        })
        root.addView(TextView(this).apply {
            text = runCatching { NativeBridge.version() }.getOrElse { "gamestream-native unavailable" }
            textSize = 13f
        })

        statusText = TextView(this).apply {
            text = "${ProjectionStreamService.currentState}: ${ProjectionStreamService.lastMessage}"
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }
        root.addView(statusText)

        connectionText = TextView(this).apply {
            text = connectionSummary()
            textSize = 14f
            setPadding(0, 0, 0, 18)
        }
        root.addView(connectionText)

        codecSpinner = spinner(CodecPreference.entries.map { it.name })
        codecSpinner.setSelection(CodecPreference.H264.ordinal)
        root.addView(labeled("Codec", codecSpinner))

        resolutionSpinner = spinner(ResolutionPreset.DEFAULTS.map { "${it.label} (${it.width}x${it.height})" })
        resolutionSpinner.setSelection(1)
        root.addView(labeled("Resolution", resolutionSpinner))

        fpsSpinner = spinner(listOf("30", "45", "60", "90", "120"))
        fpsSpinner.setSelection(2)
        root.addView(labeled("FPS", fpsSpinner))

        bitrateInput = EditText(this).apply {
            setText("16")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Mbps"
        }
        root.addView(labeled("Bitrate (Mbps)", bitrateInput))

        audioSwitch = Switch(this).apply {
            text = "Audio capture"
            isChecked = true
        }
        root.addView(audioSwitch)

        encoderText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        root.addView(encoderText)

        val previewButton = Button(this).apply {
            text = "Check hardware encoder"
            setOnClickListener { refreshEncoderPreview() }
        }
        root.addView(previewButton)

        val startButton = Button(this).apply {
            text = "Start server"
            setOnClickListener { requestProjectionAndStart() }
        }
        root.addView(startButton)

        val stopButton = Button(this).apply {
            text = "Stop server"
            setOnClickListener { startService(ProjectionStreamService.stopIntent(this@MainActivity)) }
        }
        root.addView(stopButton)

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshEncoderPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        codecSpinner.onItemSelectedListener = listener
        resolutionSpinner.onItemSelectedListener = listener
        fpsSpinner.onItemSelectedListener = listener

        return ScrollView(this).apply { addView(root) }
    }

    private fun labeled(label: String, child: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                gravity = Gravity.START
            })
            addView(child)
        }

    private fun spinner(items: List<String>): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                items,
            )
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
                "No compatible hardware encoder for ${config.codecPreference.name} ${config.resolutionLabel} ${config.fps}fps at ${config.bitrate / 1_000_000}Mbps.\n${it.message}"
            },
        )
    }

    private fun connectionSummary(): String {
        val ips = localIpv4Addresses().ifEmpty { listOf("IP unavailable") }
        return "PIN: ${ProjectionStreamService.currentPin}\n" +
            "Protocol ${GameStreamProtocol.APP_VERSION} legacy TCP control\n" +
            "HTTP ${Ports.HTTP}, HTTPS ${Ports.HTTPS}, RTSP ${Ports.RTSP}, Video UDP ${Ports.VIDEO}\n" +
            "Control TCP ${Ports.LEGACY_CONTROL}, Input TCP ${Ports.LEGACY_INPUT}, Audio UDP ${Ports.AUDIO}\n" +
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

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_RUNTIME_PERMISSIONS = 1002
    }
}
