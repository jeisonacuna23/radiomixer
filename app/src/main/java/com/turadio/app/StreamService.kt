package com.turadio.app

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

class StreamService : Service() {
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var shoutcastClient: ShoutcastClient? = null
    private var currentStatus = "Iniciando..."
    private var currentSong = ""
    private var startTime = 0L
    private var timeoutMinutes = 0
    
    @Volatile private var isRecording = false
    @Volatile private var isStreaming = false
    @Volatile private var isConnected = false
    @Volatile private var isLiveMicActive = false

    private var outputStream: OutputStream? = null
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var micJob: Job? = null
    
    private val firebaseDbUrl = "https://radiomixer-proyecto-default-rtdb.firebaseio.com/now_playing.json"

    private var configIp = ""
    private var configPort = 8017
    private var configPass = ""
    private var configUris: List<Uri> = emptyList()
    private var configProgramName = ""
    private var configModeMezcladora = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            isStreaming = false
            isRecording = false
            notificarDesconexion()
            stopSelf()
            return START_NOT_STICKY
        }

        // CAMBIO EN VIVO CORREGIDO
        if (intent?.action == "TOGGLE_LIVE_MIC") {
            val nuevoEstado = intent.getBooleanExtra("IS_LIVE_MIC", false)
            configProgramName = intent.getStringExtra("PROGRAM_NAME") ?: "Programa en Vivo"
            
            if (nuevoEstado && !isLiveMicActive) {
                // ACTIVAR MICRÓFONO
                isLiveMicActive = true
                currentSong = configProgramName
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("SONG_UPDATE").putExtra("SONG_NAME", currentSong))
                actualizarNowPlaying(configProgramName)
                
                // ACTUALIZAR TIPO DE FOREGROUND SERVICE (Obligatorio en Android 14)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val notif = buildNotification("En Vivo (Micrófono)", currentSong)
                    startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
                
                iniciarLineIn()
            } else if (!nuevoEstado && isLiveMicActive) {
                // DESACTIVAR MICRÓFONO
                isLiveMicActive = false
                stopMicrophone()
                
                // VOLVER A FOREGROUND SERVICE DE MÚSICA
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val notif = buildNotification("En Vivo (Música)", currentSong)
                    startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                }
            }
            return START_STICKY
        }

        shoutcastClient?.disconnect()
        shoutcastClient = null
        serviceScope.cancel()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        configIp = intent?.getStringExtra("IP") ?: return START_NOT_STICKY
        configPort = intent?.getIntExtra("PORT", 8017) ?: 8017
        configPass = intent?.getStringExtra("PASS") ?: ""
        timeoutMinutes = intent?.getIntExtra("TIMEOUT_MINUTES", 0) ?: 0
        val uriList = intent?.getStringArrayListExtra("URIS") ?: emptyList()
        configModeMezcladora = intent?.getBooleanExtra("LINE_IN_MODE", false) ?: false
        isRecording = intent?.getBooleanExtra("RECORD_STREAM", false) ?: false
        configProgramName = intent?.getStringExtra("PROGRAM_NAME") ?: "Transmisión Especial"
        
        configUris = uriList.map { Uri.parse(it) }
        startTime = System.currentTimeMillis()
        isStreaming = true
        isLiveMicActive = configModeMezcladora

        createNotificationChannel()
        updateNotification("Conectando...", "")
        
        val initialNotif = buildNotification("Conectando...", "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (configModeMezcladora) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(1, initialNotif, type)
        } else {
            startForeground(1, initialNotif)
        }

        if (timeoutMinutes > 0) {
            serviceScope.launch {
                delay(timeoutMinutes * 60 * 1000L)
                isStreaming = false
                notificarDesconexion()
                stopSelf()
            }
        }

        conectarAServidor()
        return START_STICKY
    }

    private fun notificarDesconexion() {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val jsonDb = JSONObject().apply { put("titulo", "Fuera del aire"); put("timestamp", System.currentTimeMillis()) }
                val client = OkHttpClient()
                client.newCall(Request.Builder().url(firebaseDbUrl).put(jsonDb.toString().toRequestBody("application/json".toMediaType())).build()).execute().close()

                val inputStream = resources.openRawResource(com.turadio.app.R.raw.service_account)
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                val projectId = JSONObject(jsonString).getString("project_id")
                val credentials = GoogleCredentials.fromStream(jsonString.byteInputStream()).createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging"))
                credentials.refreshIfExpired()
                val url = "https://fcm.googleapis.com/v1/projects/$projectId/messages:send"
                val jsonPush = """{"message": {"topic": "radio_en_vivo","notification": {"title": "A Voz en Cuello","body": "La transmisión ha finalizado."}}}"""
                client.newCall(Request.Builder().url(url).addHeader("Authorization", "Bearer ${credentials.accessToken.tokenValue}").post(jsonPush.toRequestBody("application/json".toMediaType())).build()).execute().close()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun conectarAServidor() {
        serviceScope.launch {
            try {
                shoutcastClient = ShoutcastClient(
                    configIp, configPort, configPass,
                    onStatus = { status -> currentStatus = status; updateNotification(status, currentSong) },
                    onError = { error ->
                        isConnected = false
                        currentStatus = "Señal perdida. Reconectando..."
                        updateNotification(currentStatus, "Esperando red...")
                        serviceScope.launch { delay(5000); if (isStreaming) conectarAServidor() }
                    }
                )

                shoutcastClient?.connectAndStream {
                    isConnected = true
                    val timeIntent = Intent("TIME_UPDATE").putExtra("START_TIME", startTime).putExtra("TIMEOUT", timeoutMinutes)
                    LocalBroadcastManager.getInstance(this@StreamService).sendBroadcast(timeIntent)
                    
                    if (isLiveMicActive) {
                        currentSong = configProgramName
                        LocalBroadcastManager.getInstance(this@StreamService).sendBroadcast(Intent("SONG_UPDATE").putExtra("SONG_NAME", currentSong))
                        actualizarNowPlaying(configProgramName)
                        iniciarLineIn()
                    } else {
                        reproducirPlaylist(configUris)
                    }
                }
            } catch (e: Exception) {
                isConnected = false
                serviceScope.launch { delay(5000); if (isStreaming) conectarAServidor() }
            }
        }
    }
    
    private fun actualizarNowPlaying(titulo: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply { put("titulo", titulo); put("timestamp", System.currentTimeMillis()) }
                val client = OkHttpClient()
                client.newCall(Request.Builder().url(firebaseDbUrl).put(json.toString().toRequestBody("application/json".toMediaType())).build()).execute().close()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun iniciarLineIn() {
        stopMicrophone()
        micJob = serviceScope.launch {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.DEFAULT

            audioRecord = AudioRecord(source, sampleRate, channelConfig, audioFormat, minBufSize * 2)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return@launch

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            if (isRecording && outputStream == null) {
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                val fileName = "Grabacion_$timestamp.aac"
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "audio/aac")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/A Voz en Cuello")
                        }
                        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) outputStream = contentResolver.openOutputStream(uri)
                    } else {
                        val recordDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "A Voz en Cuello")
                        if (!recordDir.exists()) recordDir.mkdirs()
                        outputStream = FileOutputStream(File(recordDir, fileName))
                    }
                    LocalBroadcastManager.getInstance(this@StreamService).sendBroadcast(Intent("RECORDING_UPDATE").putExtra("IS_RECORDING", true))
                } catch (e: Exception) { e.printStackTrace() }
            }

            audioRecord?.startRecording()
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                while (isStreaming && isConnected && isLiveMicActive) {
                    val pcmBuffer = ByteArray(minBufSize)
                    val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                    if (read > 0) {
                        val inputIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
                        if (inputIndex >= 0) {
                            val inputBuffer = mediaCodec?.getInputBuffer(inputIndex)
                            inputBuffer?.clear(); inputBuffer?.put(pcmBuffer, 0, read)
                            mediaCodec?.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1000, 0)
                        }
                    }

                    var outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                    while (outputIndex >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val aacData = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset); outputBuffer.get(aacData)
                            val adtsData = addAdtsHeader(aacData, sampleRate, 2)
                            if (isStreaming && isConnected) shoutcastClient?.sendAudio(adtsData, adtsData.size)
                            if (isRecording) { try { outputStream?.write(adtsData) } catch (e: Exception) {} }
                        }
                        mediaCodec?.releaseOutputBuffer(outputIndex, false)
                        outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
                try { mediaCodec?.stop(); mediaCodec?.release() } catch (e: Exception) {}
                if (!isLiveMicActive) {
                    try { outputStream?.flush(); outputStream?.close(); outputStream = null } catch (e: Exception) {}
                    isRecording = false
                    LocalBroadcastManager.getInstance(this@StreamService).sendBroadcast(Intent("RECORDING_UPDATE").putExtra("IS_RECORDING", false))
                }
            }
        }
    }

    private fun stopMicrophone() {
        micJob?.cancel()
        micJob = null
    }

    private fun reproducirPlaylist(uris: List<Uri>) {
        var index = 0
        while (isStreaming && isConnected && uris.isNotEmpty()) {
            if (isLiveMicActive) {
                index++
                if (index >= uris.size) index = 0
                Thread.sleep(500)
                continue
            }

            val uriActual = uris[index]
            
            var nombreCancion = uriActual.lastPathSegment?.substringAfterLast("/")?.substringAfterLast(":") ?: "Canción"
            nombreCancion = nombreCancion.replace(Regex("\\.(mp3|aac|m4a|wav|ogg)$", RegexOption.IGNORE_CASE), "")
            nombreCancion = nombreCancion.replace(Regex("^[0-9\\s\\-_.]+"), "")
            nombreCancion = nombreCancion.replace("_", " ").trim()
            
            currentSong = nombreCancion
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("SONG_UPDATE").putExtra("SONG_NAME", currentSong))
            updateNotification("En Vivo", currentSong)
            actualizarNowPlaying(currentSong)

            val retriever = MediaMetadataRetriever()
            var trackDurationSeconds = 0L
            try {
                retriever.setDataSource(this, uriActual)
                trackDurationSeconds = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L) / 1000
            } catch (e: Exception) {
            } finally { retriever.release() }
            
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("TRACK_DURATION_UPDATE").putExtra("DURATION", trackDurationSeconds))
            enviarAudioNativo(uriActual)

            index++
            if (index >= uris.size) index = 0
        }
    }

    private fun enviarAudioNativo(uri: Uri) {
        val extractor = MediaExtractor()
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> extractor.setDataSource(pfd.fileDescriptor) }

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

            while (isStreaming && isConnected && !isLiveMicActive) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                var audioData = ByteArray(sampleSize)
                buffer.position(0); buffer.get(audioData)

                if (isAac) audioData = addAdtsHeader(audioData, sampleRate, channelCount)

                shoutcastClient?.sendAudio(audioData, audioData.size)
                bytesEnviadosTotales += audioData.size
                extractor.advance()

                val currentTrackSecond = bytesEnviadosTotales / 8000
                if (currentTrackSecond > lastBroadcastedSecond) {
                    lastBroadcastedSecond = currentTrackSecond
                    LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("TRACK_PROGRESS_UPDATE").putExtra("PROGRESS", currentTrackSecond))
                }

                val tiempoTranscurridoMs = System.currentTimeMillis() - tiempoInicio
                val tiempoQueDeberiaTardarMs = (bytesEnviadosTotales / 8000.0) * 1000.0
                val tiempoEspera = tiempoQueDeberiaTardarMs - tiempoTranscurridoMs
                if (tiempoEspera > 0) Thread.sleep(tiempoEspera.toLong())
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
        adts[0] = 0xFF.toByte(); adts[1] = 0xF1.toByte()
        adts[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        adts[3] = (((chanCfg and 3) shl 6) + (packetSize shr 11)).toByte()
        adts[4] = ((packetSize and 0x7FF) shr 3).toByte()
        adts[5] = (((packetSize and 7) shl 5) + 0x1F).toByte()
        adts[6] = 0xFC.toByte()
        return adts + aacData
    }

    override fun onDestroy() {
        isStreaming = false; isRecording = false; isLiveMicActive = false
        try { outputStream?.flush(); outputStream?.close() } catch (e: Exception) {}
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        try { mediaCodec?.stop(); mediaCodec?.release() } catch (e: Exception) {}
        shoutcastClient?.disconnect()
        serviceScope.cancel()
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("RECORDING_UPDATE").putExtra("IS_RECORDING", false))
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
        if (song.isNotEmpty()) builder.setContentText(song) else builder.setContentText(title)
        return builder.build()
    }

    private fun updateNotification(title: String, song: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, buildNotification(title, song))
    }
}
