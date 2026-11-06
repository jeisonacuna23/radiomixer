package com.turadio.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
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
    private var currentSong = ""
    private var startTime = 0L
    private var timeoutMinutes = 0
    private var isLineInMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val ip = intent?.getStringExtra("IP") ?: return START_NOT_STICKY
        val port = intent?.getIntExtra("PORT", 8017) ?: 8017
        val pass = intent?.getStringExtra("PASS") ?: ""
        timeoutMinutes = intent?.getIntExtra("TIMEOUT_MINUTES", 0) ?: 0
        val uriList = intent?.getStringArrayListExtra("URIS") ?: emptyList()
        isLineInMode = intent?.getBooleanExtra("LINE_IN_MODE", false) ?: false

        val uris = uriList.map { Uri.parse(it) }
        startTime = System.currentTimeMillis()

        createNotificationChannel()
        updateNotification("Conectando...", "")
        
        val initialNotif = buildNotification("Conectando...", "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (isLineInMode) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(1, initialNotif, type)
        } else {
            startForeground(1, initialNotif)
        }

        if (timeoutMinutes > 0) {
            serviceScope.launch {
                delay(timeoutMinutes * 60 * 1000L)
                stopSelf()
            }
        }

        serviceScope.launch {
            try {
                if (shoutcastClient == null) {
                    shoutcastClient = ShoutcastClient(
                        ip, port, pass,
                        onStatus = { status ->
                            currentStatus = status
                            updateNotification(status, currentSong)
                        },
                        onError = { error ->
                            currentStatus = "ERROR: $error"
                            updateNotification(error, "")
                            stopSelf()
                        }
                    )
                }

                shoutcastClient?.connectAndStream {
                    val timeIntent = Intent("TIME_UPDATE")
                        .putExtra("START_TIME", startTime)
                        .putExtra("TIMEOUT", timeoutMinutes)
                    LocalBroadcastManager.getInstance(this@StreamService).sendBroadcast(timeIntent)
                    
                    if (isLineInMode) {
                        // MODO MEZCLADORA
                        currentSong = "Entrada de Línea (Live)"
                        LocalBroadcastManager.getInstance(this@StreamService).sendBroadcast(Intent("SONG_UPDATE").putExtra("SONG_NAME", currentSong))
                        iniciarLineIn()
                    } else {
                        // MODO AUTO-DJ
                        reproducirPlaylist(uris)
                    }
                }

            } catch (e: Exception) {
                currentStatus = "Fallo: ${e.message}"
                updateNotification(currentStatus, "")
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun iniciarLineIn() {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        // Usamos DEFAULT o UNPROCESSED para evitar que Android le meta filtros de voz a la música
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.DEFAULT
        }

        val audioRecord = AudioRecord(
            source,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufSize * 2
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            currentStatus = "Error: No se pudo abrir el micrófono/línea"
            updateNotification(currentStatus, "")
            stopSelf()
            return
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        audioRecord.startRecording()
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (shoutcastClient != null) {
                val pcmBuffer = ByteArray(minBufSize)
                val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                if (read > 0) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(pcmBuffer, 0, read)
                        codec.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1000, 0)
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                while (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val aacData = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(aacData)
                        val adtsData = addAdtsHeader(aacData, sampleRate, 2)
                        shoutcastClient?.sendAudio(adtsData, adtsData.size)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioRecord.stop()
            audioRecord.release()
            codec.stop()
            codec.release()
        }
    }

    private fun reproducirPlaylist(uris: List<Uri>) {
        var index = 0
        while (shoutcastClient != null && uris.isNotEmpty()) {
            val uriActual = uris[index]
            
            currentSong = uriActual.lastPathSegment?.substringAfterLast("/")?.substringAfterLast(":") ?: "Canción"
            val intentUpdate = Intent("SONG_UPDATE").putExtra("SONG_NAME", currentSong)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intentUpdate)
            updateNotification("En Vivo", currentSong)

            val retriever = MediaMetadataRetriever()
            var trackDurationSeconds = 0L
            try {
                retriever.setDataSource(this, uriActual)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                trackDurationSeconds = durationMs / 1000
            } catch (e: Exception) {
            } finally {
                retriever.release()
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("TRACK_DURATION_UPDATE").putExtra("DURATION", trackDurationSeconds)
            )
            
            enviarAudioNativo(uriActual)

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
            var lastBroadcastedSecond = -1L

            while (shoutcastClient != null) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                var audioData = ByteArray(sampleSize)
                buffer.position(0)
                buffer.get(audioData)

                if (isAac) {
                    audioData = addAdtsHeader(audioData, sampleRate, channelCount)
                }

                shoutcastClient?.sendAudio(audioData, audioData.size)
                bytesEnviadosTotales += audioData.size
                extractor.advance()

                val currentTrackSecond = bytesEnviadosTotales / 8000
                if (currentTrackSecond > lastBroadcastedSecond) {
                    lastBroadcastedSecond = currentTrackSecond
                    LocalBroadcastManager.getInstance(this).sendBroadcast(
                        Intent("TRACK_PROGRESS_UPDATE").putExtra("PROGRESS", currentTrackSecond)
                    )
                }

                val tiempoTranscurridoMs = System.currentTimeMillis() - tiempoInicio
                val tiempoQueDeberiaTardarMs = (bytesEnviadosTotales / 8000.0) * 1000.0
                val tiempoEspera = tiempoQueDeberiaTardarMs - tiempoTranscurridoMs
                if (tiempoEspera > 0) {
                    Thread.sleep(tiempoEspera.toLong())
                }
            }
        } catch (e: Exception) {
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

    private fun buildNotification(title: String, song: String): Notification {
        val builder = Notification.Builder(this, "RadioChannel")
            .setContentTitle("RadioMixer: $title")
            .setSmallIcon(android.R.drawable.ic_media_play)

        if (song.isNotEmpty()) {
            builder.setContentText(song)
        } else {
            builder.setContentText(title)
        }
        return builder.build()
    }

    private fun updateNotification(title: String, song: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, buildNotification(title, song))
    }
}
