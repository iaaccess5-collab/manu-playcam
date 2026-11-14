package com.manumusicprod.playcam.session

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.manumusicprod.playcam.core.WavWriter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Assemble la vidéo de la caméra et l'audio WAV de l'instrument dans un MP4 final.
 * L'audio est encodé en AAC et placé selon le décalage mesuré (horodatages), sans réencoder l'image.
 */
object AvMuxer {

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /**
     * @param audioOffsetNs position de la première trame audio par rapport à la première image
     *        (négatif : l'audio a commencé avant la vidéo, le début est coupé ; positif : l'audio est retardé)
     */
    fun mux(video: File, wav: File, out: File, audioOffsetNs: Long, onProgress: (Float) -> Unit = {}) {
        val ex = MediaExtractor()
        ex.setDataSource(video.path)
        var vTrack = -1
        var vFormat: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { vTrack = i; vFormat = f; break }
        }
        require(vTrack >= 0 && vFormat != null) { "Piste vidéo introuvable" }
        ex.selectTrack(vTrack)
        val rotation = if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) vFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
        val videoDurUs = if (vFormat.containsKey(MediaFormat.KEY_DURATION)) vFormat.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE

        val (aFormat, aSamples) = encodeAac(wav, audioOffsetNs, videoDurUs) { onProgress(it * 0.7f) }

        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            muxer.setOrientationHint(rotation)
            val vt = muxer.addTrack(vFormat)
            val at = if (aFormat != null && aSamples.isNotEmpty()) muxer.addTrack(aFormat) else -1
            muxer.start()

            val maxIn = if (vFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = ByteBuffer.allocateDirect(maxOf(maxIn, 8 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            var ai = 0
            fun writeAudioUntil(limitUs: Long) {
                if (at < 0) return
                while (ai < aSamples.size && aSamples[ai].ptsUs <= limitUs) {
                    val s = aSamples[ai]
                    info.set(0, s.data.size, s.ptsUs, s.flags)
                    muxer.writeSampleData(at, ByteBuffer.wrap(s.data), info)
                    ai++
                }
            }
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val pts = ex.sampleTime
                writeAudioUntil(pts)
                var flags = 0
                if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                info.set(0, size, pts, flags)
                muxer.writeSampleData(vt, buf, info)
                if (videoDurUs != Long.MAX_VALUE && videoDurUs > 0) onProgress(0.7f + 0.3f * (pts.toFloat() / videoDurUs).coerceIn(0f, 1f))
                ex.advance()
            }
            writeAudioUntil(Long.MAX_VALUE)
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            ex.release()
        }
        onProgress(1f)
    }

    private fun encodeAac(wav: File, offsetNs: Long, maxDurUs: Long, onProgress: (Float) -> Unit): Pair<MediaFormat?, List<Sample>> {
        val info = WavWriter.readInfo(wav)
        val rate = info[0].toInt(); val ch = info[1].toInt(); val dataLen = info[3]
        val frameBytes = 2 * ch
        if (dataLen < frameBytes * 1024L) return null to emptyList()

        var skipFrames = 0L
        var delayUs = 0L
        if (offsetNs < 0) skipFrames = (-offsetNs) * rate / 1_000_000_000L else delayUs = offsetNs / 1000L

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (ch == 2) 256_000 else 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val input = FileInputStream(wav)
        input.skip(44 + skipFrames * frameBytes)
        val total = (dataLen - skipFrames * frameBytes).coerceAtLeast(0)
        var fed = 0L
        var inputDone = false
        var outFormat: MediaFormat? = null
        val samples = ArrayList<Sample>()
        val bi = MediaCodec.BufferInfo()
        val chunk = ByteArray(4096 * frameBytes)
        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = codec.getInputBuffer(idx)!!
                        ib.clear()
                        val want = minOf(ib.remaining(), chunk.size) / frameBytes * frameBytes
                        val n = input.read(chunk, 0, want)
                        val pts = delayUs + (fed / frameBytes) * 1_000_000L / rate
                        if (n <= 0 || pts > maxDurUs + 200_000L) {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val nn = n / frameBytes * frameBytes
                            ib.put(chunk, 0, nn)
                            codec.queueInputBuffer(idx, 0, nn, pts, 0)
                            fed += nn
                            if (total > 0) onProgress((fed.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(bi, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    o >= 0 -> {
                        val ob = codec.getOutputBuffer(o)!!
                        if ((bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) {
                            val data = ByteArray(bi.size)
                            ob.position(bi.offset); ob.limit(bi.offset + bi.size); ob.get(data)
                            samples.add(Sample(data, bi.presentationTimeUs, 0))
                        }
                        codec.releaseOutputBuffer(o, false)
                        if ((bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } finally {
            input.close()
            runCatching { codec.stop() }
            codec.release()
        }
        return outFormat to samples
    }
}
