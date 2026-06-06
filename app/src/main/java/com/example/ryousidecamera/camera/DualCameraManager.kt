package com.example.ryousidecamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.SingleCameraConfig
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DualCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "DualCameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null

    // Use cases – back camera
    private var backPreview: Preview? = null
    private var backImageCapture: ImageCapture? = null
    private var backVideoCapture: VideoCapture<Recorder>? = null

    // Use cases – front camera
    private var frontPreview: Preview? = null
    private var frontImageCapture: ImageCapture? = null
    private var frontVideoCapture: VideoCapture<Recorder>? = null

    var isConcurrentMode = false
        private set
    var isFrontVideoEnabled = false
        private set

    private var backRecording: Recording? = null
    private var frontRecording: Recording? = null

    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        backPreviewView: PreviewView,
        frontPreviewView: PreviewView
    ) {
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            ProcessCameraProvider.getInstance(context).also { future ->
                future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
            }
        }
        cameraProvider = provider
        provider.unbindAll()

        backPreview = Preview.Builder().build().also { it.setSurfaceProvider(backPreviewView.surfaceProvider) }
        frontPreview = Preview.Builder().build().also { it.setSurfaceProvider(frontPreviewView.surfaceProvider) }

        backImageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        frontImageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        backVideoCapture = VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
        )
        frontVideoCapture = VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
        )

        bindCameras(provider, lifecycleOwner)
    }

    private fun bindCameras(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        // Try concurrent camera binding (front + back simultaneously)
        val concurrentInfoSets = provider.availableConcurrentCameraInfos
        val supportsConcurrent = concurrentInfoSets.any { infoSet ->
            infoSet.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
            infoSet.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
        }

        if (supportsConcurrent) {
            tryConcurrentBinding(provider, lifecycleOwner)
        } else {
            fallbackBinding(provider, lifecycleOwner)
        }
    }

    private fun tryConcurrentBinding(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        try {
            val backGroup = UseCaseGroup.Builder()
                .addUseCase(backPreview!!)
                .addUseCase(backImageCapture!!)
                .addUseCase(backVideoCapture!!)
                .build()
            val frontGroup = UseCaseGroup.Builder()
                .addUseCase(frontPreview!!)
                .addUseCase(frontImageCapture!!)
                .addUseCase(frontVideoCapture!!)
                .build()

            val backConfig = SingleCameraConfig(
                CameraSelector.DEFAULT_BACK_CAMERA, backGroup, lifecycleOwner
            )
            val frontConfig = SingleCameraConfig(
                CameraSelector.DEFAULT_FRONT_CAMERA, frontGroup, lifecycleOwner
            )

            provider.bindToLifecycle(listOf(backConfig, frontConfig))
            isConcurrentMode = true
            isFrontVideoEnabled = true
            Log.d(TAG, "Concurrent camera binding succeeded (video on both)")
        } catch (e: Exception) {
            Log.w(TAG, "Full concurrent binding failed, retrying without front video: $e")
            tryConcurrentNoFrontVideo(provider, lifecycleOwner)
        }
    }

    private fun tryConcurrentNoFrontVideo(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        try {
            val backGroup = UseCaseGroup.Builder()
                .addUseCase(backPreview!!)
                .addUseCase(backImageCapture!!)
                .addUseCase(backVideoCapture!!)
                .build()
            val frontGroup = UseCaseGroup.Builder()
                .addUseCase(frontPreview!!)
                .addUseCase(frontImageCapture!!)
                .build()

            val backConfig = SingleCameraConfig(
                CameraSelector.DEFAULT_BACK_CAMERA, backGroup, lifecycleOwner
            )
            val frontConfig = SingleCameraConfig(
                CameraSelector.DEFAULT_FRONT_CAMERA, frontGroup, lifecycleOwner
            )

            provider.bindToLifecycle(listOf(backConfig, frontConfig))
            isConcurrentMode = true
            isFrontVideoEnabled = false
            Log.d(TAG, "Concurrent binding without front video succeeded")
        } catch (e: Exception) {
            Log.w(TAG, "Concurrent binding failed entirely, falling back to back camera only: $e")
            fallbackBinding(provider, lifecycleOwner)
        }
    }

    private fun fallbackBinding(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                backPreview, backImageCapture, backVideoCapture
            )
            isConcurrentMode = false
            isFrontVideoEnabled = false
            Log.d(TAG, "Fallback to back camera only")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed: $e")
        }
    }

    suspend fun capturePhoto(): Pair<Bitmap, Bitmap?> {
        val executor = ContextCompat.getMainExecutor(context)
        val backBitmap = captureImage(backImageCapture!!, executor, mirrorHorizontal = false)
        val frontBitmap = if (isConcurrentMode) {
            runCatching { captureImage(frontImageCapture!!, executor, mirrorHorizontal = true) }.getOrNull()
        } else null
        return Pair(backBitmap, frontBitmap)
    }

    private suspend fun captureImage(
        imageCapture: ImageCapture,
        executor: Executor,
        mirrorHorizontal: Boolean
    ): Bitmap = suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.toBitmap().let { bmp ->
                    if (mirrorHorizontal) {
                        val matrix = Matrix().apply { postScale(-1f, 1f, bmp.width / 2f, bmp.height / 2f) }
                        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true).also { bmp.recycle() }
                    } else bmp
                }
                image.close()
                cont.resume(bitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    fun startRecording(
        backFile: File,
        frontFile: File,
        onEvent: (VideoRecordEvent) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(context)

        backRecording = backVideoCapture!!.output
            .prepareRecording(context, FileOutputOptions.Builder(backFile).build())
            .withAudioEnabled()
            .start(executor) { event -> onEvent(event) }

        if (isFrontVideoEnabled) {
            frontRecording = frontVideoCapture!!.output
                .prepareRecording(context, FileOutputOptions.Builder(frontFile).build())
                .start(executor) { /* secondary recording events */ }
        }
    }

    fun stopRecording() {
        backRecording?.stop()
        frontRecording?.stop()
        backRecording = null
        frontRecording = null
    }

    fun release() {
        cameraProvider?.unbindAll()
    }
}
