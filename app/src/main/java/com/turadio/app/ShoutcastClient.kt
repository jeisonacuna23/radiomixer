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
            // Timeout de 5 segundos para que no se quede colgado
            socket?.connect(InetSocketAddress(ip, port), 5000)
            out = DataOutputStream(socket?.getOutputStream())

            val auth = Base64.encodeToString("source:$password".toByteArray(), Base64.NO_WRAP)
            
            // Protocolo Shoutcast v2 con headers completos
            val header = "SOURCE / HTTP/1.0\r\n" +
                         "Host: $ip:$port\r\n" +
                         "User-Agent: RadioMixer/1.0\r\n" +
                         "Authorization: Basic $auth\r\n" +
                         "Content-Type: audio/aacp\r\n" +
                         "icy-name: RadioMixer\r\n" +
                         "icy-bitrate: 64\r\n" +
                         "icy-pub: 0\r\n\r\n"
            
            out?.write(header.toByteArray())
            out?.flush()
            
            // Leer la respuesta del servidor
            val reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
            val response = reader.readLine()
            
            if (response?.contains("200") == true || response?.contains("OK") == true) {
                "OK"
            } else {
                "Servidor rechazó conexión: $response"
            }
        } catch (e: Exception) {
            "Error de red: ${e.message}"
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
