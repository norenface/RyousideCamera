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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.lang.reflect.ParameterizedType
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DualCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "DualCameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundLifecycleOwner: LifecycleOwner? = null

    private var backPreview: Preview? = null
    private var backPreviewView: PreviewView? = null
    private var backImageCapture: ImageCapture? = null
    private var backVideoCapture: VideoCapture<Recorder>? = null

    private var frontPreview: Preview? = null
    private var frontPreviewView: PreviewView? = null
    private var frontImageCapture: ImageCapture? = null
    private var frontVideoCapture: VideoCapture<Recorder>? = null

    var isConcurrentMode = false
        private set
    var isFrontVideoEnabled = false
        private set
    var concurrentBindingError: String? = null
        private set

    // true = photo mode (Preview+ImageCapture), false = video mode (Preview+VideoCapture)
    private var isPhotoModeBinding = true

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
        recreateUseCases()
        bindCameras(provider, lifecycleOwner)
    }

    /** Rebind cameras for the given mode. Photo ↔ Video switch triggers this. */
    suspend fun switchMode(lifecycleOwner: LifecycleOwner, isPhotoMode: Boolean) {
        val provider = cameraProvider ?: return
        if (isPhotoModeBinding == isPhotoMode) return
        isPhotoModeBinding = isPhotoMode
        concurrentBindingError = null
        isConcurrentMode = false
        isFrontVideoEnabled = false
        provider.unbindAll()
        recreateUseCases()
        bindCameras(provider, lifecycleOwner)
    }

    private fun recreateUseCases() {
        backPreview = Preview.Builder().build()
            .also { it.setSurfaceProvider(backPreviewView?.surfaceProvider) }
        frontPreview = Preview.Builder().build()
            .also { it.setSurfaceProvider(frontPreviewView?.surfaceProvider) }
        backImageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        frontImageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        backVideoCapture = VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
        )
        frontVideoCapture = VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
        )
    }

    private fun bindCameras(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        Log.d(TAG, "bindCameras photoMode=$isPhotoModeBinding")
        tryConcurrentBinding(provider, lifecycleOwner)
    }

    /**
     * Find bindToLifecycle(List) and extract SingleCameraConfig class from its generic signature.
     * This is version-independent — no hardcoded class names.
     */
    private fun findBindMethodAndConfigClass(
        provider: ProcessCameraProvider
    ): Pair<java.lang.reflect.Method, Class<*>> {
        val method = generateSequence<Class<*>>(provider.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { m ->
                m.name == "bindToLifecycle" &&
                m.parameterCount == 1 &&
                m.parameterTypes[0] == List::class.java
            }
            ?: throw NoSuchMethodException(
                "bindToLifecycle(List) not found on ${provider.javaClass.name}"
            )
        method.isAccessible = true

        val configClass = runCatching {
            val paramType = method.genericParameterTypes[0] as? ParameterizedType
            paramType?.actualTypeArguments?.getOrNull(0) as? Class<*>
        }.getOrNull()
            ?: throw ClassNotFoundException(
                "Cannot extract SingleCameraConfig type from ${method.genericParameterTypes.firstOrNull()}"
            )
        Log.d(TAG, "SingleCameraConfig class: ${configClass.name}")
        return Pair(method, configClass)
    }

    private fun makeSingleCameraConfig(
        configClass: Class<*>,
        cameraSelector: CameraSelector,
        useCaseGroup: UseCaseGroup,
        lifecycleOwner: LifecycleOwner
    ): Any {
        val ctor = runCatching {
            configClass.getConstructor(
                CameraSelector::class.java,
                UseCaseGroup::class.java,
                LifecycleOwner::class.java
            )
        }.getOrNull()
            ?: configClass.declaredConstructors.firstOrNull { c ->
                c.parameterCount >= 3 &&
                c.parameterTypes[0].isAssignableFrom(CameraSelector::class.java) &&
                c.parameterTypes[1].isAssignableFrom(UseCaseGroup::class.java) &&
                c.parameterTypes[2].isAssignableFrom(LifecycleOwner::class.java)
            }
            ?: throw NoSuchMethodException(
                "No suitable constructor in ${configClass.name}. " +
                "Available: ${configClass.declaredConstructors.joinToString { c ->
                    "(${c.parameterTypes.joinToString { it.simpleName }})"
                }}"
            )
        ctor.isAccessible = true
        val args: Array<Any?> = Array(ctor.parameterCount) { i ->
            when (i) { 0 -> cameraSelector; 1 -> useCaseGroup; 2 -> lifecycleOwner; else -> null }
        }
        return ctor.newInstance(*args)
    }

    // Concurrent: photo=Preview+IC both cameras, video=Preview+VC both cameras (max 2 per camera)
    private fun tryConcurrentBinding(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        try {
            val (bindMethod, configClass) = findBindMethodAndConfigClass(provider)

            val backGroup: UseCaseGroup
            val frontGroup: UseCaseGroup
            if (isPhotoModeBinding) {
                backGroup = UseCaseGroup.Builder()
                    .addUseCase(backPreview!!).addUseCase(backImageCapture!!).build()
                frontGroup = UseCaseGroup.Builder()
                    .addUseCase(frontPreview!!).addUseCase(frontImageCapture!!).build()
            } else {
                backGroup = UseCaseGroup.Builder()
                    .addUseCase(backPreview!!).addUseCase(backVideoCapture!!).build()
                frontGroup = UseCaseGroup.Builder()
                    .addUseCase(frontPreview!!).addUseCase(frontVideoCapture!!).build()
            }

            val backConfig = makeSingleCameraConfig(configClass, CameraSelector.DEFAULT_BACK_CAMERA, backGroup, lifecycleOwner)
            val frontConfig = makeSingleCameraConfig(configClass, CameraSelector.DEFAULT_FRONT_CAMERA, frontGroup, lifecycleOwner)
            bindMethod.invoke(provider, listOf(backConfig, frontConfig))

            isConcurrentMode = true
            isFrontVideoEnabled = !isPhotoModeBinding
            Log.d(TAG, "Concurrent binding succeeded photoMode=$isPhotoModeBinding")
        } catch (e: Exception) {
            val cause = e.cause ?: e
            concurrentBindingError = "${cause.javaClass.simpleName}: ${cause.message}"
            Log.w(TAG, "Concurrent binding failed: $concurrentBindingError")
            if (!isPhotoModeBinding) {
                tryConcurrentVideoFrontPreviewOnly(provider, lifecycleOwner)
            } else {
                fallbackBinding(provider, lifecycleOwner)
            }
        }
    }

    // Video mode fallback: back(Preview+VC) + front(Preview only) — still concurrent, no front video
    private fun tryConcurrentVideoFrontPreviewOnly(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        try {
            val (bindMethod, configClass) = findBindMethodAndConfigClass(provider)

            val backGroup = UseCaseGroup.Builder()
                .addUseCase(backPreview!!).addUseCase(backVideoCapture!!).build()
            val frontGroup = UseCaseGroup.Builder()
                .addUseCase(frontPreview!!).build()

            val backConfig = makeSingleCameraConfig(configClass, CameraSelector.DEFAULT_BACK_CAMERA, backGroup, lifecycleOwner)
            val frontConfig = makeSingleCameraConfig(configClass, CameraSelector.DEFAULT_FRONT_CAMERA, frontGroup, lifecycleOwner)
            bindMethod.invoke(provider, listOf(backConfig, frontConfig))

            isConcurrentMode = true
            isFrontVideoEnabled = false
            Log.d(TAG, "Concurrent video (front preview only) succeeded")
        } catch (e: Exception) {
            val cause = e.cause ?: e
            Log.w(TAG, "Concurrent video (front preview only) failed: ${cause.message}, falling back")
            fallbackBinding(provider, lifecycleOwner)
        }
    }

    // Last resort: back camera only with all 3 use cases
    private fun fallbackBinding(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                backPreview, backImageCapture, backVideoCapture
            )
            isConcurrentMode = false
            isFrontVideoEnabled = false
            Log.d(TAG, "Fallback: back camera only")
        } catch (e: Exception) {
            Log.e(TAG, "All camera binding attempts failed: $e")
        }
    }

    suspend fun capturePhoto(): Pair<Bitmap, Bitmap?> {
        val executor = ContextCompat.getMainExecutor(context)
        val backBitmap = captureImage(backImageCapture!!, executor, mirrorHorizontal = false)
        val frontBitmap = if (isConcurrentMode) {
            runCatching {
                captureImage(frontImageCapture!!, executor, mirrorHorizontal = true)
            }.getOrNull()
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
