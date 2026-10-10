package com.everydayecho.memorykeeper.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream

/** Encodes 16 kHz mono PCM16 to a raw ADTS .aac file (concatenable, accepted as audio/aac by Gemini). */
object AacEncoder {
    private const val SAMPLE_RATE = 16000
    private const val BITRATE = 32000

    fun encode(pcm: ShortArray, out: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        try {
            FileOutputStream(out).buffered().use { os ->
                var offset = 0
                var inputDone = false
                var outputDone = false
                var ptsUs = 0L
                while (!outputDone) {
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            buf.clear()
                            val n = minOf(buf.capacity() / 2, pcm.size - offset)
                            if (n <= 0) {
                                codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, offset, n)
                                codec.queueInputBuffer(idx, 0, n * 2, ptsUs, 0)
                                offset += n
                                ptsUs += n * 1_000_000L / SAMPLE_RATE
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi >= 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val data = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.get(data)
                            os.write(adtsHeader(data.size + 7))
                            os.write(data)
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        codec.releaseOutputBuffer(oi, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun adtsHeader(len: Int): ByteArray {
        val profile = 2 // AAC LC
        val freqIdx = 8 // 16 kHz
        val chan = 1
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            (((profile - 1) shl 6) or (freqIdx shl 2) or (chan shr 2)).toByte(),
            (((chan and 3) shl 6) or (len shr 11)).toByte(),
            ((len and 0x7FF) shr 3).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
