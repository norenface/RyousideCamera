package com.example.ryousidecamera.media

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VideoCompositor(private val context: Context) {

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val FLOAT_SIZE = 4

        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uTexMatrix;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()

        // pos(x,y) + uv(u,v) for a triangle-strip quad
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,  0f, 0f,
             1f, -1f,  1f, 0f,
            -1f,  1f,  0f, 1f,
             1f,  1f,  1f, 1f
        )
    }

    // EGL
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // GL resources
    private var programId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var texMatrixHandle = 0
    private var texSamplerHandle = 0
    private var vboId = 0
    private var backTexId = 0
    private var frontTexId = 0
    private var backST: SurfaceTexture? = null
    private var frontST: SurfaceTexture? = null
    private var backSurface: Surface? = null
    private var frontSurface: Surface? = null
    private val backTexMatrix = FloatArray(16)
    private val frontTexMatrix = FloatArray(16)

    // Frame synchronization via semaphores (set from GL thread listener)
    private val backFrameSema = Semaphore(0)
    private val frontFrameSema = Semaphore(0)

    private val glThread = HandlerThread("VideoCompositorGL").also { it.start() }
    private val glHandler = Handler(glThread.looper)

    private suspend fun onGLThread(block: () -> Unit) = suspendCancellableCoroutine<Unit> { cont ->
        glHandler.post {
            try { block(); cont.resume(Unit) }
            catch (e: Exception) { cont.resumeWithException(e) }
        }
    }

    // ─────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────

    suspend fun compose(
        backFile: File,
        frontFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): Uri = withContext(Dispatchers.IO) {

        val (width, height, frameRate) = probeVideo(backFile)

        val outputFile = File(context.cacheDir, "combined_${System.currentTimeMillis()}.mp4")

        // Encoder
        val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()

        // EGL + GL setup on dedicated GL thread
        onGLThread {
            setupEGL(encoderInputSurface)
            setupGL()
            backST!!.setOnFrameAvailableListener({ backFrameSema.release() }, glHandler)
            frontST!!.setOnFrameAvailableListener({ frontFrameSema.release() }, glHandler)
        }

        encoder.start()

        // Pre-read audio format so we can add it to the muxer before muxer.start()
        val audioFormat = getAudioFormat(backFile)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIdx = -1
        var audioTrackIdx = -1
        var muxerStarted = false

        // Video extractors & decoders
        val backExtractor = MediaExtractor().apply { setDataSource(backFile.absolutePath) }
        val backTrack = findVideoTrack(backExtractor)
        backExtractor.selectTrack(backTrack)
        val backFmt = backExtractor.getTrackFormat(backTrack)

        val backDecoder = MediaCodec.createDecoderByType(backFmt.getString(MediaFormat.KEY_MIME)!!)
        backDecoder.configure(backFmt, backSurface!!, null, 0)
        backDecoder.start()

        val frontExtractor = MediaExtractor().apply { setDataSource(frontFile.absolutePath) }
        val frontTrack = findVideoTrack(frontExtractor)
        frontExtractor.selectTrack(frontTrack)
        val frontFmt = frontExtractor.getTrackFormat(frontTrack)

        val frontDecoder = MediaCodec.createDecoderByType(frontFmt.getString(MediaFormat.KEY_MIME)!!)
        frontDecoder.configure(frontFmt, frontSurface!!, null, 0)
        frontDecoder.start()

        val bufInfo = MediaCodec.BufferInfo()
        var backInputDone = false
        var frontInputDone = false
        var backOutputDone = false
        var frontOutputDone = false
        var backHasFrame = false
        var frontHasFrame = false
        var backPTS = 0L
        var frontPTS = 0L
        val totalDurationUs = minOf(getDurationUs(backFile), getDurationUs(frontFile)).coerceAtLeast(1L)

        while (!backOutputDone || !frontOutputDone) {

            // ── Feed decoder inputs ──────────────────────────────────────
            if (!backInputDone) feedDecoder(backDecoder, backExtractor) { backInputDone = true }
            if (!frontInputDone) feedDecoder(frontDecoder, frontExtractor) { frontInputDone = true }

            // ── Drain back decoder output ────────────────────────────────
            if (!backOutputDone && !backHasFrame) {
                val idx = backDecoder.dequeueOutputBuffer(bufInfo, 0)
                when {
                    idx >= 0 -> {
                        val eos = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (eos) {
                            backDecoder.releaseOutputBuffer(idx, false)
                            backOutputDone = true
                        } else if (bufInfo.size > 0) {
                            backPTS = bufInfo.presentationTimeUs
                            backDecoder.releaseOutputBuffer(idx, true)
                            backFrameSema.tryAcquire(300, TimeUnit.MILLISECONDS)
                            backHasFrame = true
                        } else {
                            backDecoder.releaseOutputBuffer(idx, false)
                        }
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                }
            }

            // ── Drain front decoder output ───────────────────────────────
            if (!frontOutputDone && !frontHasFrame) {
                val idx = frontDecoder.dequeueOutputBuffer(bufInfo, 0)
                when {
                    idx >= 0 -> {
                        val eos = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (eos) {
                            frontDecoder.releaseOutputBuffer(idx, false)
                            frontOutputDone = true
                        } else if (bufInfo.size > 0) {
                            frontPTS = bufInfo.presentationTimeUs
                            frontDecoder.releaseOutputBuffer(idx, true)
                            frontFrameSema.tryAcquire(300, TimeUnit.MILLISECONDS)
                            frontHasFrame = true
                        } else {
                            frontDecoder.releaseOutputBuffer(idx, false)
                        }
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                }
            }

            // ── Composite when frame(s) ready ────────────────────────────
            val canComposite = (backHasFrame && frontHasFrame) ||
                (backHasFrame && frontOutputDone) ||
                (frontHasFrame && backOutputDone)

            if (canComposite) {
                val pts = if (backHasFrame) backPTS else frontPTS
                onProgress?.invoke(pts.toFloat() / totalDurationUs)

                onGLThread {
                    if (backHasFrame) {
                        backST!!.updateTexImage()
                        backST!!.getTransformMatrix(backTexMatrix)
                    }
                    if (frontHasFrame) {
                        frontST!!.updateTexImage()
                        frontST!!.getTransformMatrix(frontTexMatrix)
                    }

                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                    // Back camera: full screen
                    GLES20.glViewport(0, 0, width, height)
                    renderTexture(backTexId, backTexMatrix)

                    // Front camera: PiP (top-right, 1/4 width)
                    if (frontHasFrame) {
                        val pw = width / 4
                        val ph = height / 4
                        val margin = (width * 0.02f).toInt()
                        GLES20.glViewport(width - pw - margin, height - ph - margin, pw, ph)
                        renderTexture(frontTexId, frontTexMatrix)
                    }

                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, pts * 1000L)
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                }

                backHasFrame = false
                frontHasFrame = false
            }

            // ── Drain encoder output → muxer ─────────────────────────────
            drainEncoder(encoder, muxer, audioFormat,
                { idx -> videoTrackIdx = idx },
                { idx -> audioTrackIdx = idx },
                { muxerStarted = true },
                videoTrackIdx, muxerStarted
            )
        }

        // Signal EOS to encoder then drain remaining
        encoder.signalEndOfInputStream()
        drainEncoderToEnd(encoder, muxer, videoTrackIdx, muxerStarted)

        // Copy audio frames (muxer is still open, audio track already registered)
        if (muxerStarted && audioTrackIdx >= 0) {
            copyAudioFrames(backFile, muxer, audioTrackIdx)
        }

        muxer.stop()
        muxer.release()

        encoder.stop()
        encoder.release()
        encoderInputSurface.release()

        backDecoder.stop(); backDecoder.release()
        frontDecoder.stop(); frontDecoder.release()
        backExtractor.release(); frontExtractor.release()

        onGLThread { releaseGL(); releaseEGL() }
        glThread.quit()

        saveToMediaStore(outputFile)
    }

    // ─────────────────────────────────────────────────────────
    // EGL helpers
    // ─────────────────────────────────────────────────────────

    private fun setupEGL(outputSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 1)

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, IntArray(1), 0)
        val cfg = configs[0]!!

        eglContext = EGL14.eglCreateContext(
            eglDisplay, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, cfg, outputSurface,
            intArrayOf(EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun releaseEGL() {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(eglDisplay)
    }

    // ─────────────────────────────────────────────────────────
    // GL helpers
    // ─────────────────────────────────────────────────────────

    private fun setupGL() {
        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        backTexId = ids[0]; frontTexId = ids[1]

        for (id in ids) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        backST = SurfaceTexture(backTexId);  backSurface = Surface(backST!!)
        frontST = SurfaceTexture(frontTexId); frontSurface = Surface(frontST!!)

        programId = buildProgram()
        positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        texMatrixHandle = GLES20.glGetUniformLocation(programId, "uTexMatrix")
        texSamplerHandle = GLES20.glGetUniformLocation(programId, "sTexture")

        val vbo = IntArray(1)
        GLES20.glGenBuffers(1, vbo, 0); vboId = vbo[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        val buf = ByteBuffer.allocateDirect(QUAD_VERTICES.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(QUAD_VERTICES).flip()
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, QUAD_VERTICES.size * FLOAT_SIZE, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun releaseGL() {
        backSurface?.release(); frontSurface?.release()
        backST?.release();     frontST?.release()
        if (vboId != 0) GLES20.glDeleteBuffers(1, intArrayOf(vboId), 0)
        if (programId != 0) GLES20.glDeleteProgram(programId)
        if (backTexId != 0 || frontTexId != 0)
            GLES20.glDeleteTextures(2, intArrayOf(backTexId, frontTexId), 0)
    }

    private fun renderTexture(texId: Int, matrix: FloatArray) {
        GLES20.glUseProgram(programId)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)

        val stride = 4 * FLOAT_SIZE
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, stride, 2 * FLOAT_SIZE)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, matrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(texSamplerHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun buildProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs); GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs);       GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    // ─────────────────────────────────────────────────────────
    // Encoder / muxer helpers
    // ─────────────────────────────────────────────────────────

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        audioFormat: MediaFormat?,
        onVideoTrack: (Int) -> Unit,
        onAudioTrack: (Int) -> Unit,
        onStarted: () -> Unit,
        videoTrackIdx: Int,
        muxerStarted: Boolean
    ) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        val vt = muxer.addTrack(encoder.outputFormat)
                        onVideoTrack(vt)
                        if (audioFormat != null) {
                            val at = muxer.addTrack(audioFormat)
                            onAudioTrack(at)
                        }
                        muxer.start()
                        onStarted()
                    }
                }
                idx >= 0 -> {
                    if (muxerStarted && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        muxer.writeSampleData(videoTrackIdx, encoder.getOutputBuffer(idx)!!, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(idx, false)
                    if (eos) return
                }
                else -> return
            }
        }
    }

    private fun drainEncoderToEnd(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        videoTrackIdx: Int,
        muxerStarted: Boolean
    ) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                idx >= 0 -> {
                    if (muxerStarted && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        muxer.writeSampleData(videoTrackIdx, encoder.getOutputBuffer(idx)!!, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(idx, false)
                    if (eos) return
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> {}
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // Media helpers
    // ─────────────────────────────────────────────────────────

    private inline fun feedDecoder(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        crossinline onEOS: () -> Unit
    ) {
        val idx = decoder.dequeueInputBuffer(0)
        if (idx < 0) return
        val buf = decoder.getInputBuffer(idx)!!
        val n = extractor.readSampleData(buf, 0)
        if (n < 0) {
            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            onEOS()
        } else {
            decoder.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).first { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }

    private fun getAudioFormat(file: File): MediaFormat? {
        val ex = MediaExtractor().apply { setDataSource(file.absolutePath) }
        val fmt = (0 until ex.trackCount)
            .find { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?.let { ex.getTrackFormat(it) }
        ex.release()
        return fmt
    }

    private fun copyAudioFrames(sourceFile: File, muxer: MediaMuxer, trackIndex: Int) {
        val ex = MediaExtractor().apply { setDataSource(sourceFile.absolutePath) }
        val audioTrack = (0 until ex.trackCount)
            .find { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { ex.release(); return }
        ex.selectTrack(audioTrack)
        val buf = ByteBuffer.allocate(512 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            info.set(0, n, ex.sampleTime, ex.sampleFlags)
            muxer.writeSampleData(trackIndex, buf, info)
            ex.advance()
        }
        ex.release()
    }

    private fun getDurationUs(file: File): Long {
        val r = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L
        r.release()
        return ms * 1000L
    }

    private fun probeVideo(file: File): Triple<Int, Int, Int> {
        val r = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
        val fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toFloatOrNull()?.toInt()?.coerceIn(15, 60) ?: 30
        r.release()
        return Triple(w, h, fps)
    }

    private suspend fun saveToMediaStore(tmpFile: File): Uri = withContext(Dispatchers.IO) {
        val filename = "RyousideCamera_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RyousideCamera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")
        resolver.openOutputStream(uri)!!.use { out -> tmpFile.inputStream().use { it.copyTo(out) } }
        tmpFile.delete()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }
}
