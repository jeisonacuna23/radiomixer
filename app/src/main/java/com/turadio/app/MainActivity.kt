package com.turadio.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.FragmentActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

val Context.dataStore by preferencesDataStore(name = "settings")

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val scope = rememberCoroutineScope()

    var isLocked by remember { mutableStateOf(true) }
    var savedPin by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        savedPin = prefs[stringPreferencesKey("APP_PIN")]
        isLoading = false
        if (savedPin == null) isLocked = false
    }

    LaunchedEffect(isLocked, savedPin) {
        if (isLocked && savedPin != null) {
            val biometricManager = BiometricManager.from(context)
            if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
                val executor = ContextCompat.getMainExecutor(context)
                val biometricPrompt = BiometricPrompt(activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            isLocked = false
                        }
                    })
                val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("Desbloquear RadioMixer").setNegativeButtonText("Usar PIN").build()
                biometricPrompt.authenticate(promptInfo)
            }
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (isLocked && savedPin != null) {
        LockScreen(savedPin = savedPin!!) { isLocked = false }
    } else {
        PantallaPrincipal(savedPin = savedPin, onPinSet = { newPin ->
            scope.launch {
                context.dataStore.edit { it[stringPreferencesKey("APP_PIN")] = newPin }
                savedPin = newPin
            }
        })
    }
}

@Composable
fun LockScreen(savedPin: String, onUnlock: () -> Unit) {
    var pinInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, contentDescription = "Bloqueado", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Ingrese su PIN", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = pinInput, onValueChange = { if (it.length <= 4) pinInput = it; error = false },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = error, label = { Text("PIN de 4 dígitos") }, modifier = Modifier.fillMaxWidth()
        )
        if (error) Text("PIN incorrecto", color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { if (pinInput == savedPin) onUnlock() else error = true }, modifier = Modifier.fillMaxWidth()) { Text("Desbloquear") }
    }
}

fun formatTime(seconds: Long): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

@Composable
fun PantallaPrincipal(savedPin: String?, onPinSet: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ip by remember { mutableStateOf("radio.conectarservidor.com") }
    var puerto by remember { mutableStateOf("8017") }
    var password by remember { mutableStateOf("") }
    var minutosAutoStop by remember { mutableStateOf("") }
    var nombrePrograma by remember { mutableStateOf("Programa en Vivo") }
    
    var modoMezcladora by remember { mutableStateOf(false) }
    var grabarMezcladora by remember { mutableStateOf(false) }
    var isRecordingActive by remember { mutableStateOf(false) }

    var todosLosArchivos by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    var listaReproduccion by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    
    var mensajeEstado by remember { mutableStateOf("No hay carpeta seleccionada") }
    var conectado by remember { mutableStateOf(false) }
    var cancionActual by remember { mutableStateOf("Esperando...") }
    var oyentes by remember { mutableIntStateOf(0) }

    // ESTADOS EN VIVO
    var isLiveMicUi by remember { mutableStateOf(false) }
    var liveProgramName by remember { mutableStateOf("Programa en Vivo") }

    var startTime by remember { mutableLongStateOf(0L) }
    var timeoutMinutes by remember { mutableIntStateOf(0) }
    var tiempoAlAire by remember { mutableStateOf("00:00") }
    var tiempoRestante by remember { mutableStateOf("--:--") }
    var trackElapsed by remember { mutableLongStateOf(0L) }
    var trackDuration by remember { mutableLongStateOf(0L) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAutoStopDialog by remember { mutableStateOf(false) }
    var tempMinutes by remember { mutableStateOf("") }
    var pinSetup by remember { mutableStateOf("") }
    var showPinSetup by remember { mutableStateOf(savedPin == null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) { }

    val permissionLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            cancionActual = nombrePrograma
            trackElapsed = 0L; trackDuration = 0L
            isLiveMicUi = true // Arrancó en mezcladora
            liveProgramName = nombrePrograma
            val intent = Intent(context, StreamService::class.java).apply {
                putExtra("IP", ip); putExtra("PORT", puerto.toIntOrNull() ?: 8017); putExtra("PASS", password)
                putExtra("TIMEOUT_MINUTES", minutosAutoStop.toIntOrNull() ?: 0)
                putExtra("LINE_IN_MODE", true); putExtra("RECORD_STREAM", grabarMezcladora)
                putExtra("PROGRAM_NAME", nombrePrograma)
            }
            context.startForegroundService(intent)
            conectado = true
            mensajeEstado = "Transmitiendo Entrada de Línea..."
        } else { mensajeEstado = "Permiso de micrófono denegado" }
    }

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
            todosLosArchivos = archivos?.map { Pair(it.uri, it.name ?: "Canción") } ?: emptyList()
            
            val savedUris = prefs[stringSetPreferencesKey("PLAYLIST_URIS")] ?: emptySet()
            if (savedUris.isNotEmpty()) listaReproduccion = todosLosArchivos.filter { savedUris.contains(it.first.toString()) }
            else listaReproduccion = todosLosArchivos
            mensajeEstado = "Carpeta cargada: ${todosLosArchivos.size} canciones"
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(conectado, startTime, timeoutMinutes) {
        if (conectado && startTime > 0) {
            while (true) {
                val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                tiempoAlAire = formatTime(elapsedSeconds)
                if (timeoutMinutes > 0) {
                    val remaining = (timeoutMinutes * 60L) - elapsedSeconds
                    if (remaining > 0) tiempoRestante = formatTime(remaining) else { tiempoRestante = "00:00"; conectado = false }
                } else tiempoRestante = "Ilimitado"
                delay(1000L)
            }
        }
    }

    LaunchedEffect(conectado) {
        if (conectado) {
            while (true) {
                try {
                    val client = OkHttpClient()
                    val request = Request.Builder().url("http://radio.conectarservidor.com:8016/stats?sid=1&json=1").build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body?.string() ?: "{}")
                            if (json.has("currentlisteners")) oyentes = json.getInt("currentlisteners")
                        }
                    }
                } catch (e: Exception) { }
                delay(20000L)
            }
        } else oyentes = 0
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    "SONG_UPDATE" -> { cancionActual = intent.getStringExtra("SONG_NAME") ?: "Desconocida"; trackElapsed = 0L; trackDuration = 0L }
                    "TIME_UPDATE" -> { startTime = intent.getLongExtra("START_TIME", 0L); timeoutMinutes = intent.getIntExtra("TIMEOUT", 0) }
                    "TRACK_DURATION_UPDATE" -> trackDuration = intent.getLongExtra("DURATION", 0L)
                    "TRACK_PROGRESS_UPDATE" -> trackElapsed = intent.getLongExtra("PROGRESS", 0L)
                    "RECORDING_UPDATE" -> isRecordingActive = intent.getBooleanExtra("IS_RECORDING", false)
                }
            }
        }
        val filter = IntentFilter().apply { addAction("SONG_UPDATE"); addAction("TIME_UPDATE"); addAction("TRACK_DURATION_UPDATE"); addAction("TRACK_PROGRESS_UPDATE"); addAction("RECORDING_UPDATE") }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)
        onDispose { LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver) }
    }

    val selectorCarpeta = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            scope.launch { context.dataStore.edit { settings -> settings[stringPreferencesKey("FOLDER_URI")] = it.toString() } }
            val root = DocumentFile.fromTreeUri(context, it)
            val archivos = root?.listFiles()?.filter { file -> file.type?.startsWith("audio/") == true }
            todosLosArchivos = archivos?.map { file -> Pair(file.uri, file.name ?: "Canción") } ?: emptyList()
            listaReproduccion = todosLosArchivos
            mensajeEstado = "Canciones encontradas: ${todosLosArchivos.size}"
        }
    }

    fun toggleSeleccion(cancion: Pair<Uri, String>) {
        if (listaReproduccion.contains(cancion)) listaReproduccion -= cancion else listaReproduccion += cancion
        scope.launch { context.dataStore.edit { settings -> settings[stringSetPreferencesKey("PLAYLIST_URIS")] = listaReproduccion.map { it.first.toString() }.toSet() } }
    }

    fun moverArriba(index: Int) {
        if (index > 0) {
            val nuevaLista = listaReproduccion.toMutableList()
            nuevaLista.add(index - 1, nuevaLista.removeAt(index))
            listaReproduccion = nuevaLista
        }
    }

    fun moverAbajo(index: Int) {
        if (index < listaReproduccion.size - 1) {
            val nuevaLista = listaReproduccion.toMutableList()
            nuevaLista.add(index + 1, nuevaLista.removeAt(index))
            listaReproduccion = nuevaLista
        }
    }

    fun enviarNotificacionFirebase() {
        scope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.resources.openRawResource(com.turadio.app.R.raw.service_account)
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                val projectId = JSONObject(jsonString).getString("project_id")
                val credentials = GoogleCredentials.fromStream(jsonString.byteInputStream()).createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging"))
                credentials.refreshIfExpired()
                val url = "https://fcm.googleapis.com/v1/projects/$projectId/messages:send"
                val jsonBody = """{"message": {"topic": "radio_en_vivo","notification": {"title": "¡A Voz en Cuello está en vivo!","body": "La transmisión ha comenzado. ¡Entra a escuchar!"}}}"""
                val client = OkHttpClient()
                val request = Request.Builder().url(url).addHeader("Authorization", "Bearer ${credentials.accessToken.tokenValue}").post(jsonBody.toRequestBody("application/json".toMediaType())).build()
                client.newCall(request).execute().use { }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // MODALES
    if (showPinSetup) {
        AlertDialog(onDismissRequest = { }, title = { Text("Configurar Seguridad") },
            text = {
                Column {
                    Text("Cree un PIN de 4 dígitos para proteger la app.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = pinSetup, onValueChange = { if (it.length <= 4) pinSetup = it }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), label = { Text("Nuevo PIN") })
                }
            },
            confirmButton = { TextButton(onClick = { if (pinSetup.length == 4) { onPinSet(pinSetup); showPinSetup = false } }) { Text("Guardar") } }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(onDismissRequest = { showSettingsDialog = false }, title = { Text("Ajustes de Red") },
            text = {
                Column {
                    OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("Servidor") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = puerto, onValueChange = { puerto = it }, label = { Text("Puerto") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Cerrar") } }
        )
    }

    if (showAutoStopDialog) {
        AlertDialog(onDismissRequest = { showAutoStopDialog = false }, title = { Text("Temporizador Auto-Stop") },
            text = {
                Column {
                    Text("¿En cuántos minutos quieres que se detenga la transmisión?")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = tempMinutes, onValueChange = { if (it.all { char -> char.isDigit() }) tempMinutes = it }, label = { Text("Minutos (0 = Desactivado)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { minutosAutoStop = tempMinutes; showAutoStopDialog = false }) { Text("Aceptar") } },
            dismissButton = { TextButton(onClick = { showAutoStopDialog = false }) { Text("Cancelar") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("RadioMixer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { tempMinutes = minutosAutoStop; showAutoStopDialog = true }) {
                Icon(Icons.Default.Timer, contentDescription = "Auto-Stop", tint = if (minutosAutoStop.isNotEmpty() && minutosAutoStop != "0") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Default.Settings, contentDescription = "Ajustes", tint = MaterialTheme.colorScheme.primary) }
        }

        if (!conectado) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { modoMezcladora = false }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (!modoMezcladora) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) {
                    Icon(Icons.Default.MusicNote, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text("Auto-DJ")
                }
                Button(onClick = { modoMezcladora = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (modoMezcladora) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) {
                    Icon(Icons.Default.SettingsInputComponent, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text("Mezcladora")
                }
            }
        }

        Text(text = mensajeEstado, style = MaterialTheme.typography.bodyMedium)
        
        if (conectado) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "En vivo", tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("EN VIVO", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                        
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.People, contentDescription = "Oyentes", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("$oyentes", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                        if (isRecordingActive) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Default.FiberManualRecord, contentDescription = "Grabando", tint = Color.Red, modifier = Modifier.size(16.dp))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    // SWITCH EN VIVO AQUI
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Switch(
                            checked = isLiveMicUi, 
                            onCheckedChange = { isChecked ->
                                isLiveMicUi = isChecked
                                if (isChecked) {
                                    // Pedir permiso de micrófono si no lo tiene
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        val intent = Intent(context, StreamService::class.java).apply {
                                            action = "TOGGLE_LIVE_MIC"
                                            putExtra("IS_LIVE_MIC", true)
                                            putExtra("PROGRAM_NAME", liveProgramName)
                                        }
                                        context.startService(intent)
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        isLiveMicUi = false // Revertir si no da permiso
                                    }
                                } else {
                                    val intent = Intent(context, StreamService::class.java).apply {
                                        action = "TOGGLE_LIVE_MIC"
                                        putExtra("IS_LIVE_MIC", false)
                                    }
                                    context.startService(intent)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isLiveMicUi) "Modo Mezcladora (En vivo)" else "Modo Auto-DJ", style = MaterialTheme.typography.bodyMedium)
                    }

                    // CAMPO DE TEXTO PARA EL PROGRAMA (solo si el switch está activo)
                    if (isLiveMicUi) {
                        OutlinedTextField(
                            value = liveProgramName,
                            onValueChange = { 
                                liveProgramName = it
                                // Enviar el texto actualizado al servicio
                                val intent = Intent(context, StreamService::class.java).apply {
                                    action = "TOGGLE_LIVE_MIC"
                                    putExtra("IS_LIVE_MIC", true)
                                    putExtra("PROGRAM_NAME", liveProgramName)
                                }
                                context.startService(intent)
                            },
                            label = { Text("Nombre del programa (Firebase)") },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Sonando: $cancionActual", style = MaterialTheme.typography.bodyLarge)
                    
                    if (!isLiveMicUi) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (trackDuration > 0) {
                            LinearProgressIndicator(progress = { trackElapsed.toFloat() / trackDuration.toFloat() }, modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(trackElapsed), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                                Text(formatTime(trackDuration), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            }
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Tiempo al aire:", style = MaterialTheme.typography.labelMedium)
                            Text(tiempoAlAire, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Restante Auto-Stop:", style = MaterialTheme.typography.labelMedium)
                            Text(tiempoRestante, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        if (!conectado) {
            if (!modoMezcladora) {
                Button(onClick = { selectorCarpeta.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Folder, contentDescription = "Carpeta"); Spacer(modifier = Modifier.width(8.dp)); Text("Seleccionar Carpeta de Música")
                }

                if (todosLosArchivos.isNotEmpty()) {
                    Text("Catálogo de Música (Selecciona los que sonarán):", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(todosLosArchivos) { cancion ->
                            val isSelected = listaReproduccion.contains(cancion)
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = isSelected, onCheckedChange = { toggleSeleccion(cancion) })
                                    Text(text = cancion.second, modifier = Modifier.weight(1f), maxLines = 1)
                                }
                            }
                        }
                    }
                }

                if (listaReproduccion.isNotEmpty()) {
                    Text("Lista de Reproducción (En orden):", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(modifier = Modifier.heightIn(max = 250.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(listaReproduccion) { index, cancion ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(text = cancion.second, modifier = Modifier.weight(1f), maxLines = 1)
                                    Row {
                                        IconButton(onClick = { moverArriba(index) }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir", tint = if (index > 0) MaterialTheme.colorScheme.primary else Color.Gray) }
                                        IconButton(onClick = { moverAbajo(index) }, enabled = index < listaReproduccion.size - 1) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Bajar", tint = if (index < listaReproduccion.size - 1) MaterialTheme.colorScheme.primary else Color.Gray) }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SettingsInputComponent, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Modo Mezcladora / Line-In", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Conecta tu mezcladora al puerto de audio. La app transmitirá todo el sonido que entre por el cable.", textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(value = nombrePrograma, onValueChange = { nombrePrograma = it }, label = { Text("Nombre del programa") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Switch(checked = grabarMezcladora, onCheckedChange = { grabarMezcladora = it })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Grabar transmisión localmente", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(onClick = { 
            if (!conectado) {
                enviarNotificacionFirebase()
                if (modoMezcladora) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        cancionActual = nombrePrograma; trackElapsed = 0L; trackDuration = 0L
                        isLiveMicUi = true
                        liveProgramName = nombrePrograma
                        val intent = Intent(context, StreamService::class.java).apply {
                            putExtra("IP", ip); putExtra("PORT", puerto.toIntOrNull() ?: 8017); putExtra("PASS", password)
                            putExtra("TIMEOUT_MINUTES", minutosAutoStop.toIntOrNull() ?: 0)
                            putExtra("LINE_IN_MODE", true); putExtra("RECORD_STREAM", grabarMezcladora)
                            putExtra("PROGRAM_NAME", nombrePrograma)
                        }
                        context.startForegroundService(intent); conectado = true; mensajeEstado = "Transmitiendo Entrada de Línea..."
                    } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    if (listaReproduccion.isEmpty()) { mensajeEstado = "Selecciona al menos una canción"; return@Button }
                    scope.launch {
                        context.dataStore.edit { settings ->
                            settings[stringPreferencesKey("IP")] = ip
                            settings[stringPreferencesKey("PORT")] = puerto
                            settings[stringPreferencesKey("PASS")] = password
                        }
                        isLiveMicUi = false
                        val intent = Intent(context, StreamService::class.java).apply {
                            putExtra("IP", ip); putExtra("PORT", puerto.toIntOrNull() ?: 8017); putExtra("PASS", password)
                            putExtra("TIMEOUT_MINUTES", minutosAutoStop.toIntOrNull() ?: 0)
                            putExtra("LINE_IN_MODE", false)
                            putStringArrayListExtra("URIS", ArrayList(listaReproduccion.map { it.first.toString() }))
                        }
                        context.startForegroundService(intent); conectado = true; mensajeEstado = "Transmitiendo Auto-DJ..."
                    }
                }
            } else {
                isRecordingActive = false
                val intent = Intent(context, StreamService::class.java).apply { action = "STOP" }
                context.startService(intent); conectado = false; mensajeEstado = "Transmisión detenida."
            }
        }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = if (conectado) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
            Icon(if (conectado) Icons.Default.Close else Icons.Default.PlayArrow, contentDescription = "Play/Stop")
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (conectado) "DETENER TRANSMISIÓN" else "INICIAR TRANSMISIÓN")
        }
    }
}
