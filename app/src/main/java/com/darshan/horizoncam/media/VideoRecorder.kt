package com.darshan.horizoncam.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.darshan.horizoncam.gl.EncoderSink
import com.darshan.horizoncam.gl.HorizonRenderer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Picks a video encoder and size the device can actually handle. */
object EncoderSupport {
    class Choice(val mime: String, val codecName: String, val width: Int, val height: Int, val rotate90: Boolean)

    private fun encoders(mime: String): List<MediaCodecInfo> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val list = all.filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } }
        // Prefer hardware encoders.
        return list.sortedBy { info ->
            val sw = if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
            else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
            if (sw) 1 else 0
        }
    }

    private fun supports(info: MediaCodecInfo, mime: String, w: Int, h: Int, fps: Int): Boolean = try {
        val vc = info.getCapabilitiesForType(mime).videoCapabilities
        vc != null && vc.isSizeSupported(w, h) && vc.areSizeAndRateSupported(w, h, fps.toDouble())
    } catch (e: Exception) { false }

    fun pick(mime: String, w: Int, h: Int, fps: Int): Choice? {
        for (info in encoders(mime)) {
            if (supports(info, mime, w, h, fps)) return Choice(mime, info.name, w, h, false)
            // Portrait sizes are not supported by some encoders: encode sideways + rotation hint.
            if (w < h && supports(info, mime, h, w, fps)) return Choice(mime, info.name, h, w, true)
        }
        return null
    }

    fun hasEncoder(mime: String): Boolean = encoders(mime).isNotEmpty()

    fun bitrateRange(codecName: String, mime: String): android.util.Range<Int>? = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == codecName }
            ?.getCapabilitiesForType(mime)?.videoCapabilities?.bitrateRange
    } catch (e: Exception) { null }
}

/** Collects encoded samples from several threads and starts the muxer once all tracks are known. */
private class MuxerHolder(private val muxer: MediaMuxer, private var expectedTracks: Int) {
    private class Pending(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)

    private val pending = ArrayList<Pending>()
    private var added = 0
    private var firstPendingMs = 0L
    var started = false; private set
    var samplesWritten = 0L; private set
    private val disabledTracks = HashSet<Int>()

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        if (started) return -1
        val idx = muxer.addTrack(format)
        added++
        if (added >= expectedTracks) start()
        return idx
    }

    /** A track that will never deliver a format (e.g. audio failed). */
    @Synchronized
    fun dropExpectedTrack() {
        expectedTracks = max(1, expectedTracks - 1)
        if (!started && added >= expectedTracks) start()
    }

    private fun start() {
        muxer.start()
        started = true
        for (p in pending) {
            muxer.writeSampleData(p.track, ByteBuffer.wrap(p.data), p.info)
            samplesWritten++
        }
        pending.clear()
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (track < 0 || disabledTracks.contains(track)) return
        if (started) {
            muxer.writeSampleData(track, buf, info)
            samplesWritten++
            return
        }
        val copy = ByteArray(info.size)
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        buf.get(copy)
        val i2 = MediaCodec.BufferInfo()
        i2.set(0, info.size, info.presentationTimeUs, info.flags)
        pending.add(Pending(track, copy, i2))
        val now = SystemClock.elapsedRealtime()
        if (firstPendingMs == 0L) firstPendingMs = now
        // Audio never showed up: start with the video track only.
        if (added >= 1 && now - firstPendingMs > 2500) {
            expectedTracks = added
            start()
        }
    }

    @Synchronized
    fun stop(): Boolean {
        if (!started) return false
        return try {
            muxer.stop()
            true
        } catch (e: Exception) {
            Log.e("MuxerHolder", "stop failed", e)
            false
        }
    }

    fun release() {
        try { muxer.release() } catch (e: Exception) { }
    }
}

/**
 * Records the renderer's horizon-locked output to MP4 (H.264/HEVC + AAC) using
 * MediaCodec surface input. Supports time-lapse (no audio, frames decimated).
 */
class VideoRecorder(private val context: Context, private val renderer: HorizonRenderer, private val listener: Listener) {

    interface Listener {
        fun onRecordingSaved(uri: Uri?, durationMs: Long)
        fun onRecordingFailed(message: String)
        fun onAudioLevel(level: Float)
    }

    data class Config(
        val width: Int,
        val height: Int,
        val rotate90: Boolean,
        val fps: Int,
        val bitrate: Int,
        val mime: String,
        val codecName: String,
        val audio: Boolean,
        val timelapse: Int
    )

    @Volatile var isRecording = false; private set
    @Volatile var lastPtsNs = 0L; private set
    private var startElapsed = 0L

    private var target: MediaSaver.Target? = null
    private var muxer: MuxerHolder? = null
    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    @Volatile private var videoEosSignaled = false
    private val t0Mono = AtomicLong(-1)

    val elapsedMs: Long get() = if (isRecording) SystemClock.elapsedRealtime() - startElapsed else 0L

    fun start(cfg: Config): String? {
        if (isRecording) return "Already recording"
        val name = MediaSaver.timestampName(if (cfg.timelapse > 1) "HC_TL" else "HC", "mp4")
        val t = MediaSaver.create(context, true, name) ?: return "Cannot create video file"
        target = t
        try {
            val m = MediaMuxer(t.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (cfg.rotate90) m.setOrientationHint(90)
            val wantAudio = cfg.audio && cfg.timelapse <= 1
            val holder = MuxerHolder(m, if (wantAudio) 2 else 1)
            muxer = holder

            val codec = createVideoCodec(cfg)
            videoCodec = codec
            val surface = codec.createInputSurface()
            inputSurface = surface
            codec.start()

            t0Mono.set(-1)
            lastPtsNs = 0
            videoEosSignaled = false

            val vt = Thread({ drainVideo(codec, holder) }, "VideoDrain")
            videoThread = vt
            vt.start()

            if (wantAudio) {
                audioRunning = true
                val at = Thread({ runAudio(holder) }, "AudioEncode")
                audioThread = at
                at.start()
            }

            val ok = renderer.startEncoding(surface, cfg.width, cfg.height, cfg.rotate90, cfg.timelapse, cfg.fps, object : EncoderSink {
                override fun onFirstFrame(monoNs: Long) { t0Mono.set(monoNs) }
                override fun onFrameEncoded(ptsNs: Long) { lastPtsNs = ptsNs }
            })
            if (!ok) throw IllegalStateException("Renderer could not attach to the encoder")
            startElapsed = SystemClock.elapsedRealtime()
            isRecording = true
            return null
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            cleanupAfterFailure()
            return "Recording failed to start: ${e.message}"
        }
    }

    private fun createVideoCodec(cfg: Config): MediaCodec {
        fun format(withProfile: Boolean, withColor: Boolean): MediaFormat {
            val f = MediaFormat.createVideoFormat(cfg.mime, cfg.width, cfg.height)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            f.setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrate)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            f.setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            if (withProfile && cfg.mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel52)
            }
            if (withColor) {
                // Tag the stream as standard HD video so players show the intended colours.
                f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            return f
        }
        val highSupported = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == cfg.codecName }
                ?.getCapabilitiesForType(cfg.mime)?.profileLevels
                ?.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh } ?: false
        } catch (e: Exception) { false }
        val attempts = mutableListOf<MediaFormat>()
        if (highSupported) attempts.add(format(withProfile = true, withColor = true))
        attempts.add(format(withProfile = false, withColor = true))
        attempts.add(format(withProfile = false, withColor = false))
        var last: Exception? = null
        for (f in attempts) {
            val c = MediaCodec.createByCodecName(cfg.codecName)
            try {
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return c
            } catch (e: Exception) {
                Log.w(TAG, "Encoder rejected $f", e)
                last = e
                c.release()
            }
        }
        throw last ?: IllegalStateException("Encoder configuration failed")
    }

    private fun drainVideo(codec: MediaCodec, holder: MuxerHolder) {
        val info = MediaCodec.BufferInfo()
        var track = -1
        var eosWaitStart = 0L
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (videoEosSignaled) {
                            if (eosWaitStart == 0L) eosWaitStart = SystemClock.elapsedRealtime()
                            if (SystemClock.elapsedRealtime() - eosWaitStart > 3000) break
                        }
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = holder.addTrack(codec.outputFormat)
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buf != null && info.size > 0 && track >= 0) holder.write(track, buf, info)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "video drain error", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val sources = intArrayOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)
        val rates = intArrayOf(48000, 44100)
        val channels = intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        for (src in sources) for (rate in rates) for (ch in channels) {
            try {
                val min = AudioRecord.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val r = AudioRecord(src, rate, ch, AudioFormat.ENCODING_PCM_16BIT, max(min * 4, 32768))
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (e: Exception) { }
        }
        return null
    }

    private fun runAudio(holder: MuxerHolder) {
        val record = createAudioRecord()
        if (record == null) {
            holder.dropExpectedTrack()
            return
        }
        val rate = record.sampleRate
        val chCount = record.channelCount
        val codec: MediaCodec
        try {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, chCount)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, if (chCount == 2) 192_000 else 128_000)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            Log.e(TAG, "audio codec failed", e)
            record.release()
            holder.dropExpectedTrack()
            return
        }
        val bytesPerFrame = 2 * chCount
        val chunkFrames = 1024
        val pcm = ByteArray(chunkFrames * bytesPerFrame)
        val info = MediaCodec.BufferInfo()
        val ts = AudioTimestamp()
        var track = -1
        var framesRead = 0L
        var anchorNs = -1L
        var lastPtsUs = -1L
        var levelCounter = 0

        fun drain(untilEos: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 2000
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!untilEos || SystemClock.elapsedRealtime() > deadline) return
                    continue
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = holder.addTrack(codec.outputFormat)
                    continue
                }
                if (idx < 0) continue
                val buf = codec.getOutputBuffer(idx)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (buf != null && info.size > 0 && track >= 0) holder.write(track, buf, info)
                codec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }

        try {
            record.startRecording()
            while (audioRunning) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val frames = n / bytesPerFrame
                val chunkStart = framesRead
                framesRead += frames

                // Level meter (~10 Hz).
                if (++levelCounter % 4 == 0) {
                    var sum = 0.0
                    var i = 0
                    while (i + 1 < n) {
                        val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
                        sum += s * s
                        i += 2
                    }
                    val rms = sqrt(sum / max(1, n / 2)) / 32768.0
                    listener.onAudioLevel(rms.toFloat())
                }

                val ptsNs: Long = if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    ts.nanoTime + (chunkStart - ts.framePosition) * 1_000_000_000L / rate
                } else {
                    if (anchorNs < 0) anchorNs = System.nanoTime() - frames * 1_000_000_000L / rate
                    anchorNs + chunkStart * 1_000_000_000L / rate
                }
                val t0 = t0Mono.get()
                if (t0 < 0) continue
                var ptsUs = (ptsNs - t0) / 1000
                if (ptsUs < 0) continue
                if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
                // Queue the chunk (split if the codec buffer is smaller).
                var off = 0
                while (off < n) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx < 0) break
                    val inBuf = codec.getInputBuffer(inIdx) ?: break
                    inBuf.clear()
                    val len = minOf(n - off, inBuf.capacity())
                    inBuf.put(pcm, off, len)
                    val subPts = ptsUs + (off / bytesPerFrame) * 1_000_000L / rate
                    codec.queueInputBuffer(inIdx, 0, len, subPts, 0)
                    lastPtsUs = subPts
                    off += len
                }
                drain(false)
            }
            // End of stream.
            val inIdx = codec.dequeueInputBuffer(20_000)
            if (inIdx >= 0) {
                codec.queueInputBuffer(inIdx, 0, 0, max(0, lastPtsUs + 1), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
        } catch (e: Exception) {
            Log.e(TAG, "audio error", e)
        } finally {
            try { record.stop() } catch (e: Exception) { }
            record.release()
            try { codec.stop() } catch (e: Exception) { }
            codec.release()
            if (track < 0) holder.dropExpectedTrack()
        }
    }

    /** Stops recording; finalisation continues in the background and ends with a listener callback. */
    fun stop() {
        if (!isRecording) return
        isRecording = false
        renderer.stopEncoding()
        val codec = videoCodec
        try {
            videoEosSignaled = true
            codec?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream failed", e)
        }
        audioRunning = false
        val vt = videoThread
        val at = audioThread
        val holder = muxer
        val t = target
        val surface = inputSurface
        val durationMs = lastPtsNs / 1_000_000L
        Thread({
            try { vt?.join(5000) } catch (e: InterruptedException) { }
            try { at?.join(5000) } catch (e: InterruptedException) { }
            val ok = holder?.stop() ?: false
            holder?.release()
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            surface?.release()
            if (t != null) {
                MediaSaver.finish(context, t, ok) { uri ->
                    if (ok) listener.onRecordingSaved(uri, durationMs)
                    else listener.onRecordingFailed("Recording was too short or failed")
                }
            } else {
                listener.onRecordingFailed("Recording failed")
            }
        }, "RecorderFinish").start()
        videoThread = null
        audioThread = null
        videoCodec = null
        inputSurface = null
        muxer = null
        target = null
    }

    private fun cleanupAfterFailure() {
        audioRunning = false
        renderer.stopEncoding()
        try { videoCodec?.release() } catch (e: Exception) { }
        inputSurface?.release()
        muxer?.release()
        target?.let { MediaSaver.finish(context, it, false) {} }
        videoCodec = null; inputSurface = null; muxer = null; target = null
    }

    companion object {
        private const val TAG = "VideoRecorder"

        fun bitrateFor(mime: String, w: Int, h: Int, fps: Int, high: Boolean): Int {
            // Bits per pixel per frame; roughly matches or beats the stock camera app.
            val bpp = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) (if (high) 0.26 else 0.16) else (if (high) 0.40 else 0.25)
            return (w.toDouble() * h * fps * bpp).toInt().coerceIn(2_000_000, 120_000_000)
        }

        @Suppress("unused")
        private fun near(a: Double, b: Double) = abs(a - b) < 1e-3
    }
}
