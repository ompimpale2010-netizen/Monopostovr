package com.example.monopostovrmirror

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var settings: VrSettings
    private lateinit var statusText: TextView

    // ---- slider value ranges. Kept in one place so UI and math never disagree. ----
    private val zoomMin = 0.5f
    private val zoomMax = 2.0f
    private val eyeSepMax = 0.06f
    private val posRange = 0.3f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = VrSettings(this)
        statusText = findViewById(R.id.statusText)

        setupSliders()
        setupButtons()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        statusText.text = if (hasOverlay) {
            "Overlay permission: GRANTED. Ready to start VR."
        } else {
            "Overlay permission: NOT granted. Tap the button below first."
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, Constants.REQ_OVERLAY_PERMISSION)
            } else {
                Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnStartVr).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            requestMediaProjection()
        }

        findViewById<Button>(R.id.btnStopVr).setOnClickListener {
            val stopIntent = Intent(this, CaptureService::class.java).apply {
                action = Constants.ACTION_STOP
            }
            startService(stopIntent)
        }
    }

    private fun requestMediaProjection() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mgr.createScreenCaptureIntent(), Constants.REQ_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            Constants.REQ_OVERLAY_PERMISSION -> refreshStatus()

            Constants.REQ_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    startCaptureService(resultCode, data)
                    launchMonoposto()
                } else {
                    Toast.makeText(this, "Screen-capture permission denied. Cannot start VR.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = Constants.ACTION_START
            putExtra(Constants.EXTRA_RESULT_CODE, resultCode)
            putExtra(Constants.EXTRA_RESULT_DATA, data)
        }
        // Foreground service MUST be started before the projection token is used
        // inside it (Android 10+ requirement for MediaProjection).
        startForegroundService(serviceIntent)
    }

    private fun launchMonoposto() {
        val pm = packageManager
        val launchIntent = try {
            pm.getLaunchIntentForPackage(Constants.TARGET_PACKAGE)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(
                this,
                "Monoposto (${Constants.TARGET_PACKAGE}) not found on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
        // Overlay + capture keep running via the foreground service even
        // though this Activity can now be left/finished.
    }

    private fun pushSettingsToService() {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = Constants.ACTION_UPDATE_SETTINGS
        }
        // Service reads current values straight from SharedPreferences (VrSettings),
        // so we just need to notify it something changed.
        startService(intent)
    }

    private fun setupSliders() {
        val seekEyeSep = findViewById<SeekBar>(R.id.seekEyeSep)
        val seekZoom = findViewById<SeekBar>(R.id.seekZoom)
        val seekHPos = findViewById<SeekBar>(R.id.seekHPos)
        val seekVPos = findViewById<SeekBar>(R.id.seekVPos)
        val checkBarrel = findViewById<CheckBox>(R.id.checkBarrel)

        // Initialize from stored settings (inverse of the mapping used on change).
        seekEyeSep.progress = ((settings.eyeSeparation / eyeSepMax) * 100).toInt().coerceIn(0, 100)
        seekZoom.progress = (((settings.zoom - zoomMin) / (zoomMax - zoomMin)) * 100).toInt().coerceIn(0, 100)
        seekHPos.progress = (((settings.horizontalPos / posRange) * 50) + 50).toInt().coerceIn(0, 100)
        seekVPos.progress = (((settings.verticalPos / posRange) * 50) + 50).toInt().coerceIn(0, 100)
        checkBarrel.isChecked = settings.barrelDistortion

        seekEyeSep.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            settings.eyeSeparation = (progress / 100f) * eyeSepMax
            pushSettingsToService()
        })
        seekZoom.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            settings.zoom = zoomMin + (progress / 100f) * (zoomMax - zoomMin)
            pushSettingsToService()
        })
        seekHPos.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            settings.horizontalPos = ((progress - 50) / 50f) * posRange
            pushSettingsToService()
        })
        seekVPos.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            settings.verticalPos = ((progress - 50) / 50f) * posRange
            pushSettingsToService()
        })
        checkBarrel.setOnCheckedChangeListener { _, isChecked ->
            settings.barrelDistortion = isChecked
            pushSettingsToService()
        }
    }

    private fun simpleSeekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
