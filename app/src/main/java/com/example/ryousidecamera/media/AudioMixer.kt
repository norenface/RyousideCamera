package com.example.ryousidecamera.media

import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.io.*
import java.nio.ByteBuffer

class AudioMixer(private val context: Context, private val mediaProjection: MediaProjection) {
    companion object {
        private const val TAG = "AudioMixer"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_COUNT = 2
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BIT_RATE = 128_000
    }

    private val captureSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        .coerceAtLeast(4096) * 4

    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var capturing = false
    private var pcmFile: File? = null

    fun start(pcmOut: File) {
        pcmFile = pcmOut

        micRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_MASK, ENCODING, captureSize
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                internalRecord = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(ENCODING)
                            .setChannelMask(CHANNEL_MASK)
                            .build()
                    )
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setBufferSizeInBytes(captureSize)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "Internal audio capture init failed: $e")
                internalRecord = null
            }
        }

        capturing = true
        micRecord?.startRecording()
        internalRecord?.startRecording()

        captureThread = Thread({ captureLoop(pcmOut) }, "AudioMixerThread")
        captureThread!!.start()
    }

    private fun captureLoop(out: File) {
        val micBuf = ByteArray(captureSize)
        val intBuf = ByteArray(captureSize)
        val mixed = ByteArray(captureSize)
        try {
            FileOutputStream(out).buffered(captureSize * 2).use { fos ->
                while (capturing) {
                    val micRead = micRecord?.read(micBuf, 0, captureSize) ?: break
                    if (micRead <= 0) continue

                    val intRead = (internalRecord?.read(intBuf, 0, micRead) ?: 0).coerceAtLeast(0)

                    // Mix little-endian 16-bit PCM samples from MIC + internal audio
                    var i = 0
                    while (i < micRead - 1) {
                        val micS = ((micBuf[i].toInt() and 0xFF) or ((micBuf[i + 1].toInt() and 0xFF) shl 8))
                            .let { if (it >= 0x8000) it - 0x10000 else it }
                        val intS = if (i + 1 < intRead)
                            ((intBuf[i].toInt() and 0xFF) or ((intBuf[i + 1].toInt() and 0xFF) shl 8))
                                .let { if (it >= 0x8000) it - 0x10000 else it }
                        else 0
                        val m = (micS + intS).coerceIn(-32768, 32767)
                        mixed[i] = (m and 0xFF).toByte()
                        mixed[i + 1] = ((m ushr 8) and 0xFF).toByte()
                        i += 2
                    }
                    fos.write(mixed, 0, micRead)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture loop error: $e")
        }
    }

    /** Stop capture and wait for thread to flush. */
    fun stop() {
        capturing = false
        try { micRecord?.stop() } catch (_: Exception) {}
        try { internalRecord?.stop() } catch (_: Exception) {}
        captureThread?.join(3000)
        micRecord?.release(); micRecord = null
        internalRecord?.release(); internalRecord = null
    }

    /** Stop capture and discard PCM data — for use when composition won't happen (onDestroy). */
    fun cleanup() {
        capturing = false
        try { micRecord?.stop() } catch (_: Exception) {}
        try { internalRecord?.stop() } catch (_: Exception) {}
        captureThread?.join(1000)
        micRecord?.release(); micRecord = null
        internalRecord?.release(); internalRecord = null
        pcmFile?.delete(); pcmFile = null
    }

    /**
     * Encode captured PCM to AAC and remux with [screenFile]'s video track.
     * Returns a new file containing video + mixed audio, or [screenFile] unchanged on failure.
     * The original [screenFile] is not deleted here — caller is responsible.
     */
    fun encodeAndRemux(screenFile: File): File {
        val pcm = pcmFile ?: return screenFile
        if (!pcm.exists() || pcm.length() < 1024) return screenFile

        val outputFile = File(context.cacheDir, "screen_mixed_${System.currentTimeMillis()}.mp4")
        val aacFile = File(context.cacheDir, "mixed_audio_${System.currentTimeMillis()}.m4a")
        return try {
            encodePcmToAac(pcm, aacFile)
            remuxVideoWithAudio(screenFile, aacFile, outputFile)
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "encodeAndRemux failed: $e")
            outputFile.delete()
            screenFile
        } finally {
            aacFile.delete()
            pcm.delete()
            pcmFile = null
        }
    }

    private fun encodePcmToAac(pcmFile: File, aacFile: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, captureSize * 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(aacFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var audioTrack = -1
        var muxerStarted = false
        var ptsUs = 0L
        val bytesPerFrame = CHANNEL_COUNT * 2  // stereo 16-bit

        try {
            FileInputStream(pcmFile).buffered().use { input ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            buf.clear()
                            val bytes = ByteArray(buf.remaining())
                            val n = input.read(bytes)
                            if (n <= 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.put(bytes, 0, n)
                                codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                                ptsUs += (n / bytesPerFrame) * 1_000_000L / SAMPLE_RATE
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            audioTrack = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            if (muxerStarted && info.size > 0 &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                muxer.writeSampleData(audioTrack, outBuf, info)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
    }

    private fun remuxVideoWithAudio(videoFile: File, aacFile: File, outputFile: File) {
        val videoEx = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioEx = MediaExtractor().apply { setDataSource(aacFile.absolutePath) }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var muxVideoTrack = -1
        for (i in 0 until videoEx.trackCount) {
            val fmt = videoEx.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                videoEx.selectTrack(i)
                muxVideoTrack = muxer.addTrack(fmt)
                break
            }
        }
        var muxAudioTrack = -1
        for (i in 0 until audioEx.trackCount) {
            val fmt = audioEx.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioEx.selectTrack(i)
                muxAudioTrack = muxer.addTrack(fmt)
                break
            }
        }

        muxer.start()
        val buf = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()

        try {
            if (muxVideoTrack >= 0) {
                while (true) {
                    buf.clear()
                    val sz = videoEx.readSampleData(buf, 0)
                    if (sz < 0) break
                    info.offset = 0; info.size = sz
                    info.presentationTimeUs = videoEx.sampleTime
                    info.flags = videoEx.sampleFlags
                    muxer.writeSampleData(muxVideoTrack, buf, info)
                    videoEx.advance()
                }
            }
            if (muxAudioTrack >= 0) {
                while (true) {
                    buf.clear()
                    val sz = audioEx.readSampleData(buf, 0)
                    if (sz < 0) break
                    info.offset = 0; info.size = sz
                    info.presentationTimeUs = audioEx.sampleTime
                    info.flags = audioEx.sampleFlags
                    muxer.writeSampleData(muxAudioTrack, buf, info)
                    audioEx.advance()
                }
            }
        } finally {
            videoEx.release()
            audioEx.release()
            muxer.stop()
            muxer.release()
        }
    }
}
