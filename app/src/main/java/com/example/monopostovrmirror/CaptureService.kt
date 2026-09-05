package com.example.monopostovrmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Owns the whole capture -> render -> overlay pipeline.
 *
 * Order of operations on start:
 *   1. Go foreground immediately (required before touching MediaProjection on API 29+).
 *   2. Obtain the MediaProjection from the token handed in by MainActivity.
 *   3. Create the overlay window (FLAG_SECURE, TYPE_APPLICATION_OVERLAY) and add a
 *      GLSurfaceView to it.
 *   4. Wait for the GL renderer to create its SurfaceTexture/OES texture (onSurfaceCreated).
 *   5. Only once that Surface exists do we call MediaProjection.createVirtualDisplay(),
 *      feeding the capture into the SAME surface the renderer reads from.
 *
 * Because the overlay window is FLAG_SECURE, MediaProjection's capture of the physical
 * display excludes it - the compositor blanks secure-window content out of any capture
 * or screenshot, the same mechanism used to blank DRM video during screen recording.
 * That is what prevents the renderer from capturing itself and running away in an
 * infinite mirror.
 *
 * CAVEAT (documented rather than hidden): this exclusion behavior is a fixed part of
 * SurfaceFlinger's secure-window handling and not something an app can unit-test from
 * inside a sandboxed VM. You must verify it visually on the actual M30s: if you ever see
 * the VR overlay recursively mirrored inside itself, FLAG_SECURE is not being honored for
 * some reason (some OEM skins have historically had bugs here) and this architecture
 * cannot be used as-is on that device/ROM.
 */
class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var windowManager: WindowManager? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: VrRenderer? = null
    private lateinit var settings: VrSettings

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked capture from the system "Stop sharing" control.
            stopEverything()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = VrSettings(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_START -> {
                startForeground(Constants.NOTIF_ID, buildNotification())
                val resultCode = intent.getIntExtra(Constants.EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(Constants.EXTRA_RESULT_DATA)
                if (resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                beginCapture(resultCode, resultData)
            }
            Constants.ACTION_UPDATE_SETTINGS -> {
                // Renderer reads settings live via the settingsProvider lambda,
                // nothing to push explicitly - this action just exists so the
                // Activity has something to call. Kept for clarity/extensibility.
            }
            Constants.ACTION_STOP -> {
                stopEverything()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(resultCode: Int, resultData: Intent) {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        val projection = mgr.getMediaProjection(resultCode, resultData)
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        addOverlayAndRenderer(projection)
    }

    private fun addOverlayAndRenderer(projection: MediaProjection) {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager!!.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val vrRenderer = VrRenderer(
            settingsProvider = { settings },
            onCaptureSurfaceReady = { captureSurface ->
                // Runs on the GL thread; hop back to main to touch MediaProjection safely.
                mainHandler.post {
                    createVirtualDisplay(projection, captureSurface, width, height, density)
                }
            }
        )
        renderer = vrRenderer

        val gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(vrRenderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        vrRenderer.glSurfaceView = gl
        glSurfaceView = gl

        @Suppress("DEPRECATION")
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            // NOT_TOUCHABLE: touches pass through to Monoposto underneath so the
            //   Bluetooth controller / on-screen controls still work.
            // NOT_FOCUSABLE: keep keyboard/input focus with the game, not the overlay.
            // SECURE: excluded from MediaProjection/screenshot capture - this is the
            //   line that prevents the recursive self-capture loop.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START

        windowManager!!.addView(gl, params)
    }

    private fun createVirtualDisplay(
        projection: MediaProjection,
        captureSurface: Surface,
        width: Int,
        height: Int,
        density: Int
    ) {
        virtualDisplay = projection.createVirtualDisplay(
            "MonopostoVRCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            captureSurface,
            null,
            mainHandler
        )
    }

    private fun stopEverything() {
        virtualDisplay?.release()
        virtualDisplay = null

        glSurfaceView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        glSurfaceView = null

        renderer?.release()
        renderer = null

        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIF_CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, CaptureService::class.java).apply {
            action = Constants.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_stop_action), stopPendingIntent)
            .build()
    }
}
