package com.turadio.app

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

class CaptureAudioProcessor(private val onPcmData: (ByteArray) -> Unit) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputBuffer.remaining() > 0) {
            val bytes = ByteArray(inputBuffer.remaining())
            inputBuffer.get(bytes)
            
            // Pasamos el audio al codificador AAC
            onPcmData(bytes)
            
            // Lo devolvemos para que suene por el altavoz
            val outBuffer = replaceOutputBuffer(bytes.size)
            outBuffer.put(bytes)
            outBuffer.flip()
        }
    }
}
