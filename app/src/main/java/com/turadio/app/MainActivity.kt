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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
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

val DarkPlum = Color(0xFF4A0E4E)
val LightPlum = Color(0xFF7B2C7E)
val AccentPink = Color(0xFFD65DB1)
val BgLight = Color(0xFFFDF7FF)
val TextDark = Color(0xFF2C082F)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = DarkPlum, onPrimary = Color.White, primaryContainer = LightPlum, onPrimaryContainer = Color.White,
                    secondary = AccentPink, onSecondary = Color.White, background = BgLight, onBackground = TextDark,
                    surface = Color.White, onSurface = TextDark
                )
            ) {
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
    val auth = remember { Firebase.auth }

    var isLocked by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var currentUserEmail by remember { mutableStateOf("") }
    var lastEmailUsed by remember { mutableStateOf("") } // RECUERDA EL CORREO

    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        lastEmailUsed = prefs[stringPreferencesKey("LAST_EMAIL")] ?: ""

        val currentUser = auth.currentUser
        if (currentUser != null) {
            currentUserEmail = currentUser.email ?: ""
            isLocked = true
        } else {
            isLocked = false
        }
        isLoading = false
    }

    fun authenticateWithBiometrics() {
        if (isLocked) {
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
                val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("Desbloquear RadioMixer").setNegativeButtonText("Usar Contraseña").build()
                biometricPrompt.authenticate(promptInfo)
            }
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (!isLocked && auth.currentUser == null) {
        LoginScreen(
            auth = auth, 
            initialEmail = lastEmailUsed, 
            onLoginSuccess = { email ->
                currentUserEmail = email
                lastEmailUsed = email
                isLocked = false
                scope.launch { context.dataStore.edit { it[stringPreferencesKey("LAST_EMAIL")] = email } }
            }
        )
    } else if (isLocked && auth.currentUser != null) {
        LockScreen(email = currentUserEmail, onUnlock = { isLocked = false }, onBiometricClick = { authenticateWithBiometrics() }, onLogout = {
            auth.signOut()
            isLocked = false
            currentUserEmail = ""
        })
    } else {
        PantallaPrincipal(
            savedUser = currentUserEmail,
            onExit = { 
                auth.signOut()
                activity.finishAffinity() 
            }
        )
    }
}

@Composable
fun LoginScreen(auth: FirebaseAuth, initialEmail: String, onLoginSuccess: (String) -> Unit) {
    var email by remember { mutableStateOf(initialEmail) } // SE PRECARGA EL CORREO
    var password by remember { mutableStateOf("") }
    var isRegistering by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painter = painterResource(id = R.drawable.logo_new), contentDescription = "Logo", modifier = Modifier.size(120.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(if (isRegistering) "Crear Cuenta" else "Iniciar Sesión", style = MaterialTheme.typography.titleLarge, color = DarkPlum)
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Correo Electrónico") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        
        if (errorMsg.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(errorMsg, color = Color.Red, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = {
            if (email.isBlank() || password.isBlank()) {
                errorMsg = "Por favor, llene todos los campos."
                return@Button
            }
            isLoading = true
            if (isRegistering) {
                auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener { task ->
                    isLoading = false
                    if (task.isSuccessful) onLoginSuccess(email) else errorMsg = task.exception?.message ?: "Error al registrar"
                }
            } else {
                auth.signInWithEmailAndPassword(email, password).addOnCompleteListener { task ->
                    isLoading = false
                    if (task.isSuccessful) onLoginSuccess(email) else errorMsg = task.exception?.message ?: "Error al iniciar sesión"
                }
            }
        }, modifier = Modifier.fillMaxWidth().height(50.dp), enabled = !isLoading) {
            if (isLoading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            else Text(if (isRegistering) "Registrarse" else "Ingresar")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { isRegistering = !isRegistering; errorMsg = "" }) {
            Text(if (isRegistering) "¿Ya tienes cuenta? Inicia sesión" else "¿No tienes cuenta? Regístrate", color = LightPlum)
        }
    }
}

@Composable
fun LockScreen(email: String, onUnlock: () -> Unit, onBiometricClick: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val biometricManager = BiometricManager.from(context)
    val canUseBiometric = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painter = painterResource(id = R.drawable.logo_new), contentDescription = "Logo A Voz en Cuello", modifier = Modifier.size(160.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("Bienvenido", style = MaterialTheme.typography.titleLarge, color = DarkPlum)
        Text(email, style = MaterialTheme.typography.bodyMedium, color = Color.Gray) // MUESTRA EL CORREO
        Spacer(modifier = Modifier.height(32.dp))
        
        if (canUseBiometric) {
            Button(onClick = onBiometricClick, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Default.Fingerprint, contentDescription = "Huella"); Spacer(modifier = Modifier.width(8.dp)); Text("Entrar con Huella")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)) {
            Text("Cerrar Sesión de Firebase")
        }
    }
}

fun formatTime(seconds: Long): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

@Composable
fun PantallaPrincipal(savedUser: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ip by remember { mutableStateOf("radio.conectarservidor.com") }
    var puerto by remember { mutableStateOf("8017") }
    var password by remember { mutableStateOf("") }
    var minutosAutoStop by remember { mutableStateOf("") }
    var nombrePrograma by remember { mutableStateOf("Programa en Vivo") }
    
    var modoMezcladora by remember { mutableStateOf(false) }
    var isRecordingActive by remember { mutableStateOf(false) }

    var todosLosArchivos by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    var listaReproduccion by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    
    var mensajeEstado by remember { mutableStateOf("No hay carpeta seleccionada") }
    var conectado by remember { mutableStateOf(false) }
    var cancionActual by remember { mutableStateOf("Esperando...") }
    var oyentes by remember { mutableIntStateOf(0) }

    var isLiveMicUi by remember { mutableStateOf(false) }
    var liveProgramName by remember { mutableStateOf("Programa en Vivo") }
    var pendingMicActivation by remember { mutableStateOf(false) }

    var startTime by remember { mutableLongStateOf(0L) }
    var timeoutMinutes by remember { mutableIntStateOf(0) }
    var tiempoAlAire by remember { mutableStateOf("00:00") }
    var tiempoRestante by remember { mutableStateOf("--:--") }
    var trackElapsed by remember { mutableLongStateOf(0L) }
    var trackDuration by remember { mutableLongStateOf(0L) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAutoStopDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }
    var tempMinutes by remember { mutableStateOf("") }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) { }

    val micPermissionLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted && pendingMicActivation) {
            val intent = Intent(context, StreamService::class.java).apply {
                action = "TOGGLE_LIVE_MIC"
                putExtra("IS_LIVE_MIC", true)
                putExtra("PROGRAM_NAME", liveProgramName)
            }
            context.startService(intent)
            isLiveMicUi = true
            pendingMicActivation = false
        } else {
            isLiveMicUi = false
            pendingMicActivation = false
            mensajeEstado = "Permiso de micrófono denegado"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            cancionActual = nombrePrograma
            trackElapsed = 0L; trackDuration = 0L
            isLiveMicUi = true
            liveProgramName = nombrePrograma
            val intent = Intent(context, StreamService::class.java).apply {
                putExtra("IP", ip); putExtra("PORT", puerto.toIntOrNull() ?: 8017); putExtra("PASS", password)
                putExtra("TIMEOUT_MINUTES", minutosAutoStop.toIntOrNull() ?: 0)
                putExtra("LINE_IN_MODE", true)
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
                    "ERROR_UPDATE" -> {
                        mensajeEstado = "Error: ${intent.getStringExtra("ERROR_MSG")}"
                        conectado = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply { 
            addAction("SONG_UPDATE"); addAction("TIME_UPDATE"); addAction("TRACK_DURATION_UPDATE"); 
            addAction("TRACK_PROGRESS_UPDATE"); addAction("RECORDING_UPDATE"); addAction("ERROR_UPDATE") 
        }
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

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Detener Transmisión") },
            text = { Text("¿Estás seguro de que quieres detener la transmisión? Se avisará a los oyentes.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    isRecordingActive = false
                    val intent = Intent(context, StreamService::class.java).apply { 
                        action = "STOP"
                        putExtra("NOTIFY", true) 
                    }
                    context.startService(intent)
                    conectado = false
                    mensajeEstado = "Transmisión detenida."
                }) { Text("Detener", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showStopDialog = false }) { Text("Cancelar") } }
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Cerrar Sesión") },
            text = { Text("¿Estás seguro de que quieres salir? Se detendrá todo, se cerrará la app y se cerrará tu sesión.") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    val intent = Intent(context, StreamService::class.java).apply { 
                        action = "STOP"
                        putExtra("NOTIFY", false) 
                    }
                    context.startService(intent)
                    context.stopService(Intent(context, StreamService::class.java))
                    onExit()
                }) { Text("Salir", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showExitDialog = false }) { Text("Cancelar") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(id = R.drawable.logo_new), contentDescription = "Logo", modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(savedUser, style = MaterialTheme.typography.titleLarge, color = DarkPlum, modifier = Modifier.weight(1f), maxLines = 1)
            
            IconButton(onClick = { tempMinutes = minutosAutoStop; showAutoStopDialog = true }) {
                Icon(Icons.Default.Timer, contentDescription = "Auto-Stop", tint = if (minutosAutoStop.isNotEmpty() && minutosAutoStop != "0") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Default.Settings, contentDescription = "Ajustes", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { showExitDialog = true }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Cerrar Sesión", tint = MaterialTheme.colorScheme.error) }
        }

        if (!conectado) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { modoMezcladora = false }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (!modoMezcladora) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (!modoMezcladora) Color.White else Color.Gray)) {
                    Icon(Icons.Default.MusicNote, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text("Auto-DJ")
                }
                Button(onClick = { modoMezcladora = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (modoMezcladora) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (modoMezcladora) Color.White else Color.Gray)) {
                    Icon(Icons.Default.SettingsInputComponent, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text("Mezcladora")
                }
            }
        }

        Text(text = mensajeEstado, style = MaterialTheme.typography.bodyMedium, color = if (mensajeEstado.contains("Error")) Color.Red else TextDark)
        
        if (conectado) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "En vivo", tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("EN VIVO", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                        
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.People, contentDescription = "Oyentes", modifier = Modifier.size(16.dp), tint = DarkPlum)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("$oyentes", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = DarkPlum)
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Switch(
                            checked = isLiveMicUi, 
                            onCheckedChange = { isChecked ->
                                if (isChecked) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        val intent = Intent(context, StreamService::class.java).apply {
                                            action = "TOGGLE_LIVE_MIC"
                                            putExtra("IS_LIVE_MIC", true)
                                            putExtra("PROGRAM_NAME", liveProgramName)
                                        }
                                        context.startService(intent)
                                        isLiveMicUi = true
                                    } else {
                                        pendingMicActivation = true
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                } else {
                                    val intent = Intent(context, StreamService::class.java).apply {
                                        action = "TOGGLE_LIVE_MIC"
                                        putExtra("IS_LIVE_MIC", false)
                                    }
                                    context.startService(intent)
                                    isLiveMicUi = false
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = DarkPlum)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isLiveMicUi) "Modo Mezcladora (En vivo)" else "Modo Auto-DJ", style = MaterialTheme.typography.bodyMedium)
                    }

                    if (isLiveMicUi) {
                        OutlinedTextField(
                            value = liveProgramName,
                            onValueChange = { 
                                liveProgramName = it
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
                        
                        Button(
                            onClick = {
                                val nuevoEstado = !isRecordingActive
                                val intent = Intent(context, StreamService::class.java).apply {
                                    action = "TOGGLE_RECORDING"
                                    putExtra("RECORD_STATE", nuevoEstado)
                                }
                                context.startService(intent)
                                isRecordingActive = nuevoEstado
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecordingActive) Color.Red else MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Icon(if (isRecordingActive) Icons.Default.Stop else Icons.Default.FiberManualRecord, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRecordingActive) "DETENER GRABACIÓN" else "GRABAR TRANSMISIÓN")
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.LightGray)
                    Text("Sonando: $cancionActual", style = MaterialTheme.typography.bodyLarge, color = TextDark)
                    
                    if (!isLiveMicUi) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (trackDuration > 0) {
                            LinearProgressIndicator(progress = { trackElapsed.toFloat() / trackDuration.toFloat() }, modifier = Modifier.fillMaxWidth(), color = DarkPlum)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(trackElapsed), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                                Text(formatTime(trackDuration), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            }
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DarkPlum)
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.LightGray)
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Tiempo al aire:", style = MaterialTheme.typography.labelMedium)
                            Text(tiempoAlAire, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = TextDark)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Restante Auto-Stop:", style = MaterialTheme.typography.labelMedium)
                            Text(tiempoRestante, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = DarkPlum)
                        }
                    }
                }
            }
        }

        if (!modoMezcladora) {
            if (!conectado) {
                Button(onClick = { selectorCarpeta.launch(null) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = LightPlum)) {
                    Icon(Icons.Default.Folder, contentDescription = "Carpeta"); Spacer(modifier = Modifier.width(8.dp)); Text("Seleccionar Carpeta de Música")
                }

                if (todosLosArchivos.isNotEmpty()) {
                    Text("Catálogo de Música:", style = MaterialTheme.typography.titleMedium, color = DarkPlum)
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(todosLosArchivos) { cancion ->
                            val isSelected = listaReproduccion.contains(cancion)
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Row(modifier = Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = isSelected, onCheckedChange = { toggleSeleccion(cancion) }, colors = CheckboxDefaults.colors(checkedColor = DarkPlum))
                                    Text(text = cancion.second, modifier = Modifier.weight(1f), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            if (listaReproduccion.isNotEmpty()) {
                Text("Lista de Reproducción:", style = MaterialTheme.typography.titleMedium, color = DarkPlum)
                LazyColumn(modifier = Modifier.heightIn(max = 250.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(listaReproduccion) { index, cancion ->
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Row(modifier = Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = cancion.second, modifier = Modifier.weight(1f), maxLines = 1)
                                Row {
                                    IconButton(onClick = { moverArriba(index) }, enabled = index > 0 && !conectado) { 
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir", tint = if (index > 0 && !conectado) DarkPlum else Color.Gray) 
                                    }
                                    IconButton(onClick = { moverAbajo(index) }, enabled = index < listaReproduccion.size - 1 && !conectado) { 
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Bajar", tint = if (index < listaReproduccion.size - 1 && !conectado) DarkPlum else Color.Gray) 
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else if (!conectado) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.SettingsInputComponent, contentDescription = null, modifier = Modifier.size(48.dp), tint = LightPlum)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Modo Mezcladora / Line-In", style = MaterialTheme.typography.titleMedium, color = DarkPlum)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Conecta tu mezcladora al puerto de audio. La app transmitirá todo el sonido que entre por el cable.", textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = nombrePrograma, onValueChange = { nombrePrograma = it }, label = { Text("Nombre del programa") }, modifier = Modifier.fillMaxWidth())
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
                            putExtra("LINE_IN_MODE", true)
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
                showStopDialog = true
            }
        }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = if (conectado) MaterialTheme.colorScheme.error else DarkPlum)) {
            Icon(if (conectado) Icons.Default.Close else Icons.Default.PlayArrow, contentDescription = "Play/Stop")
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (conectado) "DETENER TRANSMISIÓN" else "INICIAR TRANSMISIÓN")
        }
    }
}
