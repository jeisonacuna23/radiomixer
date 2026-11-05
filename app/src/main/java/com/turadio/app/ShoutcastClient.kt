package com.turadio.app

import android.util.Base64
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

class ShoutcastClient {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var writerThread: Thread? = null
    
    // Cola que controla la velocidad del envío
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var isStreaming = false
    var onConnectionLost: (() -> Unit)? = null

    fun connect(ip: String, port: Int, user: String, password: String): String {
        return try {
            socket = Socket()
            socket?.connect(InetSocketAddress(ip, port), 5000)
            out = DataOutputStream(socket?.getOutputStream())

            val auth = Base64.encodeToString("$user:$password".toByteArray(), Base64.NO_WRAP)
            
            // Usamos la ruta /stream/1 que funcionó en la prueba
            val header = "SOURCE /stream/1 HTTP/1.0\r\n" +
                         "Host: $ip:$port\r\n" +
                         "User-Agent: RadioBOSS/5.0\r\n" + 
                         "Authorization: Basic $auth\r\n" +
                         "Content-Type: audio/aacp\r\n" +
                         "icy-br: 64\r\n" +
                         "icy-sid: 1\r\n\r\n"
            
            out?.write(header.toByteArray())
            out?.flush()
            
            isStreaming = true
            startWriterThread()
            
            return "OK"
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }

    // Hilo dedicado exclusivamente a vaciar la cola hacia el servidor
    private fun startWriterThread() {
        writerThread = Thread {
            try {
                while (isStreaming) {
                    val data = audioQueue.take() // Se queda esperando si no hay datos
                    out?.write(data)
                    out?.flush()
                }
            } catch (e: Exception) {
                if (isStreaming) {
                    onConnectionLost?.invoke()
                }
            }
        }
        writerThread?.start()
    }

    // Ahora solo encolamos el audio, no lo enviamos directamente
    fun sendAudio(data: ByteArray) {
        if (isStreaming) {
            audioQueue.offer(data)
        }
    }

    fun disconnect() {
        isStreaming = false
        writerThread?.interrupt()
        audioQueue.clear()
        try {
            out?.close()
            socket?.close()
        } catch (e: Exception) {}
    }
}
