package com.turadio.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PantallaPrincipal()
                }
            }
        }
    }
}

@Composable
fun PantallaPrincipal() {
    val context = LocalContext.current
    var ip by remember { mutableStateOf("radio.conectarservidor.com") }
    var puerto by remember { mutableStateOf("8016") }
    var password by remember { mutableStateOf("") }
    var mountpoint by remember { mutableStateOf("/stream") } // Nuevo campo
    
    var listaCanciones by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var mensajeEstado by remember { mutableStateOf("No hay carpeta seleccionada") }
    var conectado by remember { mutableStateOf(false) }
    
    val shoutcast = remember { ShoutcastClient() }
    val encoder = remember { AacEncoder() }
    val scope = rememberCoroutineScope()

    val captureProcessor = remember { 
        CaptureAudioProcessor { pcm -> 
            if (conectado) encoder.encode(pcm) 
        } 
    }
    
    val renderersFactory = remember {
        object : DefaultRenderersFactory(context) {
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
    }

    val exoPlayer = remember { ExoPlayer.Builder(context, renderersFactory).build() }

    DisposableEffect(Unit) {
        onDispose { 
            exoPlayer.release()
            shoutcast.disconnect()
            encoder.stop()
        }
    }

    val selectorCarpeta = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val root = DocumentFile.fromTreeUri(context, it)
            val archivos = root?.listFiles()?.filter { file -> file.type?.startsWith("audio/") == true }
            listaCanciones = archivos?.map { file -> file.uri } ?: emptyList()
            mensajeEstado = "Canciones encontradas: ${listaCanciones.size}"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Configuración SonicPanel", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("Servidor") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = puerto, onValueChange = { puerto = it }, label = { Text("Puerto (8016)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = mountpoint, onValueChange = { mountpoint = it }, label = { Text("Mountpoint (/stream)") }, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(10.dp))
        Text(text = mensajeEstado, style = MaterialTheme.typography.bodyMedium)
        
        if (conectado) {
            Text("ESTADO: 🔴 EN VIVO", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
        }

        Button(onClick = { selectorCarpeta.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Text("Seleccionar Carpeta de Música")
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { 
                if (listaCanciones.isEmpty()) {
                    mensajeEstado = "¡Selecciona música primero!"
                    return@Button
                }
                
                if (!conectado) {
                    mensajeEstado = "Conectando..."
                    scope.launch {
                        val resultado = withContext(Dispatchers.IO) {
                            shoutcast.connect(ip, puerto.toIntOrNull() ?: 8016, password, mountpoint)
                        }
                        
                        if (resultado == "OK") {
                            conectado = true
                            mensajeEstado = "¡Conectado! Transmitiendo a $mountpoint"
                            
                            encoder.start { aacBytes ->
                                scope.launch(Dispatchers.IO) {
                                    shoutcast.sendAudio(aacBytes)
                                }
                            }
                            
                            exoPlayer.setMediaItem(MediaItem.fromUri(listaCanciones.first()))
                            exoPlayer.prepare()
                            exoPlayer.play()
                        } else {
                            mensajeEstado = resultado
                        }
                    }
                } else {
                    conectado = false
                    mensajeEstado = "Transmisión detenida"
                    exoPlayer.stop()
                    encoder.stop()
                    scope.launch(Dispatchers.IO) { shoutcast.disconnect() }
                }
            },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (conectado) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (conectado) "DETENER TRANSMISIÓN" else "INICIAR TRANSMISIÓN")
        }
    }
}
