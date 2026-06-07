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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DualCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "DualCameraManager"
        private const val SINGLE_CAMERA_CONFIG_CLASS = "androidx.camera.lifecycle.SingleCameraConfig"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundLifecycleOwner: LifecycleOwner? = null

    // Use cases – back camera
    private var backPreview: Preview? = null
    private var backPreviewView: PreviewView? = null
    private var backImageCapture: ImageCapture? = null
    private var backVideoCapture: VideoCapture<Recorder>? = null

    // Use cases – front camera
    private var frontPreview: Preview? = null
    private var frontPreviewView: PreviewView? = null
    private var frontImageCapture: ImageCapture? = null
    private var frontVideoCapture: VideoCapture<Recorder>? = null

    var isConcurrentMode = false
        private set
    var isFrontVideoEnabled = false
        private set
    var hasFrontCamera = false
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
        boundLifecycleOwner = lifecycleOwner
        this.backPreviewView = backPreviewView
        this.frontPreviewView = frontPreviewView
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
        tryConcurrentBinding(provider, lifecycleOwner)
    }

    // SingleCameraConfig is @RestrictTo(LIBRARY_GROUP) in camera-lifecycle, so we use
    // reflection to access it at runtime while avoiding a compile-time dependency.
    private fun makeSingleCameraConfig(
        cameraSelector: CameraSelector,
        useCaseGroup: UseCaseGroup,
        lifecycleOwner: LifecycleOwner
    ): Any {
        val clazz = Class.forName(SINGLE_CAMERA_CONFIG_CLASS)
        val ctor = clazz.getConstructor(
            CameraSelector::class.java,
            UseCaseGroup::class.java,
            LifecycleOwner::class.java
        )
        ctor.isAccessible = true
        return ctor.newInstance(cameraSelector, useCaseGroup, lifecycleOwner)
    }

    private fun bindConcurrent(provider: ProcessCameraProvider, configs: List<Any>) {
        // Search both the runtime class and ProcessCameraProvider's static hierarchy
        // to reliably find the overloaded bindToLifecycle(List) method.
        val method = (sequenceOf(provider.javaClass) +
                generateSequence<Class<*>>(ProcessCameraProvider::class.java) { it.superclass })
            .distinct()
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { m ->
                m.name == "bindToLifecycle" &&
                m.parameterCount == 1 &&
                m.parameterTypes[0] == List::class.java
            }
            ?: throw NoSuchMethodException("bindToLifecycle(List) not found on ${provider.javaClass.name}")
        method.isAccessible = true
        method.invoke(provider, configs)
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

            val backConfig = makeSingleCameraConfig(CameraSelector.DEFAULT_BACK_CAMERA, backGroup, lifecycleOwner)
            val frontConfig = makeSingleCameraConfig(CameraSelector.DEFAULT_FRONT_CAMERA, frontGroup, lifecycleOwner)

            bindConcurrent(provider, listOf(backConfig, frontConfig))
            isConcurrentMode = true
            isFrontVideoEnabled = true
            hasFrontCamera = true
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

            val backConfig = makeSingleCameraConfig(CameraSelector.DEFAULT_BACK_CAMERA, backGroup, lifecycleOwner)
            val frontConfig = makeSingleCameraConfig(CameraSelector.DEFAULT_FRONT_CAMERA, frontGroup, lifecycleOwner)

            bindConcurrent(provider, listOf(backConfig, frontConfig))
            isConcurrentMode = true
            isFrontVideoEnabled = false
            hasFrontCamera = true
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
            hasFrontCamera = try {
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            } catch (e: Exception) { false }
            Log.d(TAG, "Fallback to back camera only (hasFrontCamera=$hasFrontCamera)")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed: $e")
        }
    }

    suspend fun capturePhoto(): Pair<Bitmap, Bitmap?> {
        val executor = ContextCompat.getMainExecutor(context)
        return if (isConcurrentMode) {
            val backBitmap = captureImage(backImageCapture!!, executor, mirrorHorizontal = false)
            val frontBitmap = runCatching {
                captureImage(frontImageCapture!!, executor, mirrorHorizontal = true)
            }.getOrNull()
            Pair(backBitmap, frontBitmap)
        } else if (hasFrontCamera) {
            capturePhotoSequential(executor)
        } else {
            val backBitmap = captureImage(backImageCapture!!, executor, mirrorHorizontal = false)
            Pair(backBitmap, null)
        }
    }

    // On devices without concurrent camera support, capture back then rebind to front to
    // capture front, then rebind back. Sequential, not simultaneous.
    private suspend fun capturePhotoSequential(executor: Executor): Pair<Bitmap, Bitmap?> {
        val provider = cameraProvider ?: return Pair(
            captureImage(backImageCapture!!, executor, mirrorHorizontal = false), null
        )
        val lifecycleOwner = boundLifecycleOwner ?: return Pair(
            captureImage(backImageCapture!!, executor, mirrorHorizontal = false), null
        )

        // Step 1: capture back
        val backBitmap = captureImage(backImageCapture!!, executor, mirrorHorizontal = false)

        // Step 2: switch to front camera (bind with Preview so the sensor warms up)
        val frontCap = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val frontPrev = Preview.Builder().build().also {
            it.setSurfaceProvider(frontPreviewView?.surfaceProvider)
        }
        provider.unbindAll()
        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                frontPrev, frontCap
            )
            // Wait for the front camera sensor to open before taking the picture
            delay(600)
            val frontBitmap = runCatching {
                captureImage(frontCap, executor, mirrorHorizontal = true)
            }.onFailure { Log.e(TAG, "Sequential front capture error: $it") }.getOrNull()

            // Step 3: restore back camera with all use cases
            provider.unbindAll()
            backPreview = Preview.Builder().build().also {
                it.setSurfaceProvider(backPreviewView?.surfaceProvider)
            }
            backImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                backPreview, backImageCapture, backVideoCapture
            )
            return Pair(backBitmap, frontBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Sequential capture failed at front step: $e")
            // Restore back camera
            try {
                provider.unbindAll()
                backPreview = Preview.Builder().build().also {
                    it.setSurfaceProvider(backPreviewView?.surfaceProvider)
                }
                backImageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    backPreview, backImageCapture, backVideoCapture
                )
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to restore back camera: $ex")
            }
            return Pair(backBitmap, null)
        }
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
