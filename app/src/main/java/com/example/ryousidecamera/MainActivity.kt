package com.example.ryousidecamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ryousidecamera.camera.DualCameraManager
import com.example.ryousidecamera.databinding.ActivityMainBinding
import com.example.ryousidecamera.media.PhotoCombiner
import com.example.ryousidecamera.media.VideoCompositor
import com.example.ryousidecamera.service.ScreenCaptureService
import com.example.ryousidecamera.utils.PermissionHelper
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private enum class CaptureMode { PHOTO, VIDEO, SCREEN }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: DualCameraManager

    private var captureMode = CaptureMode.PHOTO
    private var isRecording = false

    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingStartMs = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordingStartMs) / 1000
            binding.timerText.text = "%02d:%02d".format(elapsed / 60, elapsed % 60)
            timerHandler.postDelayed(this, 500)
        }
    }

    private var backTmpFile: File? = null
    private var frontTmpFile: File? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Activity Result callbacks are exempt from Android 12+ background
            // foreground-service start restrictions. We start the service here (not
            // before the picker) so Android 15 recognises the service as having been
            // started in the same session as the user's consent.
            try {
                ContextCompat.startForegroundService(this,
                    Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_START
                        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                    }
                )
            } catch (e: Exception) {
                showErrorDialog("録画エラー", "${e.javaClass.name}\n\n${e.message ?: "(詳細なし)"}")
                return@registerForActivityResult
            }
            isRecording = true
            onRecordingStarted()
        }
        // User cancelled — nothing was started, nothing to clean up
    }

    private val screenDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            isRecording = false
            runOnUiThread {
                timerHandler.removeCallbacks(timerRunnable)
                binding.timerText.visibility = View.GONE
                binding.modeToggle.visibility = View.VISIBLE
                updateCaptureButtonAppearance()
                updateModeUI()
                binding.processingOverlay.visibility = View.GONE
                binding.captureButton.isEnabled = true
            }
            // Restart cameras so they're ready immediately when user switches to photo/video mode.
            // (Cameras were released when entering screen mode.)
            this@MainActivity.lifecycleScope.launch {
                cameraManager.startCamera(this@MainActivity, binding.previewBack, binding.previewFront)
                runOnUiThread {
                    updateModeUI()
                    if (!cameraManager.isConcurrentMode) binding.pipPlaceholder.visibility = View.VISIBLE
                }
            }
        }
    }

    private val screenErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val error = intent.getStringExtra(ScreenCaptureService.EXTRA_ERROR) ?: "不明なエラー"
            isRecording = false
            runOnUiThread {
                binding.timerText.visibility = View.GONE
                binding.modeToggle.visibility = View.VISIBLE
                updateCaptureButtonAppearance()
                updateModeUI()
                binding.captureButton.isEnabled = true
                showErrorDialog("録画エラー", error)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = DualCameraManager(this)

        val filter = IntentFilter(ScreenCaptureService.BROADCAST_DONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenDoneReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenDoneReceiver, filter)
        }
        val errorFilter = IntentFilter(ScreenCaptureService.BROADCAST_ERROR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenErrorReceiver, errorFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenErrorReceiver, errorFilter)
        }

        if (PermissionHelper.allGranted(this)) {
            startCamera()
        } else {
            requestPermissions(PermissionHelper.requiredPermissions(), REQUEST_PERMISSIONS)
        }

        setupUI()
    }

    override fun onResume() {
        super.onResume()
        // Restore video recording UI if the activity returns from background mid-recording.
        // The modeToggle listener can fire on resume and reset stopIcon/captureButton state.
        if (isRecording && captureMode == CaptureMode.VIDEO) {
            binding.photoBg.visibility = View.GONE
            binding.recordBg.visibility = View.VISIBLE
            binding.stopIcon.visibility = View.VISIBLE
            binding.modeToggle.visibility = View.GONE
            binding.timerText.visibility = View.VISIBLE
            binding.swapButton.visibility = View.GONE
            binding.screenModeHint.visibility = View.GONE
            binding.captureButton.isEnabled = true
            binding.processingOverlay.visibility = View.GONE
            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.post(timerRunnable)
            // Re-attach surface providers in case CameraX preview surface was lost in background
            cameraManager.reattachSurfaceProviders()
        }
        // If the activity was recreated while screen recording was running, restore UI
        if (ScreenCaptureService.isRunning && !isRecording) {
            isRecording = true
            captureMode = CaptureMode.SCREEN
            binding.modeToggle.check(R.id.screenModeButton)
            onRecordingStarted()
        }

        // Restart cameras when returning from background while NOT recording.
        // This covers cases where CameraX released cameras on onStop (stopped recording,
        // ended screen capture, etc.) and the preview would otherwise stay black.
        // Guard: skip if button is disabled (takePhoto or Finalize processing is in progress).
        if (!isRecording && captureMode != CaptureMode.SCREEN && binding.captureButton.isEnabled) {
            startCamera()
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        // CameraX finalizes recordings when the lifecycle owner is stopped, but Finalize may
        // arrive after onResume — leaving the timer running and preview black on return.
        // Explicitly request stop here so Finalize fires while we are still in background,
        // giving checkAndCombineVideos time to clean up before the user returns.
        if (isRecording && captureMode == CaptureMode.VIDEO) {
            stopVideoRecording()
        }
    }

    private fun setupUI() {
        binding.modeToggle.check(R.id.photoModeButton)
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val prevMode = captureMode
            captureMode = when (checkedId) {
                R.id.photoModeButton -> CaptureMode.PHOTO
                R.id.videoModeButton -> CaptureMode.VIDEO
                R.id.screenModeButton -> CaptureMode.SCREEN
                else -> CaptureMode.PHOTO
            }
            updateCaptureButtonAppearance()
            updateModeUI()

            if (!isRecording) {
                when {
                    captureMode == CaptureMode.SCREEN && prevMode != CaptureMode.SCREEN -> {
                        cameraManager.release()
                    }
                    prevMode == CaptureMode.SCREEN && captureMode != CaptureMode.SCREEN -> {
                        startCamera()
                    }
                    captureMode != CaptureMode.SCREEN -> {
                        val isPhoto = captureMode == CaptureMode.PHOTO
                        binding.captureButton.isEnabled = false
                        lifecycleScope.launch {
                            cameraManager.switchMode(this@MainActivity, isPhoto)
                            binding.captureButton.isEnabled = true
                            cameraManager.concurrentBindingError?.let { showErrorDialog("Concurrent失敗", it) }
                        }
                    }
                }
            }
        }

        binding.captureButton.setOnClickListener {
            when (captureMode) {
                CaptureMode.PHOTO -> takePhoto()
                CaptureMode.VIDEO -> if (isRecording) stopVideoRecording() else startVideoRecording()
                CaptureMode.SCREEN -> if (isRecording) stopScreenCapture() else startScreenCapture()
            }
        }

        binding.swapButton.setOnClickListener {
            cameraManager.swapPreviews()
        }
    }

    private fun updateModeUI() {
        binding.screenModeHint.visibility =
            if (captureMode == CaptureMode.SCREEN && !isRecording) View.VISIBLE else View.GONE
        binding.pipContainer.visibility =
            if (captureMode == CaptureMode.SCREEN) View.GONE else View.VISIBLE
        binding.swapButton.visibility =
            if (captureMode != CaptureMode.SCREEN && cameraManager.isConcurrentMode && !isRecording)
                View.VISIBLE else View.GONE
    }

    private fun updateCaptureButtonAppearance() {
        binding.photoBg.visibility = if (captureMode == CaptureMode.PHOTO) View.VISIBLE else View.GONE
        binding.recordBg.visibility = if (captureMode != CaptureMode.PHOTO) View.VISIBLE else View.GONE
        binding.stopIcon.visibility = View.GONE
    }

    private fun startCamera() {
        lifecycleScope.launch {
            cameraManager.startCamera(this@MainActivity, binding.previewBack, binding.previewFront)
            updateModeUI()
            if (!cameraManager.isConcurrentMode) {
                binding.pipPlaceholder.visibility = View.VISIBLE
                cameraManager.concurrentBindingError?.let { showErrorDialog("Concurrent失敗", it) }
            }
        }
    }

    private fun takePhoto() {
        binding.captureButton.isEnabled = false
        binding.processingText.setText(R.string.photo_processing)
        binding.processingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val (backBitmap, frontBitmap) = cameraManager.capturePhoto()
                val (mainBitmap, pipBitmap) = if (cameraManager.isSwapped && frontBitmap != null) {
                    Pair(frontBitmap, backBitmap)
                } else {
                    Pair(backBitmap, frontBitmap)
                }
                PhotoCombiner.combine(this@MainActivity, mainBitmap, pipBitmap)
                val msg = if (pipBitmap != null) "両カメラ合成完了" else "バックカメラのみ保存（フロント取得失敗）"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "撮影エラー: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.processingOverlay.visibility = View.GONE
                binding.captureButton.isEnabled = true
            }
        }
    }

    private fun startVideoRecording() {
        backTmpFile = File(cacheDir, "back_${System.currentTimeMillis()}.mp4")
        frontTmpFile = File(cacheDir, "front_${System.currentTimeMillis()}.mp4")

        cameraManager.startRecording(backTmpFile!!, frontTmpFile!!) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                    runOnUiThread { onRecordingStarted() }
                }
                is VideoRecordEvent.Finalize -> {
                    checkAndCombineVideos(event.hasError())
                }
                else -> {}
            }
        }
    }

    private fun onRecordingStarted() {
        binding.photoBg.visibility = View.GONE
        binding.recordBg.visibility = View.VISIBLE
        binding.stopIcon.visibility = View.VISIBLE
        binding.modeToggle.visibility = View.GONE
        binding.timerText.visibility = View.VISIBLE
        binding.swapButton.visibility = View.GONE
        binding.screenModeHint.visibility = View.GONE
        recordingStartMs = System.currentTimeMillis()
        timerHandler.post(timerRunnable)
    }

    private fun stopVideoRecording() {
        val wasActive = cameraManager.stopRecording()
        isRecording = false
        timerHandler.removeCallbacks(timerRunnable)
        binding.timerText.visibility = View.GONE
        binding.modeToggle.visibility = View.VISIBLE
        updateCaptureButtonAppearance()
        updateModeUI()
        // Only disable while waiting for Finalize; if recording already stopped,
        // Finalize won't fire again so re-enable immediately.
        binding.captureButton.isEnabled = !wasActive
    }

    private fun checkAndCombineVideos(hasError: Boolean) {
        // Always reset recording state — covers both explicit stop (already false)
        // and CameraX auto-stopping recording on lifecycle (isRecording still true).
        if (isRecording) {
            isRecording = false
            timerHandler.removeCallbacks(timerRunnable)
            runOnUiThread {
                binding.timerText.visibility = View.GONE
                binding.modeToggle.visibility = View.VISIBLE
                updateCaptureButtonAppearance()
                updateModeUI()
            }
        }

        if (hasError) {
            runOnUiThread {
                binding.captureButton.isEnabled = true
                Toast.makeText(this, "録画エラーが発生しました", Toast.LENGTH_SHORT).show()
            }
            lifecycleScope.launch {
                cameraManager.startCamera(this@MainActivity, binding.previewBack, binding.previewFront)
                runOnUiThread {
                    updateModeUI()
                    if (!cameraManager.isConcurrentMode) binding.pipPlaceholder.visibility = View.VISIBLE
                }
            }
            return
        }

        val back = backTmpFile ?: run {
            runOnUiThread { binding.captureButton.isEnabled = true }
            return
        }
        val front = frontTmpFile

        lifecycleScope.launch {
            // Rebind cameras first so preview is live while composition runs in background
            cameraManager.startCamera(this@MainActivity, binding.previewBack, binding.previewFront)
            runOnUiThread {
                updateModeUI()
                if (!cameraManager.isConcurrentMode) binding.pipPlaceholder.visibility = View.VISIBLE
                binding.captureButton.isEnabled = true
                // Show "動画を結合中…" overlay — clickable=false so user can still tap capture
                binding.processingText.setText(R.string.processing)
                binding.processingOverlay.visibility = View.VISIBLE
            }

            try {
                val frontValid = cameraManager.isFrontVideoEnabled &&
                    front != null && front.exists() && front.length() > 0

                if (frontValid) {
                    val (mainFile, pipFile) = if (cameraManager.isSwapped) {
                        Pair(front!!, back)
                    } else {
                        Pair(back, front!!)
                    }
                    VideoCompositor(this@MainActivity).compose(mainFile, pipFile) {}
                } else {
                    saveBackVideoOnly(back)
                }
                Toast.makeText(this@MainActivity, R.string.video_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "動画処理エラー: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                back.delete()
                front?.delete()
                runOnUiThread { binding.processingOverlay.visibility = View.GONE }
            }
        }
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> launchScreenCaptureWithOverlayCheck() }

    private fun startScreenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionHelper.hasNotificationPermission(this)) {
            notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchScreenCaptureWithOverlayCheck()
    }

    private fun launchScreenCaptureWithOverlayCheck() {
        if (!Settings.canDrawOverlays(this)) {
            // Warn but do NOT block — recording works via notification stop button
            AlertDialog.Builder(this)
                .setTitle("オーバーレイ権限なし")
                .setMessage(
                    "「他のアプリの上に重ねて表示」権限がないため、ゲーム中にカメラ映像と停止ボタンが表示されません。\n\n" +
                    "録画は通常通り行われます。停止するには通知バーの「停止」ボタンを使ってください。\n\n" +
                    "今すぐ権限を設定しますか？"
                )
                .setPositiveButton("設定する") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("このまま録画") { _, _ ->
                    launchScreenCapture()
                }
                .show()
            return
        }
        launchScreenCapture()
    }

    private fun launchScreenCapture() {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun stopScreenCapture() {
        startService(Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        })
        isRecording = false
        timerHandler.removeCallbacks(timerRunnable)
        binding.timerText.visibility = View.GONE
        binding.modeToggle.visibility = View.VISIBLE
        updateCaptureButtonAppearance()
        updateModeUI()
        binding.captureButton.isEnabled = false
    }

    private suspend fun saveBackVideoOnly(file: File): android.net.Uri {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                "RyousideCamera_${System.currentTimeMillis()}.mp4")
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            values.clear()
            values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun showErrorDialog(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            val tv = TextView(this).apply {
                text = message
                setPadding(48, 32, 48, 16)
                setTextIsSelectable(true)
            }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(ScrollView(this).apply { addView(tv) })
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        cameraManager.release()
        unregisterReceiver(screenDoneReceiver)
        unregisterReceiver(screenErrorReceiver)
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 10
    }
}
