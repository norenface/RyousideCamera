package com.example.ryousidecamera

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.lifecycleScope
import com.example.ryousidecamera.camera.DualCameraManager
import com.example.ryousidecamera.databinding.ActivityMainBinding
import com.example.ryousidecamera.media.PhotoCombiner
import com.example.ryousidecamera.media.VideoCompositor
import com.example.ryousidecamera.utils.PermissionHelper
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: DualCameraManager

    private var isPhotoMode = true
    private var isRecording = false

    // Timer
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingStartMs = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordingStartMs) / 1000
            binding.timerText.text = "%02d:%02d".format(elapsed / 60, elapsed % 60)
            timerHandler.postDelayed(this, 500)
        }
    }

    // Temp recording files
    private var backTmpFile: File? = null
    private var frontTmpFile: File? = null
    private var backRecordingFinished = false
    private var frontRecordingFinished = false

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

    private fun setupUI() {
        // Mode toggle: default Photo selected
        binding.modeToggle.check(R.id.photoModeButton)
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isPhotoMode = checkedId == R.id.photoModeButton
                updateCaptureButtonAppearance()
            }
        }

        binding.captureButton.setOnClickListener {
            if (isPhotoMode) {
                takePhoto()
            } else {
                if (isRecording) stopVideoRecording() else startVideoRecording()
            }
        }
    }

    private fun updateCaptureButtonAppearance() {
        binding.photoBg.visibility = if (isPhotoMode) View.VISIBLE else View.GONE
        binding.recordBg.visibility = if (isPhotoMode) View.GONE else View.VISIBLE
        binding.stopIcon.visibility = View.GONE
    }

    private fun startCamera() {
        lifecycleScope.launch {
            cameraManager.startCamera(this@MainActivity, binding.previewBack, binding.previewFront)
            if (!cameraManager.isConcurrentMode) {
                // Hide live PiP preview when simultaneous streaming isn't supported,
                // but still allow sequential front+back photo capture if front exists.
                binding.pipContainer.visibility = View.GONE
            }
        }
    }

    private fun takePhoto() {
        binding.captureButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val (backBitmap, frontBitmap) = cameraManager.capturePhoto()
                if (frontBitmap != null) {
                    PhotoCombiner.combine(this@MainActivity, backBitmap, frontBitmap)
                } else {
                    PhotoCombiner.combine(this@MainActivity, backBitmap, backBitmap.also { backBitmap })
                }
                Toast.makeText(this@MainActivity, R.string.photo_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "撮影エラー: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.captureButton.isEnabled = true
            }
        }
    }

    private fun startVideoRecording() {
        backTmpFile = File(cacheDir, "back_${System.currentTimeMillis()}.mp4")
        frontTmpFile = File(cacheDir, "front_${System.currentTimeMillis()}.mp4")
        backRecordingFinished = false
        frontRecordingFinished = !cameraManager.isFrontVideoEnabled // If no front video, mark as done

        cameraManager.startRecording(backTmpFile!!, frontTmpFile!!) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                    runOnUiThread { onRecordingStarted() }
                }
                is VideoRecordEvent.Finalize -> {
                    backRecordingFinished = true
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
                val uri = if (cameraManager.isFrontVideoEnabled && front != null && front.exists() && front.length() > 0) {
                    VideoCompositor(this@MainActivity).compose(back, front) { progress ->
                        // Could update a progress bar here
                    }
                } else {
                    // No front video: save back video directly to MediaStore
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
