package com.example.ryousidecamera.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.ryousidecamera.R
import com.example.ryousidecamera.media.VideoCompositor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ScreenCaptureService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val BROADCAST_DONE = "com.example.ryousidecamera.SCREEN_DONE"
        private const val NOTIF_ID = 2001
        private const val NOTIF_ID_DONE = 2002
        private const val CHANNEL_ID = "screen_capture_ch"
        var pendingProjection: MediaProjection? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenRecorder: MediaRecorder? = null
    private var cameraRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    private var screenFile: File? = null
    private var frontFile: File? = null
    private var stopped = false

    // Independent scope — not cancelled when lifecycle ends, so composition survives
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(NOTIF_ID, buildNotification("スクリーン録画中"), type)
        } else {
            startForeground(NOTIF_ID, buildNotification("スクリーン録画中"))
        }

        mediaProjection = pendingProjection ?: run {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (resultCode != -1 && resultData != null) {
                (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                    .getMediaProjection(resultCode, resultData)
            } else null
        }
        pendingProjection = null

        if (mediaProjection == null) {
            android.util.Log.e("ScreenCaptureService", "MediaProjection is null, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        screenFile = File(cacheDir, "screen_${System.currentTimeMillis()}.mp4")
        frontFile = File(cacheDir, "front_${System.currentTimeMillis()}.mp4")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture() }
            }, Handler(Looper.getMainLooper()))
        }

        try {
            showFloatingOverlay()
        } catch (e: Exception) {
            android.util.Log.e("ScreenCaptureService", "showFloatingOverlay failed: $e")
        }
        try {
            startScreenRecording()
        } catch (e: Exception) {
            android.util.Log.e("ScreenCaptureService", "Screen recording failed: $e")
        }
        startFrontCameraRecording()

        return START_NOT_STICKY
    }

    private fun startScreenRecording() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        screenRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        screenRecorder!!.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(screenFile!!.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(5_000_000)
            prepare()
        }

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            screenRecorder!!.surface,
            null, null
        )

        screenRecorder!!.start()
    }

    private fun showFloatingOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_camera, null)

        floatingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        floatingView!!.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0; private var initY = 0
            private var initTouchX = 0f; private var initTouchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = floatingParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x; initY = params.y
                        initTouchX = event.rawX; initTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initX + (initTouchX - event.rawX).toInt()
                        params.y = initY + (event.rawY - initTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        floatingView!!.findViewById<View>(R.id.floatingStop).setOnClickListener {
            stopCapture()
        }

        windowManager!!.addView(floatingView, floatingParams)
    }

    private fun startFrontCameraRecording() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)
                val preview = Preview.Builder().build()

                cameraProvider!!.unbindAll()
                cameraProvider!!.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    videoCapture
                )

                floatingView?.let { v ->
                    val pv = v.findViewById<PreviewView>(R.id.floatingPreview)
                    preview.setSurfaceProvider(pv.surfaceProvider)
                }

                cameraRecording = videoCapture.output
                    .prepareRecording(this, FileOutputOptions.Builder(frontFile!!).build())
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this)) { /* events */ }
            } catch (e: Exception) {
                android.util.Log.e("ScreenCaptureService", "Front camera error: $e")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun stopCapture() {
        if (stopped) return
        stopped = true

        cameraRecording?.stop()
        cameraRecording = null

        try { screenRecorder?.stop() } catch (_: Exception) {}
        screenRecorder?.release()
        screenRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        cameraProvider?.unbindAll()
        cameraProvider = null

        try {
            floatingView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        floatingView = null

        val sf = screenFile
        val ff = frontFile
        screenFile = null
        frontFile = null

        if (sf == null) {
            doStopForeground()
            stopSelf()
            return
        }

        // Update notification while compositing — keep service in foreground
        updateNotificationText("動画を合成中…")

        // Composition runs in serviceScope (independent of lifecycle) so it survives
        // even if onDestroy fires before composition finishes
        serviceScope.launch {
            try {
                if (ff != null && ff.exists() && ff.length() > 0) {
                    VideoCompositor(this@ScreenCaptureService).compose(sf, ff) {}
                } else {
                    withContext(Dispatchers.IO) { saveVideoOnly(sf) }
                }
                showDoneNotification()
            } catch (e: Exception) {
                android.util.Log.e("ScreenCaptureService", "Composition failed: $e")
            } finally {
                sf.delete()
                ff?.delete()
                // Notify MainActivity to reset UI (best-effort; activity may be gone)
                sendBroadcast(Intent(BROADCAST_DONE))
                doStopForeground()
                stopSelf()
            }
        }
    }

    private fun doStopForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun updateNotificationText(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun showDoneNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("録画完了")
            .setContentText("動画をフォトに保存しました")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_DONE, notif)
    }

    private suspend fun saveVideoOnly(file: File): android.net.Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "RyousideCamera_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RyousideCamera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("RyousideCamera")
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .addAction(
            0, "停止", PendingIntent.getService(
                this, 0,
                Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "スクリーン録画",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!stopped) {
            // System killed the service before explicit stop — minimal cleanup only
            stopped = true
            cameraRecording?.stop()
            try { screenRecorder?.stop() } catch (_: Exception) {}
            screenRecorder?.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
            cameraProvider?.unbindAll()
            try { floatingView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            screenFile?.delete()
            frontFile?.delete()
            serviceScope.cancel()
        }
    }
}
