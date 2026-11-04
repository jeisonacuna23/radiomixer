package com.turadio.app

import android.util.Base64
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class ShoutcastClient {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null

    fun connect(ip: String, port: Int, password: String, mount: String): String {
        return try {
            socket = Socket()
            socket?.connect(InetSocketAddress(ip, port), 5000)
            out = DataOutputStream(socket?.getOutputStream())

            val auth = Base64.encodeToString("source:$password".toByteArray(), Base64.NO_WRAP)
            
            // Aseguramos que el mountpoint empiece con "/"
            val finalMount = if (mount.startsWith("/")) mount else "/$mount"
            
            val header = "SOURCE $finalMount HTTP/1.0\r\n" +
                         "Host: $ip:$port\r\n" +
                         "User-Agent: RadioMixer/1.0\r\n" +
                         "Authorization: Basic $auth\r\n" +
                         "Content-Type: audio/mpeg\r\n" + 
                         "icy-name: RadioMixer\r\n" +
                         "icy-genre: Various\r\n" +
                         "icy-br: 64\r\n" +
                         "icy-pub: 0\r\n\r\n"
            
            out?.write(header.toByteArray())
            out?.flush()
            
            // IMPORTANTE: Shoutcast no responde nada hasta que recibe audio.
            // Si el socket no lanzó excepción, asumimos que el túnel está abierto.
            return "OK"
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }

    fun sendAudio(data: ByteArray) {
        try {
            out?.write(data)
            out?.flush()
        } catch (e: Exception) {}
    }

    fun disconnect() {
        try {
            out?.close()
            socket?.close()
        } catch (e: Exception) {}
    }
}
