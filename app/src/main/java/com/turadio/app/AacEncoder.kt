package com.turadio.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

class AacEncoder {
    private var codec: MediaCodec? = null
    private var isRunning = false
    private var thread: Thread? = null
    private var callback: ((ByteArray) -> Unit)? = null

    fun start(onData: (ByteArray) -> Unit) {
        this.callback = onData
        this.isRunning = true

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64000) // 64 kbps
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec?.start()

        thread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning) {
                try {
                    val outIndex = codec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    if (outIndex >= 0) {
                        val buffer = codec?.getOutputBuffer(outIndex)
                        if (buffer != null && bufferInfo.size > 0) {
                            val data = ByteArray(bufferInfo.size)
                            buffer.position(bufferInfo.offset)
                            buffer.get(data)
                            callback?.invoke(addAdtsHeader(data))
                        }
                        codec?.releaseOutputBuffer(outIndex, false)
                    }
                } catch (e: Exception) { break }
            }
        }
        thread?.start()
    }

    fun encode(pcmData: ByteArray) {
        if (!isRunning || codec == null) return
        try {
            val inIndex = codec?.dequeueInputBuffer(10000) ?: -1
            if (inIndex >= 0) {
                val buffer = codec?.getInputBuffer(inIndex)
                if (buffer != null) {
                    buffer.clear()
                    val dataToWrite = if (pcmData.size > buffer.capacity()) pcmData.copyOf(buffer.capacity()) else pcmData
                    buffer.put(dataToWrite)
                    codec?.queueInputBuffer(inIndex, 0, dataToWrite.size, System.nanoTime() / 1000, 0)
                }
            }
        } catch (e: Exception) {}
    }

    fun stop() {
        isRunning = false
        thread?.join(1000)
        try { codec?.stop() } catch (e: Exception) {}
        codec?.release()
        codec = null
    }

    // Convierte el AAC a AAC-ADTS (el formato exacto que acepta Shoutcast)
    private fun addAdtsHeader(aacData: ByteArray): ByteArray {
        val packetSize = aacData.size + 7
        val adts = ByteArray(7)
        val profile = 2 // AAC LC
        val freqIdx = 4 // 44100 Hz
        val chanCfg = 2 // Stereo

        adts[0] = 0xFF.toByte()
        adts[1] = 0xF1.toByte()
        adts[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        adts[3] = (((chanCfg and 3) shl 6) + (packetSize shr 11)).toByte()
        adts[4] = ((packetSize and 0x7FF) shr 3).toByte()
        adts[5] = (((packetSize and 7) shl 5) + 0x1F).toByte()
        adts[6] = 0xFC.toByte()

        return adts + aacData
    }
}
