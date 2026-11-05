package com.turadio.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

class StreamService : Service() {
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ffmpegSessionId: Long? = null
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
                val tempFile = File(cacheDir, "audio_temp.mp3")
                contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }

                val localServer = ServerSocket(0)
                val localPort = localServer.localPort
                var ffmpegSocket: Socket? = null

                // Forzamos CBR a 64kbps estricto
                val cmd = "-re -i \"${tempFile.absolutePath}\" -c:a libmp3lame -b:a 64k -minrate 64k -maxrate 64k -bufsize 128k -ar 44100 -ac 2 -f mp3 tcp://127.0.0.1:$localPort"
                
                val session = FFmpegKit.executeAsync(cmd) { session ->
                    if (!ReturnCode.isSuccess(session.returnCode)) {
                        stopSelf()
                    }
                }
                ffmpegSessionId = session.sessionId

                withContext(Dispatchers.IO) {
                    ffmpegSocket = localServer.accept()
                }

                val audioStream: InputStream = ffmpegSocket!!.getInputStream()
                shoutcastClient = ShoutcastClient(ip, port, pass) { error ->
                    stopSelf()
                }
                shoutcastClient?.connectAndStream(audioStream)

            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        ffmpegSessionId?.let { FFmpegKit.cancel(it) }
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
