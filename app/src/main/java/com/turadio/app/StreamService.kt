package com.turadio.app

import android.app.*
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import java.nio.ByteBuffer

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
                    enviarAudioNativo(Uri.parse(uriString))
                }

            } catch (e: Exception) {
                currentStatus = "Fallo: ${e.message}"
                updateNotification(currentStatus)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun enviarAudioNativo(uri: Uri) {
        val extractor = MediaExtractor()
        try {
            // Abrimos el archivo usando el motor nativo de Android
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            }

            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex < 0) {
                currentStatus = "No se encontró audio en el archivo"
                updateNotification(currentStatus)
                return
            }

            extractor.selectTrack(audioTrackIndex)
            val buffer = ByteBuffer.allocate(65536) // Buffer amplio para frames
            
            var bytesEnviadosTotales = 0L
            val tiempoInicio = System.currentTimeMillis()

            while (shoutcastClient != null) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    currentStatus = "Fin de la canción"
                    updateNotification(currentStatus)
                    break
                }

                val audioData = ByteArray(sampleSize)
                buffer.position(0)
                buffer.get(audioData)

                // Enviamos el frame MP3 exacto
                shoutcastClient?.sendAudio(audioData, sampleSize)
                bytesEnviadosTotales += sampleSize
                extractor.advance()

                // Control de tiempo real (Pacing)
                // A 64kbps = 8000 bytes por segundo.
                val tiempoTranscurridoMs = System.currentTimeMillis() - tiempoInicio
                val tiempoQueDeberiaTardarMs = (bytesEnviadosTotales / 8000.0) * 1000.0
                
                val tiempoEspera = tiempoQueDeberiaTardarMs - tiempoTranscurridoMs
                if (tiempoEspera > 0) {
                    Thread.sleep(tiempoEspera.toLong())
                }
            }

        } catch (e: Exception) {
            currentStatus = "Error al leer: ${e.message}"
            updateNotification(currentStatus)
        } finally {
            extractor.release()
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
            .setContentTitle("RadioMixer (Modo Nativo)")
            .setContentText(currentStatus)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, buildNotification())
    }
}
