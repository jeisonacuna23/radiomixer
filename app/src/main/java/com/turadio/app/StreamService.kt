package com.turadio.app

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import java.io.InputStream

class StreamService : Service() {
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var shoutcastClient: ShoutcastClient? = null
    private var currentStatus = "Iniciando..."

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val ip = intent?.getStringExtra("IP") ?: return START_NOT_STICKY
        val port = intent?.getIntExtra("PORT", 8017) ?: 8017
        val pass = intent?.getStringExtra("PASS") ?: ""
        val uriString = intent?.getStringExtra("URI") ?: return START_NOT_STICKY

        createNotificationChannel()
        updateNotification("Conectando...")
        startForeground(1, buildNotification())

        serviceScope.launch {
            try {
                shoutcastClient = ShoutcastClient(
                    ip, port, pass,
                    onStatus = { status ->
                        currentStatus = status
                        updateNotification(status)
                    },
                    onError = { error ->
                        currentStatus = "ERROR: $error"
                        updateNotification(error)
                        stopSelf()
                    }
                )

                shoutcastClient?.connectAndStream {
                    // Una vez conectados (OK2), empezamos a leer el archivo
                    enviarAudioPreCodificado(Uri.parse(uriString))
                }

            } catch (e: Exception) {
                currentStatus = "Fallo: ${e.message}"
                updateNotification(currentStatus)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun enviarAudioPreCodificado(uri: Uri) {
        var inputStream: InputStream? = null
        try {
            inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                currentStatus = "No se pudo abrir el archivo"
                updateNotification(currentStatus)
                return
            }

            // 64 kbps = 8000 bytes por segundo.
            // Leeremos bloques de 2048 bytes y dormiremos para mantener el ritmo.
            val buffer = ByteArray(2048)
            var bytesRead: Int
            
            // Buffer inicial para que el servidor no se queje de "flujo vacío" al inicio
            val preBuffer = ByteArray(16384) 
            val preRead = inputStream.read(preBuffer)
            if (preRead > 0) {
                shoutcastClient?.sendAudio(preBuffer, preRead)
            }

            while (shoutcastClient != null) {
                bytesRead = inputStream.read(buffer)
                if (bytesRead <= 0) {
                    currentStatus = "Fin de la canción"
                    updateNotification(currentStatus)
                    break
                }

                shoutcastClient?.sendAudio(buffer, bytesRead)
                
                // Pacing: 2048 bytes / 8000 bytes/seg = 0.256 segundos = 256 ms
                Thread.sleep(256) 
            }

        } catch (e: Exception) {
            currentStatus = "Error al leer: ${e.message}"
            updateNotification(currentStatus)
        } finally {
            try { inputStream?.close() } catch (e: Exception) {}
            stopSelf()
        }
    }

    override fun onDestroy() {
        shoutcastClient?.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("RadioChannel", "Radio", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, "RadioChannel")
            .setContentTitle("RadioMixer (Modo Directo)")
            .setContentText(currentStatus)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, buildNotification())
    }
}
