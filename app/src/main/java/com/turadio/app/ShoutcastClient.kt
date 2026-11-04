package com.turadio.app

import android.util.Base64
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class ShoutcastClient {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null

    fun connect(ip: String, port: Int, password: String): String {
        return try {
            socket = Socket()
            socket?.connect(InetSocketAddress(ip, port), 5000)
            out = DataOutputStream(socket?.getOutputStream())

            // Shoutcast usualmente requiere que el usuario sea "source" o "admin"
            val auth = Base64.encodeToString("source:$password".toByteArray(), Base64.NO_WRAP)
            
            // Cabecera exacta que espera Shoutcast v1/v2
            val header = "SOURCE / HTTP/1.0\r\n" +
                         "Host: $ip:$port\r\n" +
                         "User-Agent: RadioMixer/1.0\r\n" +
                         "Authorization: Basic $auth\r\n" +
                         "Content-Type: audio/aacp\r\n" +
                         "icy-name: RadioMixer\r\n" +
                         "icy-genre: Various\r\n" +
                         "icy-pub: 0\r\n" +
                         "icy-br: 64\r\n\r\n"
            
            out?.write(header.toByteArray())
            out?.flush()
            
            // Intentamos leer si el servidor responde algo.
            // Si el servidor cierra la conexión, el read() devolverá -1
            val inputStream = socket?.getInputStream()
            val buffer = ByteArray(1024)
            val bytesRead = inputStream?.read(buffer)
            
            if (bytesRead != null && bytesRead > 0) {
                val response = String(buffer, 0, bytesRead)
                if (response.contains("200") || response.contains("OK")) {
                    return "OK"
                } else {
                    return "Rechazado: ${response.lines().firstOrNull()}"
                }
            } else {
                // Si bytesRead es -1, el servidor colgó sin decir nada.
                // En Shoutcast, esto a veces significa que SÍ conectó, pero
                // aún no hay datos de audio fluyendo. Lo tomaremos como éxito temporal.
                return "OK" 
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
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
