package com.turadio.app

import android.app.*
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
        val uriList = intent?.getStringArrayListExtra("URIS") ?: return START_NOT_STICKY

        val uris = uriList.map { Uri.parse(it) }

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
                    reproducirPlaylist(uris)
                }

            } catch (e: Exception) {
                currentStatus = "Fallo: ${e.message}"
                updateNotification(currentStatus)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun reproducirPlaylist(uris: List<Uri>) {
        var index = 0
        // Bucle infinito para el Auto-DJ
        while (shoutcastClient != null && uris.isNotEmpty()) {
            val uriActual = uris[index]
            
            // Extraer el nombre del archivo para mostrarlo en la UI
            val nombreCancion = uriActual.lastPathSegment?.substringAfterLast("/")?.substringAfterLast(":") ?: "Canción"
            val intentUpdate = Intent("SONG_UPDATE").putExtra("SONG_NAME", nombreCancion)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intentUpdate)
            
            enviarAudioNativo(uriActual)

            // Pasar a la siguiente canción (vuelve al inicio si llega al final)
            index++
            if (index >= uris.size) {
                index = 0
            }
        }
    }

    private fun enviarAudioNativo(uri: Uri) {
        val extractor = MediaExtractor()
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            }

            var audioTrackIndex = -1
            var isAac = false
            var sampleRate = 44100
            var channelCount = 2
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    if (mime.contains("aac") || mime.contains("mp4a")) {
                        isAac = true
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    break
                }
            }

            if (audioTrackIndex < 0) return

            extractor.selectTrack(audioTrackIndex)
            val buffer = ByteBuffer.allocate(65536)
            
            var bytesEnviadosTotales = 0L
            val tiempoInicio = System.currentTimeMillis()

            while (shoutcastClient != null) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break // Fin de la canción

                var audioData = ByteArray(sampleSize)
                buffer.position(0)
                buffer.get(audioData)

                if (isAac) {
                    audioData = addAdtsHeader(audioData, sampleRate, channelCount)
                }

                shoutcastClient?.sendAudio(audioData, audioData.size)
                bytesEnviadosTotales += audioData.size
                extractor.advance()

                val tiempoTranscurridoMs = System.currentTimeMillis() - tiempoInicio
                val tiempoQueDeberiaTardarMs = (bytesEnviadosTotales / 8000.0) * 1000.0
                val tiempoEspera = tiempoQueDeberiaTardarMs - tiempoTranscurridoMs
                if (tiempoEspera > 0) {
                    Thread.sleep(tiempoEspera.toLong())
                }
            }

        } catch (e: Exception) {
            // Ignoramos el error para que pase a la siguiente canción
        } finally {
            extractor.release()
        }
    }

    private fun addAdtsHeader(aacData: ByteArray, sampleRate: Int, channelCount: Int): ByteArray {
        val packetSize = aacData.size + 7
        val adts = ByteArray(7)
        val profile = 2
        val freqIdx = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; 7350 -> 12; else -> 4
        }
        val chanCfg = channelCount

        adts[0] = 0xFF.toByte()
        adts[1] = 0xF1.toByte()
        adts[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        adts[3] = (((chanCfg and 3) shl 6) + (packetSize shr 11)).toByte()
        adts[4] = ((packetSize and 0x7FF) shr 3).toByte()
        adts[5] = (((packetSize and 7) shl 5) + 0x1F).toByte()
        adts[6] = 0xFC.toByte()

        return adts + aacData
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
            .setContentTitle("RadioMixer (Auto-DJ)")
            .setContentText(currentStatus)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, buildNotification())
    }
}
