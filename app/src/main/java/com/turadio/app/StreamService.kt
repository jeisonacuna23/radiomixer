package com.turadio.app

import android.app.*
import android.content.Context
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
        val notification = Notification.Builder(this, "RadioChannel")
            .setContentTitle("RadioMixer en Vivo")
            .setContentText("Transmitiendo a SonicPanel...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)

        serviceScope.launch {
            try {
                val extractor = MediaExtractor()
                contentResolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                    
                    var audioTrackIndex = -1
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            audioTrackIndex = i
                            break
                        }
                    }

                    if (audioTrackIndex >= 0) {
                        extractor.selectTrack(audioTrackIndex)
                        val buffer = ByteBuffer.allocate(16384)
                        
                        // Conectar al Shoutcast (Handshake ya programado)
                        shoutcastClient = ShoutcastClient(ip, port, pass) { error ->
                            stopSelf()
                        }
                        shoutcastClient?.connectAndStream(extractor, buffer)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        }

        return START_STICKY
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
}
