package com.turadio.app

import android.util.Base64
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class ShoutcastClient {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    var onConnectionLost: (() -> Unit)? = null

    fun connect(ip: String, port: Int, user: String, password: String): String {
        return try {
            socket = Socket()
            socket?.connect(InetSocketAddress(ip, port), 5000)
            out = DataOutputStream(socket?.getOutputStream())

            // Usamos el usuario del panel en lugar de "source"
            val auth = Base64.encodeToString("$user:$password".toByteArray(), Base64.NO_WRAP)
            
            val header = "SOURCE / HTTP/1.0\r\n" +
                         "Host: $ip:$port\r\n" +
                         "User-Agent: RadioMixer/1.0\r\n" +
                         "Authorization: Basic $auth\r\n" +
                         "Content-Type: audio/aacp\r\n" +
                         "icy-name: RadioMixer\r\n" +
                         "icy-genre: Various\r\n" +
                         "icy-br: 64\r\n" +
                         "icy-sid: 1\r\n" +
                         "icy-pub: 0\r\n\r\n"
            
            out?.write(header.toByteArray())
            out?.flush()
            
            return "OK"
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }

    fun sendAudio(data: ByteArray) {
        try {
            out?.write(data)
            out?.flush()
        } catch (e: Exception) {
            onConnectionLost?.invoke()
            disconnect()
        }
    }

    fun disconnect() {
        try {
            out?.close()
            socket?.close()
        } catch (e: Exception) {}
    }
}
