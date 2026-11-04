package com.turadio.app

import android.util.Base64
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
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

            val auth = Base64.encodeToString("source:$password".toByteArray(), Base64.NO_WRAP)
            
            // Probamos enviando Content-Type: audio/mpeg (por si el panel espera MP3)
            val header = "SOURCE / HTTP/1.0\r\n" +
                         "Host: $ip:$port\r\n" +
                         "User-Agent: Mozilla/5.0\r\n" +
                         "Authorization: Basic $auth\r\n" +
                         "Content-Type: audio/mpeg\r\n" +
                         "icy-name: RadioMixer\r\n" +
                         "icy-genre: Various\r\n" +
                         "icy-pub: 0\r\n" +
                         "icy-br: 64\r\n\r\n"
            
            out?.write(header.toByteArray())
            out?.flush()
            
            val reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
            
            // Leemos la primera línea de la respuesta HTTP
            val response = reader.readLine()
            
            if (response != null) {
                return "Respuesta: $response"
            } else {
                return "Servidor cerró sin responder. (¿AutoDJ encendido o formato incorrecto?)"
            }
        } catch (e: Exception) {
            return "Error de red: ${e.message}"
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
