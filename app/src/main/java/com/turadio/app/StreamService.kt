package com.turadio.app

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

class StreamService : Service() {
    private var exoPlayer: ExoPlayer? = null
    private var shoutcastClient: ShoutcastClient? = null
    private var encoder: AacEncoder? = null

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
            .setContentTitle("RadioMixer en Vivo (AAC)")
            .setContentText("Conectando a SonicPanel...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)

        // 1. Iniciar Encoder
        encoder = AacEncoder()
        encoder?.start { aacData ->
            shoutcastClient?.sendAudio(aacData)
        }

        // 2. Configurar ExoPlayer para que capture el audio
        val captureProcessor = CaptureAudioProcessor { pcm ->
            encoder?.encode(pcm)
        }
        
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(captureProcessor))
                    .build()
            }
        }
        exoPlayer = ExoPlayer.Builder(this, renderersFactory).build()

        // 3. Conectar al 8017 y luego darle Play a la música
        shoutcastClient = ShoutcastClient(ip, port, pass) { error ->
            stopSelf()
        }
        shoutcastClient?.connectAndStream {
            exoPlayer?.setMediaItem(MediaItem.fromUri(Uri.parse(uriString)))
            exoPlayer?.prepare()
            exoPlayer?.play()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        exoPlayer?.release()
        encoder?.stop()
        shoutcastClient?.disconnect()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("RadioChannel", "Radio", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
