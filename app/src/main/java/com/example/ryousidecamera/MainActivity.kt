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
            // Obtain MediaProjection in Activity immediately (Android 14+ requires this before service starts)
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            ScreenCaptureService.pendingProjection = mpManager.getMediaProjection(result.resultCode, result.data!!)
            ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureService::class.java))
            isRecording = true
            onRecordingStarted()
        }
    }

    private val screenDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val screenPath = intent.getStringExtra(ScreenCaptureService.EXTRA_SCREEN_FILE) ?: return
            val frontPath = intent.getStringExtra(ScreenCaptureService.EXTRA_FRONT_FILE) ?: return
            composeScreenVideo(File(screenPath), File(frontPath))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = DualCameraManager(this)

        if (PermissionHelper.allGranted(this)) {
            startCamera()
        } else {
            requestPermissions(PermissionHelper.requiredPermissions(), REQUEST_PERMISSIONS)
        }

        setupUI()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ScreenCaptureService.BROADCAST_DONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenDoneReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenDoneReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(screenDoneReceiver)
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
        cameraManager.stopRecording()
        isRecording = false
        timerHandler.removeCallbacks(timerRunnable)
        binding.timerText.visibility = View.GONE
        binding.modeToggle.visibility = View.VISIBLE
        updateCaptureButtonAppearance()
        updateModeUI()
        binding.captureButton.isEnabled = false
    }

    private fun checkAndCombineVideos(hasError: Boolean) {
        if (hasError) {
            runOnUiThread {
                binding.captureButton.isEnabled = true
                Toast.makeText(this, "録画エラーが発生しました", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val back = backTmpFile ?: return
        val front = frontTmpFile

        runOnUiThread { binding.processingOverlay.visibility = View.VISIBLE }

        lifecycleScope.launch {
            try {
                val frontValid = cameraManager.isFrontVideoEnabled &&
                    front != null && front.exists() && front.length() > 0

                val uri = if (frontValid) {
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
                runOnUiThread {
                    binding.processingOverlay.visibility = View.GONE
                    binding.captureButton.isEnabled = true
                }
            }
        }
    }

    private fun startScreenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("オーバーレイ権限が必要です")
                .setMessage("ゲームの上にカメラ映像を表示するために\n「他のアプリの上に重ねて表示」の権限が必要です。\n設定から許可してください。")
                .setPositiveButton("設定を開く") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }
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

    private fun composeScreenVideo(screenFile: File, frontFile: File) {
        runOnUiThread { binding.processingOverlay.visibility = View.VISIBLE }
        lifecycleScope.launch {
            try {
                if (frontFile.exists() && frontFile.length() > 0) {
                    VideoCompositor(this@MainActivity).compose(screenFile, frontFile) {}
                } else {
                    saveBackVideoOnly(screenFile)
                }
                Toast.makeText(this@MainActivity, R.string.video_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "動画処理エラー: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                screenFile.delete()
                frontFile.delete()
                runOnUiThread {
                    binding.processingOverlay.visibility = View.GONE
                    binding.captureButton.isEnabled = true
                }
            }
        }
    }

    private suspend fun saveBackVideoOnly(file: File): android.net.Uri {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                "RyousideCamera_${System.currentTimeMillis()}.mp4")
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/RyousideCamera")
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
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 10
    }
}
