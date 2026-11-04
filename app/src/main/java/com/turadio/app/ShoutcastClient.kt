package com.turadio.app

import android.util.Base64
import java.io.DataOutputStream
import java.net.Socket

class ShoutcastClient {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null

    fun connect(ip: String, port: Int, password: String): Boolean {
        return try {
            socket = Socket(ip, port)
            out = DataOutputStream(socket?.getOutputStream())

            val auth = Base64.encodeToString("source:$password".toByteArray(), Base64.NO_WRAP)
            
            // Petición HTTP estilo SOURCE para Shoutcast
            val header = "SOURCE / HTTP/1.0\r\n" +
                         "Authorization: Basic $auth\r\n" +
                         "Content-Type: audio/aacp\r\n" +
                         "icy-name: RadioMixer\r\n" +
                         "icy-bitrate: 64\r\n" +
                         "icy-pub: 0\r\n\r\n"
            
            out?.write(header.toByteArray())
            out?.flush()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun sendAudio(data: ByteArray) {
        try {
            out?.write(data)
            out?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disconnect() {
        try {
            out?.close()
            socket?.close()
        } catch (e: Exception) {}
    }
}
