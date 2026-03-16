package com.example.omnia2.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material.*
import com.example.omnia2.data.S3Manager
import com.example.omnia2.data.MqttManager
import com.amazonaws.services.s3.model.S3ObjectSummary
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("Omnia2", "--- STARTING APP ---")
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                PermissionChecker {
                    WearApp()
                }
            }
        }
    }
}

@Composable
fun PermissionChecker(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            launcher.launch(arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    if (hasPermissions) {
        content()
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Permissions requises", color = Color.White)
        }
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Managers initialisés seulement après le rendu
    var s3Manager by remember { mutableStateOf<S3Manager?>(null) }
    var mqttManager by remember { mutableStateOf<MqttManager?>(null) }
    
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    
    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var s3Files by remember { mutableStateOf<List<S3ObjectSummary>>(emptyList()) }
    var localFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var mqttMessages by remember { mutableStateOf<List<String>>(emptyList()) }
    
    val recorderState = remember { mutableStateOf<MediaRecorder?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    var currentRecordingFile by remember { mutableStateOf<File?>(null) }
    var currentMetadataFile by remember { mutableStateOf<File?>(null) }
    
    var amplitude by remember { mutableFloatStateOf(0f) }
    var recordingTime by remember { mutableLongStateOf(0L) }

    val pagerState = rememberPagerState(pageCount = { 4 })
    val pageIndicatorState = remember {
        object : PageIndicatorState {
            override val pageOffset: Float
                get() = pagerState.currentPageOffsetFraction
            override val selectedPage: Int
                get() = pagerState.currentPage
            override val pageCount: Int
                get() = pagerState.pageCount
        }
    }

    val refreshLocalFiles = {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val files = context.filesDir.listFiles()?.filter { it.name.endsWith(".mp4") }?.sortedByDescending { it.lastModified() } ?: emptyList()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                localFiles = files
            }
        }
    }

    LaunchedEffect(Unit) {
        Log.d("Omnia2", "UI Loaded, initializing critical managers...")
        refreshLocalFiles()
        
        // On n'initialise MQTT que si nécessaire ou de manière asynchrone légère
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (mqttManager == null) {
                    val manager = MqttManager()
                    manager.connect()
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        mqttManager = manager
                    }
                    manager.messages.collect { msg ->
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            mqttMessages = (listOf(msg) + mqttMessages).take(20)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Omnia2", "MQTT init failed", e)
            }
        }
    }

    LaunchedEffect(isRecording, isPaused) {
        if (isRecording && !isPaused) {
            while (true) {
                delay(1000L)
                recordingTime++
                
                // Mettre à jour l'amplitude moyenne si possible
                recorderState.value?.let {
                    try {
                        amplitude = it.maxAmplitude.toFloat()
                    } catch (e: Exception) {}
                }
            }
        }
    }

    // Lazy initialization de S3Manager lors de la navigation
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1 || pagerState.currentPage == 2) {
            if (s3Manager == null) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val manager = S3Manager(context)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        s3Manager = manager
                    }
                    if (pagerState.currentPage == 1) {
                        val files = manager.listFiles().filter { it.key.endsWith(".mp4") }
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            s3Files = files
                        }
                    }
                }
            } else if (pagerState.currentPage == 1) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val files = s3Manager?.listFiles()?.filter { it.key.endsWith(".mp4") } ?: emptyList()
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        s3Files = files
                    }
                }
            }
        }
        if (pagerState.currentPage == 2) {
            refreshLocalFiles()
        }
    }

    Scaffold(
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        pageIndicator = { HorizontalPageIndicator(pageIndicatorState = pageIndicatorState) }
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> RecordScreen(
                        isRecording = isRecording,
                        isPaused = isPaused,
                        isUploading = isUploading,
                        recordingTime = recordingTime,
                        amplitudeProvider = { amplitude },
                        onStartRecording = {
                            scope.launch {
                                val timestamp = System.currentTimeMillis()
                                val audioFile = File(context.filesDir, "rec_$timestamp.mp4")
                                val metaFile = File(context.filesDir, "rec_$timestamp.json")
                                currentRecordingFile = audioFile
                                currentMetadataFile = metaFile
                                val location = getCurrentLocation(context, fusedLocationClient)
                                saveMetadata(metaFile, timestamp, location)
                                startRecording(context, audioFile, recorderState, onStarted = { 
                                    isRecording = true
                                    isPaused = false
                                    recordingTime = 0L
                                })
                            }
                        },
                        onStopRecording = {
                            stopRecording(recorderState, onStopped = { 
                                isRecording = false
                                isPaused = false
                                refreshLocalFiles()
                                scope.launch {
                                    isUploading = true
                                    currentRecordingFile?.let { s3Manager?.uploadFile(it) }
                                    currentMetadataFile?.let { s3Manager?.uploadFile(it) }
                                    isUploading = false
                                    refreshLocalFiles()
                                }
                            })
                        },
                        onTogglePause = {
                            recorderState.value?.let { 
                                if (isPaused) { it.resume(); isPaused = false } 
                                else { it.pause(); isPaused = true }
                            }
                        }
                    )
                    1 -> PlayScreen(
                        isPlaying = isPlaying,
                        files = s3Files,
                        onPlayFile = { s3Object ->
                            if (isPlaying) {
                                stopPlaying(player, onStopped = { isPlaying = false })
                            } else {
                                val url = s3Manager?.getFileUrl(s3Object.key)
                                url?.let { startPlaying(it, player, onStarted = { isPlaying = true }, onFinished = { isPlaying = false }) }
                            }
                        },
                        onRefresh = { 
                            scope.launch { s3Files = s3Manager?.listFiles()?.filter { it.key.endsWith(".mp4") } ?: emptyList() }
                        }
                    )
                    2 -> SyncScreen(
                        localFiles = localFiles,
                        isUploading = isUploading,
                        onSyncFile = { file ->
                            scope.launch {
                                isUploading = true
                                if (s3Manager?.uploadFile(file) == true) {
                                    file.delete()
                                    refreshLocalFiles()
                                }
                                isUploading = false
                            }
                        },
                        onSyncAll = { localFiles.forEach { /* logic */ } },
                        onRefresh = { refreshLocalFiles() },
                        onDeleteFile = { it.delete(); refreshLocalFiles() },
                        onPlayLocal = { 
                            if (isPlaying) stopPlaying(player, onStopped = { isPlaying = false })
                            else startPlayingLocal(it.absolutePath, player, onStarted = { isPlaying = true }, onFinished = { isPlaying = false })
                        }
                    )
                    3 -> MqttScreen(messages = mqttMessages)
                }
            }
        }
    }
}

@Composable
fun MqttScreen(messages: List<String>) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MQTT INBOX", color = Color.Cyan, modifier = Modifier.padding(top = 8.dp))
        ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
            items(messages) { msg ->
                Text(msg, color = Color.White, modifier = Modifier.padding(4.dp))
            }
        }
    }
}

// Utilisation de textes descriptifs clairs si les icônes ne sont pas disponibles
@Composable
fun RecordScreen(
    isRecording: Boolean, isPaused: Boolean, isUploading: Boolean, recordingTime: Long,
    amplitudeProvider: () -> Float, onStartRecording: () -> Unit, onStopRecording: () -> Unit, onTogglePause: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (isUploading) "UPLOADING..." else if (isRecording) "RECORDING" else "READY", color = Color.Gray)
        Text(String.format(Locale.getDefault(), "%02d:%02d", recordingTime / 60, recordingTime % 60), style = MaterialTheme.typography.display2)
        Spacer(modifier = Modifier.height(10.dp))
        Row {
            if (!isRecording) {
                Button(onClick = onStartRecording) { 
                    Icon(imageVector = Icons.Default.Mic, contentDescription = "Record")
                }
            } else {
                Button(onClick = onTogglePause) { 
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, 
                        contentDescription = if (isPaused) "Resume" else "Pause"
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(onClick = onStopRecording, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)) { 
                    Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop")
                }
            }
        }
    }
}

@Composable
fun PlayScreen(isPlaying: Boolean, files: List<S3ObjectSummary>, onPlayFile: (S3ObjectSummary) -> Unit, onRefresh: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("REFRESH S3") }
        ScalingLazyColumn {
            items(files) { file ->
                TitleCard(onClick = { onPlayFile(file) }, title = { Text(file.key) }) {
                    Text(if (isPlaying) "STOP" else "PLAY")
                }
            }
        }
    }
}

@Composable
fun SyncScreen(localFiles: List<File>, isUploading: Boolean, onSyncFile: (File) -> Unit, onSyncAll: () -> Unit, onRefresh: () -> Unit, onDeleteFile: (File) -> Unit, onPlayLocal: (File) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = onRefresh) { Text("REFRESH LOCAL") }
        ScalingLazyColumn {
            items(localFiles) { file ->
                AppCard(
                    onClick = { onPlayLocal(file) },
                    appName = { Text("LOCAL") },
                    time = { Text("") },
                    title = { Text(file.name) }
                ) {
                    Button(onClick = { onSyncFile(file) }) { Text("SYNC") }
                }
            }
        }
    }
}

suspend fun getCurrentLocation(context: Context, fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient): Location? {
    return try { fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await() } catch (e: Exception) { null }
}

fun saveMetadata(file: File, timestamp: Long, location: Location?) {
    val json = JSONObject().apply { put("timestamp", timestamp); put("lat", location?.latitude ?: 0.0); put("lon", location?.longitude ?: 0.0) }
    file.writeText(json.toString())
}

fun startRecording(context: Context, file: File, recorderState: MutableState<MediaRecorder?>, onStarted: () -> Unit) {
    try {
        @Suppress("DEPRECATION")
        val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare(); start()
        }
        recorderState.value = recorder; onStarted()
    } catch (e: Exception) { Log.e("Audio", "Record start failed", e) }
}

fun stopRecording(recorderState: MutableState<MediaRecorder?>, onStopped: () -> Unit) {
    recorderState.value?.apply { try { stop(); release() } catch (e: Exception) {} }
    recorderState.value = null; onStopped()
}

fun startPlaying(url: String, playerState: MutableState<MediaPlayer?>, onStarted: () -> Unit, onFinished: () -> Unit) {
    try {
        val mp = MediaPlayer().apply {
            setDataSource(url); prepareAsync()
            setOnPreparedListener { start(); onStarted() }
            setOnCompletionListener { onFinished(); release() }
        }
        playerState.value = mp
    } catch (e: Exception) {}
}

fun startPlayingLocal(path: String, playerState: MutableState<MediaPlayer?>, onStarted: () -> Unit, onFinished: () -> Unit) {
    try {
        val mp = MediaPlayer().apply {
            setDataSource(path); prepare(); start(); onStarted()
            setOnCompletionListener { onFinished(); release() }
        }
        playerState.value = mp
    } catch (e: Exception) {}
}

fun stopPlaying(playerState: MutableState<MediaPlayer?>, onStopped: () -> Unit) {
    playerState.value?.apply { try { stop(); release() } catch (e: Exception) {} }
    playerState.value = null; onStopped()
}
