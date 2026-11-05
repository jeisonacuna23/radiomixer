package com.turadio.app

import android.media.MediaExtractor
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class ShoutcastClient(
    private val ip: String,
    private val port: Int,
    private val password: String,
    private val onError: (String) -> Unit
) {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var isStreaming = false

    fun connectAndStream(extractor: MediaExtractor, buffer: ByteBuffer) {
        isStreaming = true
        thread {
            try {
                socket = Socket(ip, port)
                socket?.soTimeout = 10000
                out = DataOutputStream(socket?.getOutputStream())

                // Handshake Shoutcast v1 (Puerto 8017)
                out?.write("$password\r\n".toByteArray())
                out?.flush()

                val reader = socket?.getInputStream()?.bufferedReader()
                var response = ""
                while (isStreaming) {
                    val line = reader?.readLine()
                    if (line == null) break
                    response += line
                    if (line.contains("OK2")) break
                }

                if (!response.contains("OK2")) {
                    onError("Servidor rechazó: $response")
                    return@thread
                }

                // Enviar cabeceras
                val headers = "icy-name: RadioMixer\r\n" +
                              "icy-genre: Varios\r\n" +
                              "icy-br: 64\r\n" +
                              "content-type: audio/mpeg\r\n\r\n"
                out?.write(headers.toByteArray())
                out?.flush()

                // Leer del MediaExtractor y enviar en tiempo real
                while (isStreaming) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break // Fin del archivo
                    
                    val audioData = ByteArray(sampleSize)
                    buffer.position(0)
                    buffer.get(audioData)
                    
                    out?.write(audioData)
                    out?.flush()
                    
                    extractor.advance()
                    // Pausa de 50ms para simular tiempo real (evita desbordar el servidor)
                    Thread.sleep(50)
                }

            } catch (e: Exception) {
                if (isStreaming) onError("Error: ${e.message}")
            } finally {
                disconnect()
            }
        }
    }

    fun disconnect() {
        isStreaming = false
        try { out?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
    }
}
