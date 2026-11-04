package com.turadio.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

class AacEncoder {
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    fun start(onEncodedData: (ByteArray, Int) -> Unit) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64000) // 64 kbps
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec?.start()

        Thread {
            while (true) {
                val inputIndex = codec?.dequeueInputBuffer(10000) ?: -1
                // Nota: Esta es una implementación simplificada. 
                // Para producción real, aquí se inyectan los bytes PCM capturados.
                if (inputIndex >= 0) {
                    // Simulando inyección de datos para que compile
                    codec?.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1000, 0)
                }

                val outputIndex = codec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                if (outputIndex >= 0) {
                    val outBuffer = codec?.getOutputBuffer(outputIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.get(data)
                        onEncodedData(data, bufferInfo.size)
                    }
                    codec?.releaseOutputBuffer(outputIndex, false)
                }
            }
        }.start()
    }

    fun stop() {
        try { codec?.stop() } catch (e: Exception) {}
        codec?.release()
        codec = null
    }
}
