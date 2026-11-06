package com.turadio.app

import java.io.DataOutputStream
import java.net.Socket
import kotlin.concurrent.thread

class ShoutcastClient(
    private val ip: String,
    private val port: Int,
    private val password: String,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var isStreaming = false

    fun connectAndStream(onConnected: () -> Unit) {
        isStreaming = true
        thread {
            try {
                onStatus("Conectando al puerto $port...")
                socket = Socket(ip, port)
                socket?.soTimeout = 5000
                out = DataOutputStream(socket?.getOutputStream())

                onStatus("Enviando contraseña...")
                out?.write("$password\r\n".toByteArray())
                out?.flush()

                onStatus("Esperando OK2...")
                val reader = socket?.getInputStream()?.bufferedReader()
                var response = ""
                while (isStreaming) {
                    val line = reader?.readLine()
                    if (line == null) break
                    response += line
                    if (line.contains("OK2")) break
                }

                if (!response.contains("OK2")) {
                    onError("Rechazado. Respuesta: $response")
                    return@thread
                }

                onStatus("Conectado. Enviando cabeceras...")
                // AQUÍ CAMBIAMOS A AAC
                val headers = "icy-name: RadioMixer\r\n" +
                              "icy-genre: Varios\r\n" +
                              "icy-br: 64\r\n" +
                              "content-type: audio/aacp\r\n\r\n"
                out?.write(headers.toByteArray())
                out?.flush()
                
                onStatus("¡En Vivo (AAC)!")
                onConnected()

            } catch (e: Exception) {
                onError("Error de red: ${e.message}")
            }
        }
    }

    fun sendAudio(data: ByteArray, length: Int) {
        if (!isStreaming) return
        try {
            out?.write(data, 0, length)
            out?.flush()
        } catch (e: Exception) {
            isStreaming = false
            onError("Error enviando audio: ${e.message}")
        }
    }

    fun disconnect() {
        isStreaming = false
        try { out?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
    }
}
