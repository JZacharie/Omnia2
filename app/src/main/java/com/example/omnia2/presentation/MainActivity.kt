package com.example.omnia2.presentation

import android.Manifest
import android.app.Application
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
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private var s3Manager: S3Manager? = null
    private var mqttManager: MqttManager? = null
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var currentRecordingFile: File? = null
    private var currentMetadataFile: File? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun initManagers() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshLocalFilesSync()
            // S3 et MQTT init retardés: on les appellera quand on a besoin (ou asynchrone profondement)
        }
    }

    fun initMqttIfNeeded() {
        if (mqttManager != null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val manager = MqttManager()
                manager.connect()
                mqttManager = manager
                manager.messages.collect { msg ->
                    _uiState.update { it.copy(mqttMessages = (listOf(msg) + it.mqttMessages).take(20)) }
                }
            } catch (e: Exception) {
                Log.e("Omnia2", "MQTT init failed", e)
            }
        }
    }

    fun initS3IfNeeded() {
        if (s3Manager != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val manager = S3Manager(context)
            s3Manager = manager
            refreshS3FilesSync()
        }
    }

    private fun refreshLocalFilesSync() {
        val files = context.filesDir.listFiles()?.filter { it.name.endsWith(".mp4") }?.sortedByDescending { it.lastModified() } ?: emptyList()
        _uiState.update { it.copy(localFiles = files) }
    }

    private fun refreshS3FilesSync() {
        val files = s3Manager?.listFiles()?.filter { it.key.endsWith(".mp4") } ?: emptyList()
        _uiState.update { it.copy(s3Files = files) }
    }

    fun startRecording() {
        viewModelScope.launch {
            val timestamp = System.currentTimeMillis()
            val audioFile = File(context.filesDir, "rec_$timestamp.mp4")
            val metaFile = File(context.filesDir, "rec_$timestamp.json")
            currentRecordingFile = audioFile
            currentMetadataFile = metaFile
            
            // Location async (ne bloque pas)
            val location = try { fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await() } catch (e: Exception) { null }
            val json = JSONObject().apply { put("timestamp", timestamp); put("lat", location?.latitude ?: 0.0); put("lon", location?.longitude ?: 0.0) }
            metaFile.writeText(json.toString())

            try {
                @Suppress("DEPRECATION")
                val r = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
                r.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(audioFile.absolutePath)
                    prepare(); start()
                }
                recorder = r
                _uiState.update { it.copy(isRecording = true, isPaused = false, recordingTime = 0L) }
                startRecordingTimer()
            } catch (e: Exception) { Log.e("Audio", "Record start failed", e) }
        }
    }

    private fun startRecordingTimer() {
        viewModelScope.launch {
            while (_uiState.value.isRecording && !_uiState.value.isPaused) {
                delay(1000L)
                val amp = try { recorder?.maxAmplitude?.toFloat() ?: 0f } catch (e: Exception) { 0f }
                _uiState.update { it.copy(recordingTime = it.recordingTime + 1, amplitude = amp) }
            }
        }
    }

    fun togglePauseRecording() {
        recorder?.let { 
            val currentlyPaused = _uiState.value.isPaused
            if (currentlyPaused) { 
                it.resume()
                _uiState.update { s -> s.copy(isPaused = false) }
                startRecordingTimer()
            } else { 
                it.pause()
                _uiState.update { s -> s.copy(isPaused = true) }
            }
        }
    }

    fun stopRecording() {
        recorder?.apply { try { stop(); release() } catch (e: Exception) {} }
        recorder = null
        _uiState.update { it.copy(isRecording = false, isPaused = false) }
        
        viewModelScope.launch(Dispatchers.IO) {
            refreshLocalFilesSync()
            _uiState.update { it.copy(isUploading = true) }
            currentRecordingFile?.let { s3Manager?.uploadFile(it) }
            currentMetadataFile?.let { s3Manager?.uploadFile(it) }
            refreshLocalFilesSync()
            _uiState.update { it.copy(isUploading = false) }
        }
    }

    fun playS3File(s3Object: S3ObjectSummary) {
        if (_uiState.value.isPlaying) {
            stopPlaying()
        } else {
            val url = s3Manager?.getFileUrl(s3Object.key) ?: return
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(url); prepareAsync()
                    setOnPreparedListener { start(); _uiState.update { s -> s.copy(isPlaying = true) } }
                    setOnCompletionListener { stopPlaying(); release() }
                }
                player = mp
            } catch (e: Exception) {}
        }
    }

    fun playLocalFile(file: File) {
        if (_uiState.value.isPlaying) {
            stopPlaying()
        } else {
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(file.absolutePath); prepare(); start()
                    _uiState.update { s -> s.copy(isPlaying = true) }
                    setOnCompletionListener { stopPlaying(); release() }
                }
                player = mp
            } catch (e: Exception) {}
        }
    }

    private fun stopPlaying() {
        player?.apply { try { stop(); release() } catch (e: Exception) {} }
        player = null
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun refreshS3() {
        viewModelScope.launch(Dispatchers.IO) { refreshS3FilesSync() }
    }

    fun refreshLocal() {
        viewModelScope.launch(Dispatchers.IO) { refreshLocalFilesSync() }
    }

    fun syncFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isUploading = true) }
            if (s3Manager?.uploadFile(file) == true) {
                file.delete()
                refreshLocalFilesSync()
            }
            _uiState.update { it.copy(isUploading = false) }
        }
    }

    fun deleteFile(file: File) {
        file.delete()
        refreshLocal()
    }
}

data class UiState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val isPlaying: Boolean = false,
    val isUploading: Boolean = false,
    val s3Files: List<S3ObjectSummary> = emptyList(),
    val localFiles: List<File> = emptyList(),
    val mqttMessages: List<String> = emptyList(),
    val amplitude: Float = 0f,
    val recordingTime: Long = 0L
)

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("Omnia2", "--- STARTING APP ---")
        super.onCreate(savedInstanceState)
        
        // On libère le thread principal immédiatement. initManager se fera en background.
        viewModel.initManagers()

        setContent {
            MaterialTheme {
                PermissionChecker {
                    WearApp(viewModel)
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

    if (hasPermissions) {
        // Appeler le contenu directement : aucune latence
        content()
    } else {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasPermissions = permissions.values.all { it }
        }

        LaunchedEffect(Unit) {
            launcher.launch(arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Permissions requises...", color = Color.White)
        }
    }
}

@Composable
fun WearApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

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

    // Auto-record on startup
    LaunchedEffect(Unit) {
        if (!uiState.isRecording) {
            viewModel.startRecording()
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1) {
            viewModel.initS3IfNeeded()
        } else if (pagerState.currentPage == 3) {
            viewModel.initMqttIfNeeded()
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
                    0 -> RecordScreen(uiState, viewModel)
                    1 -> PlayScreen(uiState, viewModel)
                    2 -> SyncScreen(uiState, viewModel)
                    3 -> MqttScreen(uiState, viewModel)
                }
            }
        }
    }
}

@Composable
fun RecordScreen(uiState: UiState, viewModel: MainViewModel) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (uiState.isUploading) "UPLOADING..." else if (uiState.isRecording) "RECORDING" else "READY", color = Color.Gray)
        Text(String.format(Locale.getDefault(), "%02d:%02d", uiState.recordingTime / 60, uiState.recordingTime % 60), style = MaterialTheme.typography.display2)
        Spacer(modifier = Modifier.height(10.dp))
        Row {
            if (!uiState.isRecording) {
                Button(onClick = { viewModel.startRecording() }) { 
                    Icon(imageVector = Icons.Default.Mic, contentDescription = "Record")
                }
            } else {
                Button(onClick = { viewModel.togglePauseRecording() }) { 
                    Icon(
                        imageVector = if (uiState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, 
                        contentDescription = if (uiState.isPaused) "Resume" else "Pause"
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(onClick = { viewModel.stopRecording() }, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)) { 
                    Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop")
                }
            }
        }
    }
}

@Composable
fun PlayScreen(uiState: UiState, viewModel: MainViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = { viewModel.refreshS3() }, modifier = Modifier.fillMaxWidth()) { Text("REFRESH S3") }
        ScalingLazyColumn {
            items(uiState.s3Files) { file ->
                TitleCard(onClick = { viewModel.playS3File(file) }, title = { Text(file.key) }) {
                    Text(if (uiState.isPlaying) "STOP" else "PLAY")
                }
            }
        }
    }
}

@Composable
fun SyncScreen(uiState: UiState, viewModel: MainViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = { viewModel.refreshLocal() }) { Text("REFRESH LOCAL") }
        ScalingLazyColumn {
            items(uiState.localFiles) { file ->
                AppCard(
                    onClick = { viewModel.playLocalFile(file) },
                    appName = { Text("LOCAL") },
                    time = { Text("") },
                    title = { Text(file.name) }
                ) {
                    Button(onClick = { viewModel.syncFile(file) }) { Text("SYNC") }
                }
            }
        }
    }
}

@Composable
fun MqttScreen(uiState: UiState, viewModel: MainViewModel) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MQTT INBOX", color = Color.Cyan, modifier = Modifier.padding(top = 8.dp))
        ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
            items(uiState.mqttMessages) { msg ->
                Text(msg, color = Color.White, modifier = Modifier.padding(4.dp))
            }
        }
    }
}
