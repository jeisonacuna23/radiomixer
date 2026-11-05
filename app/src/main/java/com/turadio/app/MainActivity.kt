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
    var puerto by remember { mutableStateOf("8017") } // Puerto correcto
    var password by remember { mutableStateOf("") }
    
    var listaCanciones by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var mensajeEstado by remember { mutableStateOf("No hay carpeta seleccionada") }
    var conectado by remember { mutableStateOf(false) }

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
        OutlinedTextField(value = puerto, onValueChange = { puerto = it }, label = { Text("Puerto (8017)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña (sin usuario)") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())

        Spacer(modifier = Modifier.height(10.dp))
        Text(text = mensajeEstado, style = MaterialTheme.typography.bodyMedium)
        
        if (conectado) {
            Text("ESTADO: 🔴 EN VIVO (Servicio en 2do plano)", color = MaterialTheme.colorScheme.error)
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
                    val intent = Intent(context, StreamService::class.java).apply {
                        putExtra("IP", ip)
                        putExtra("PORT", puerto.toIntOrNull() ?: 8017)
                        putExtra("PASS", password)
                        putExtra("URI", listaCanciones.first().toString())
                    }
                    context.startForegroundService(intent)
                    conectado = true
                    mensajeEstado = "Servicio iniciado. Revisa SonicPanel."
                } else {
                    val intent = Intent(context, StreamService::class.java).apply {
                        action = "STOP"
                    }
                    context.startService(intent)
                    conectado = false
                    mensajeEstado = "Transmisión detenida."
                }
            },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (conectado) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        ) {
            Text(if (conectado) "DETENER TRANSMISIÓN" else "INICIAR TRANSMISIÓN")
        }
    }
}
