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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

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
    var usuario by remember { mutableStateOf("jeisonacuna2323") }
    var password by remember { mutableStateOf("") }
    
    var listaCanciones by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var mensajeEstado by remember { mutableStateOf("No hay carpeta seleccionada") }
    var conectado by remember { mutableStateOf(false) }
    var sessionId by remember { mutableStateOf<Long?>(null) }

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
        Text("Configuración SonicPanel (FFmpeg)", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("Servidor") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = puerto, onValueChange = { puerto = it }, label = { Text("Puerto") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = usuario, onValueChange = { usuario = it }, label = { Text("Usuario (Panel)") }, modifier = Modifier.fillMaxWidth())
        
        OutlinedTextField(
            value = password, 
            onValueChange = { password = it }, 
            label = { Text("Contraseña") }, 
            modifier = Modifier.fillMaxWidth(), 
            visualTransformation = PasswordVisualTransformation()
        )

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
                    conectado = true
                    mensajeEstado = "Conectando con FFmpeg..."
                    
                    // FFmpeg necesita una ruta de archivo real, no un content:// de Android. 
                    // Por eso copiamos la canción a la memoria temporal de la app.
                    val uriActual = listaCanciones.first()
                    val inputPath = copiarArchivoTemporal(context, uriActual)
                    
                    if (inputPath == null) {
                        mensajeEstado = "Error: No se pudo leer el archivo de música"
                        conectado = false
                        return@Button
                    }

                    // Comando FFmpeg: -re (velocidad real), libmp3lame (MP3), 64k, a Icecast/Shoutcast
                    val cmd = "-re -i \"$inputPath\" -c:a libmp3lame -b:a 64k -f mp3 icecast://$usuario:$password@$ip:$puerto/"
                    
                    val session = FFmpegKit.executeAsync(cmd) { session ->
                        // Cuando el proceso termina (o falla)
                        if (ReturnCode.isSuccess(session.returnCode)) {
                            mensajeEstado = "Transmisión finalizada."
                        } else {
                            mensajeEstado = "FFmpeg se detuvo: ${session.failStackTrace?.take(150)}..."
                        }
                        conectado = false
                    }
                    sessionId = session.sessionId
                    mensajeEstado = "¡Conectando a SonicPanel!"
                    
                } else {
                    // Detener transmisión
                    sessionId?.let { FFmpegKit.cancel(it) }
                    conectado = false
                    mensajeEstado = "Transmisión detenida por el usuario"
                }
            },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (conectado) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        ) {
            Text(if (conectado) "DETENER TRANSMISIÓN" else "INICIAR TRANSMISIÓN")
        }
    }
}

// Función auxiliar para copiar el archivo y que FFmpeg lo pueda leer
fun copiarArchivoTemporal(context: android.content.Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val tempFile = java.io.File(context.cacheDir, "cancion_temporal.mp3")
        tempFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        tempFile.absolutePath
    } catch (e: Exception) {
        null
    }
}
