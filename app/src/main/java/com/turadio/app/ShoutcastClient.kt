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
    private var sendThread: Thread? = null

    fun connectAndStream(audioInputStream: java.io.InputStream) {
        isStreaming = true
        thread {
            try {
                // 1. Conectar al puerto de fuente (8017)
                socket = Socket(ip, port)
                socket?.soTimeout = 10000
                out = DataOutputStream(socket?.getOutputStream())

                // 2. Enviar contraseña
                out?.write("$password\r\n".toByteArray())
                out?.flush()

                // 3. Esperar respuesta OK2
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

                // 4. Enviar cabeceras
                val headers = "icy-name: RadioMixer\r\n" +
                              "icy-genre: Varios\r\n" +
                              "icy-br: 64\r\n" +
                              "content-type: audio/mpeg\r\n\r\n"
                out?.write(headers.toByteArray())
                out?.flush()

                // 5. Enviar audio
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (isStreaming) {
                    bytesRead = audioInputStream.read(buffer)
                    if (bytesRead > 0) {
                        out?.write(buffer, 0, bytesRead)
                        out?.flush()
                    }
                }
            } catch (e: Exception) {
                if (isStreaming) onError("Error en conexión: ${e.message}")
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
