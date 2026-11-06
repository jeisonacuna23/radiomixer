package com.turadio.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val Context.dataStore by preferencesDataStore(name = "settings")

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
    val scope = rememberCoroutineScope()

    var ip by remember { mutableStateOf("radio.conectarservidor.com") }
    var puerto by remember { mutableStateOf("8017") }
    var password by remember { mutableStateOf("") }
    
    var listaCanciones by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    var mensajeEstado by remember { mutableStateOf("No hay carpeta seleccionada") }
    var conectado by remember { mutableStateOf(false) }
    var cancionActual by remember { mutableStateOf("Esperando...") }

    // Cargar datos guardados
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        ip = prefs[stringPreferencesKey("IP")] ?: "radio.conectarservidor.com"
        puerto = prefs[stringPreferencesKey("PORT")] ?: "8017"
        password = prefs[stringPreferencesKey("PASS")] ?: ""
        
        val savedFolderUri = prefs[stringPreferencesKey("FOLDER_URI")]
        if (savedFolderUri != null) {
            val uri = Uri.parse(savedFolderUri)
            val root = DocumentFile.fromTreeUri(context, uri)
            val archivos = root?.listFiles()?.filter { file -> file.type?.startsWith("audio/") == true }
            listaCanciones = archivos?.map { Pair(it.uri, it.name ?: "Canción") } ?: emptyList()
            mensajeEstado = "Carpeta cargada: ${listaCanciones.size} canciones"
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                cancionActual = intent?.getStringExtra("SONG_NAME") ?: "Desconocida"
            }
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, IntentFilter("SONG_UPDATE"))
        onDispose { LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver) }
    }

    val selectorCarpeta = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            scope.launch {
                context.dataStore.edit { settings -> settings[stringPreferencesKey("FOLDER_URI")] = it.toString() }
            }
            val root = DocumentFile.fromTreeUri(context, it)
            val archivos = root?.listFiles()?.filter { file -> file.type?.startsWith("audio/") == true }
            listaCanciones = archivos?.map { file -> Pair(file.uri, file.name ?: "Canción") } ?: emptyList()
            mensajeEstado = "Canciones encontradas: ${listaCanciones.size}"
        }
    }

    fun moverArriba(index: Int) {
        if (index > 0) {
            val nuevaLista = listaCanciones.toMutableList()
            val item = nuevaLista.removeAt(index)
            nuevaLista.add(index - 1, item)
            listaCanciones = nuevaLista
        }
    }

    fun moverAbajo(index: Int) {
        if (index < listaCanciones.size - 1) {
            val nuevaLista = listaCanciones.toMutableList()
            val item = nuevaLista.removeAt(index)
            nuevaLista.add(index + 1, item)
            listaCanciones = nuevaLista
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("RadioMixer (Auto-DJ)", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("Servidor") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = puerto, onValueChange = { puerto = it }, label = { Text("Puerto (8017)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())

        Spacer(modifier = Modifier.height(10.dp))
        Text(text = mensajeEstado, style = MaterialTheme.typography.bodyMedium)
        
        if (conectado) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🔴 EN VIVO", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Sonando: $cancionActual", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Button(onClick = { selectorCarpeta.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Text("Seleccionar Carpeta de Música")
        }

        if (listaCanciones.isNotEmpty() && !conectado) {
            Text("Orden de reproducción:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(listaCanciones) { index, cancion ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = cancion.second,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            Row {
                                TextButton(onClick = { moverArriba(index) }, enabled = index > 0) {
                                    Text("⬆️", style = MaterialTheme.typography.titleLarge)
                                }
                                TextButton(onClick = { moverAbajo(index) }, enabled = index < listaCanciones.size - 1) {
                                    Text("⬇️", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { 
                if (listaCanciones.isEmpty()) {
                    mensajeEstado = "¡Selecciona música primero!"
                    return@Button
                }
                
                if (!conectado) {
                    scope.launch {
                        context.dataStore.edit { settings ->
                            settings[stringPreferencesKey("IP")] = ip
                            settings[stringPreferencesKey("PORT")] = puerto
                            settings[stringPreferencesKey("PASS")] = password
                        }
                        
                        val intent = Intent(context, StreamService::class.java).apply {
                            putExtra("IP", ip)
                            putExtra("PORT", puerto.toIntOrNull() ?: 8017)
                            putExtra("PASS", password)
                            putStringArrayListExtra("URIS", ArrayList(listaCanciones.map { it.first.toString() }))
                        }
                        context.startForegroundService(intent)
                        conectado = true
                        mensajeEstado = "Transmitiendo..."
                    }
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
