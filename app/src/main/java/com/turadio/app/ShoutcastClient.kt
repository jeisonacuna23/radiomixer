package com.turadio.app

import java.io.DataOutputStream
import java.net.Socket
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

    fun connectAndStream(onConnected: () -> Unit) {
        isStreaming = true
        thread {
            try {
                socket = Socket(ip, port)
                socket?.soTimeout = 10000
                out = DataOutputStream(socket?.getOutputStream())

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

                // Cabeceras para AAC
                val headers = "icy-name: RadioMixer\r\n" +
                              "icy-genre: Varios\r\n" +
                              "icy-br: 64\r\n" +
                              "content-type: audio/aacp\r\n\r\n"
                out?.write(headers.toByteArray())
                out?.flush()
                
                onConnected()

            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
        }
    }

    fun sendAudio(data: ByteArray) {
        if (!isStreaming) return
        try {
            out?.write(data)
            out?.flush()
        } catch (e: Exception) {
            isStreaming = false
        }
    }

    fun disconnect() {
        isStreaming = false
        try { out?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
    }
}
