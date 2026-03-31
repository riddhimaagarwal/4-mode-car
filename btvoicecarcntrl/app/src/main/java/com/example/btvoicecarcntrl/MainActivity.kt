package com.example.btvoicecarcntrl

import android.Manifest
import android.content.Intent
import kotlinx.coroutines.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import android.bluetooth.BluetoothAdapter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.btvoicecarcntrl.ui.theme.BtVoiceCarCntrlTheme
import kotlin.math.*

// ---- Colors ----
private val BgColor = Color(0xFF020405)
private val GlowOuter = Color(0xFF00C896)
private val GlowInner = Color(0xFF003C32)
private val CardDark = Color(0xFF050C11)
private val CardDarkSoft = Color(0xFF07161D)

// ---- Commands configuration ----
data class CommandConfig(
    val stop: String = "stop\n",
    val rc: String = "RC mode\n",
    val chase: String = "chase mode\n",
    val avoid: String = "avoid mode\n",
    val auto: String = "autonomous\n",
    val forward: String = "forward\n",
    val backward: String = "backward\n",
    val left: String = "turn left\n",
    val right: String = "turn right\n",
    val forwardLeft: String = "forward_left\n",
    val forwardRight: String = "forward_right\n",
    val backwardLeft: String = "backward_left\n",
    val backwardRight: String = "backward_right\n"
)

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var btController: BluetoothController
    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val statusMessage = mutableStateOf("Not connected")

    private var voiceCommandJob: Job? = null
    private var lastVoiceCommand: String = ""


    // ---- VOICE CONTROL ----
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private val isListening = mutableStateOf(false)
    private val lastHeard = mutableStateOf("Voice: idle")

    // Will be kept in sync with UI's commandConfig
    private var currentConfig: CommandConfig = CommandConfig()

    private var voiceJob: Job? = null
    private var lastVoiceCmd: String = ""


    // Tilt vector exposed to Compose
    private val tiltVecState = mutableStateOf(Offset.Zero)

    private lateinit var sensorManager: SensorManager
    private lateinit var prefs: SharedPreferences

    private val audioPerm = arrayOf(Manifest.permission.RECORD_AUDIO)
    private val btPerm12 = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        btController = BluetoothController(btAdapter)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        prefs = getSharedPreferences("bt_car_prefs", MODE_PRIVATE)

        val initialConfig = loadCommandConfig()
        currentConfig = initialConfig
        requestPermissions()
        initSpeech()

        setContent {
            BtVoiceCarCntrlTheme {
                var commandConfigState by remember { mutableStateOf(initialConfig) }

                Surface(Modifier.fillMaxSize(), color = BgColor) {
                    CarControlScreen(
                        statusMessage = statusMessage.value,
                        onConnect = { name ->
                            btController.connectToPairedDeviceByName(name) { raw ->
                                val msg = when {
                                    raw.contains("connected", true) -> raw
                                    raw.contains("fail", true) ||
                                            raw.contains("error", true) ||
                                            raw.contains("socket", true) ->
                                        "Connection failed"
                                    else -> raw
                                }
                                runOnUiThread { statusMessage.value = msg }
                            }
                        },
                        send = btController::send,
                        connected = { btController.isConnected },
                        tiltVec = tiltVecState.value,
                        getDevices = {
                            btAdapter?.bondedDevices?.mapNotNull { it.name }?.sorted()
                                ?: emptyList()
                        },
                        commandConfig = commandConfigState,
                        onUpdateCommands = { cfg ->
                            commandConfigState = cfg
                            saveCommandConfig(cfg)
                            currentConfig = cfg       // keep voice mapping in sync
                        },
                        voiceListening = isListening.value,
                        voiceStatus = lastHeard.value,
                        onToggleVoice = { enable ->
                            if (enable) startListening() else stopListening()
                        }
                    )
                }
            }
        }
    }

    private fun loadCommandConfig(): CommandConfig = CommandConfig(
        stop = prefs.getString("cmd_stop", "stop\n") ?: "stop\n",
        rc = prefs.getString("cmd_rc", "RC mode\n") ?: "RC mode\n",
        chase = prefs.getString("cmd_chase", "chase mode\n") ?: "chase mode\n",
        avoid = prefs.getString("cmd_avoid", "avoid mode\n") ?: "avoid mode\n",
        auto = prefs.getString("cmd_auto", "autonomous\n") ?: "autonomous\n",
        forward = prefs.getString("cmd_forward", "forward\n") ?: "forward\n",
        backward = prefs.getString("cmd_backward", "backward\n") ?: "backward\n",
        left = prefs.getString("cmd_left", "turn left\n") ?: "turn left\n",
        right = prefs.getString("cmd_right", "turn right\n") ?: "turn right\n",
        forwardLeft = prefs.getString("cmd_fwd_left", "forward_left\n") ?: "forward_left\n",
        forwardRight = prefs.getString("cmd_fwd_right", "forward_right\n") ?: "forward_right\n",
        backwardLeft = prefs.getString("cmd_back_left", "backward_left\n") ?: "backward_left\n",
        backwardRight = prefs.getString("cmd_back_right", "backward_right\n") ?: "backward_right\n"
    )

    private fun saveCommandConfig(cfg: CommandConfig) {
        prefs.edit()
            .putString("cmd_stop", cfg.stop)
            .putString("cmd_rc", cfg.rc)
            .putString("cmd_chase", cfg.chase)
            .putString("cmd_avoid", cfg.avoid)
            .putString("cmd_auto", cfg.auto)
            .putString("cmd_forward", cfg.forward)
            .putString("cmd_backward", cfg.backward)
            .putString("cmd_left", cfg.left)
            .putString("cmd_right", cfg.right)
            .putString("cmd_fwd_left", cfg.forwardLeft)
            .putString("cmd_fwd_right", cfg.forwardRight)
            .putString("cmd_back_left", cfg.backwardLeft)
            .putString("cmd_back_right", cfg.backwardRight)
            .apply()
    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(this, audioPerm, 1)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val need = btPerm12.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (need) ActivityCompat.requestPermissions(this, btPerm12, 2)
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        btController.close()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
    }


    private fun initSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            lastHeard.value = "Speech recognition not available"
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                lastHeard.value = "Listening..."
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                isListening.value = false
                lastHeard.value = "Voice error: $error"
            }

            override fun onResults(results: Bundle) {
                isListening.value = false
                val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: return
                handleRecognizedText(text)
            }

            override fun onPartialResults(partialResults: Bundle) {
                val list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: return
                lastHeard.value = "Heard: $text"
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    private fun startListening() {
        if (!::speechRecognizer.isInitialized) {
            initSpeech()
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            lastHeard.value = "Speech recognition not available"
            return
        }
        isListening.value = true
        lastHeard.value = "Listening..."
        speechRecognizer.startListening(speechIntent)
    }

    private fun stopListening() {
        if (!::speechRecognizer.isInitialized) return
        isListening.value = false
        speechRecognizer.stopListening()
        speechRecognizer.cancel()
        lastHeard.value = "Voice: stopped"
    }

    private fun handleRecognizedText(text: String) {
        lastHeard.value = text

        val cmd = mapSpeechToCommand(text, currentConfig) ?: return

        // stop previous repeating command immediately
        voiceJob?.cancel()

        lastVoiceCmd = cmd

        // start repeating sending for 1 sec
        voiceJob = CoroutineScope(Dispatchers.IO).launch {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 1000) { // 1 second
                btController.send(cmd)
                delay(120)  // repeat faster or slower as needed
            }
        }
    }





    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = e.values[0]
        val y = e.values[1]
        val nx = (-x / 4f).coerceIn(-1f, 1f)
        val ny = (y / 4f).coerceIn(-1f, 1f)
        tiltVecState.value = Offset(nx * 70f, ny * 70f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
fun CarControlScreen(
    statusMessage: String,
    onConnect: (String) -> Unit,
    send: (String) -> Unit,
    connected: () -> Boolean,
    tiltVec: Offset,
    getDevices: () -> List<String>,
    commandConfig: CommandConfig,
    onUpdateCommands: (CommandConfig) -> Unit,
    voiceListening: Boolean,
    voiceStatus: String,
    onToggleVoice: (Boolean) -> Unit
){
    var activeMode by remember { mutableStateOf("STOP") }
    val scroll = rememberScrollState()

    var devices by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<String?>(null) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    var showCommandsDialog by remember { mutableStateOf(false) }

    var lastCmd by remember { mutableStateOf("") }
    var displayCmd by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().background(BgColor)
            .padding(16.dp).verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            "BT Car Controller",
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        // ---------- Connection ----------
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            colors = CardDefaults.cardColors(CardDark)
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (connected()) "Connected" else "Not connected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Button(
                    onClick = {
                        devices = getDevices()
                        showDeviceDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(GlowOuter, Color.Black)
                ) {
                    Text("Select Bluetooth device")
                }

                if (selectedDevice != null)
                    Text("Selected: $selectedDevice", color = Color.LightGray)
                else
                    Text("No device selected", color = Color.Gray)

                Text(statusMessage, color = Color.LightGray)
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---------- Control ----------
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            colors = CardDefaults.cardColors(CardDarkSoft)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Mode", color = Color.White, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { showCommandsDialog = true }) { Text("Commands") }
                }

                Spacer(Modifier.height(4.dp))

                // ---- MODE BUTTONS WITH FIXED COMMAND DISPLAY UPDATE ----
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ModeChip("STOP", activeMode) {
                        activeMode = "STOP"
                        send(commandConfig.stop)
                        displayCmd = commandConfig.stop
                    }

                    ModeChip("RC", activeMode) {
                        activeMode = "RC"
                        send(commandConfig.rc)
                        displayCmd = commandConfig.rc          // ← add this
                    }
                    ModeChip("CHASE", activeMode) {
                        activeMode = "CHASE"
                        send(commandConfig.chase)
                        displayCmd = commandConfig.chase
                    }

                }

                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ModeChip("AVOID", activeMode) {
                        activeMode = "AVOID"
                        send(commandConfig.avoid)
                        displayCmd = commandConfig.avoid
                    }
                    ModeChip("AUTO", activeMode) {
                        activeMode = "AUTO"
                        send(commandConfig.auto)
                        displayCmd = commandConfig.auto
                    }
                }

                Spacer(Modifier.height(22.dp))

                var tiltEnabled by remember { mutableStateOf(false) }

                val maxOffset = 70f
                val nx = (tiltVec.x / maxOffset).coerceIn(-1f, 1f)
                val ny = (-tiltVec.y / maxOffset).coerceIn(-1f, 1f)
                val tiltMag = sqrt(nx * nx + ny * ny)
                val directionLabel =
                    if (!tiltEnabled || tiltMag < 0.25f) "Direction"
                    else vectorToDirectionLabel(nx, ny)

                Text(directionLabel, color = Color.White, fontWeight = FontWeight.SemiBold)

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { tiltEnabled = !tiltEnabled },
                    modifier = Modifier.padding(4.dp).height(40.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        if (tiltEnabled) GlowOuter else Color.DarkGray,
                        if (tiltEnabled) Color.Black else Color.White
                    )
                ) {
                    Text(if (tiltEnabled) "Tilt Control: ON" else "Tilt Control: OFF")
                }

                Spacer(Modifier.height(12.dp))

                Joystick(
                    tiltVec = tiltVec,
                    tiltEnabled = tiltEnabled,
                    commandConfig = commandConfig,
                    sendCommand = { cmd ->
                        if (cmd != lastCmd) {
                            lastCmd = cmd
                            displayCmd = cmd
                            send(cmd)
                        }
                    }
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    "Voice control",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(6.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onToggleVoice(!voiceListening) },
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            if (voiceListening) GlowOuter else Color.DarkGray,
                            if (voiceListening) Color.Black else Color.White
                        )
                    ) {
                        Text(if (voiceListening) "Stop Listening" else "Start Voice Control")
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = voiceStatus,
                        modifier = Modifier.weight(1f),
                        color = Color.LightGray,
                        maxLines = 2
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    if (displayCmd.isBlank()) "Command: none" else "Command: $displayCmd",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

            }
        }

        // ---------- Device selection dialog ----------
        if (showDeviceDialog) {
            AlertDialog(
                onDismissRequest = { showDeviceDialog = false },
                title = { Text("Select Bluetooth device", fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (devices.isEmpty()) {
                            Text("No paired Bluetooth devices.\nPair HC-05/HC-06 in settings.")
                        } else {
                            devices.forEach { name ->
                                OutlinedButton(
                                    onClick = {
                                        selectedDevice = name
                                        onConnect(name)
                                        showDeviceDialog = false
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(name) }
                            }
                        }
                        TextButton(onClick = { devices = getDevices() }) { Text("Refresh") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDeviceDialog = false }) { Text("Close") }
                }
            )
        }

        // ---------- Commands settings dialog ----------
        if (showCommandsDialog) {
            var stop by remember { mutableStateOf(commandConfig.stop) }
            var rc by remember { mutableStateOf(commandConfig.rc) }
            var chase by remember { mutableStateOf(commandConfig.chase) }
            var avoid by remember { mutableStateOf(commandConfig.avoid) }
            var auto by remember { mutableStateOf(commandConfig.auto) }
            var forward by remember { mutableStateOf(commandConfig.forward) }
            var backward by remember { mutableStateOf(commandConfig.backward) }
            var left by remember { mutableStateOf(commandConfig.left) }
            var right by remember { mutableStateOf(commandConfig.right) }
            var forwardLeft by remember { mutableStateOf(commandConfig.forwardLeft) }
            var forwardRight by remember { mutableStateOf(commandConfig.forwardRight) }
            var backwardLeft by remember { mutableStateOf(commandConfig.backwardLeft) }
            var backwardRight by remember { mutableStateOf(commandConfig.backwardRight) }

            AlertDialog(
                onDismissRequest = { showCommandsDialog = false },
                title = { Text("Edit commands", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(stop, { stop = it }, label = { Text("STOP") })
                        OutlinedTextField(rc, { rc = it }, label = { Text("RC") })
                        OutlinedTextField(chase, { chase = it }, label = { Text("CHASE") })
                        OutlinedTextField(avoid, { avoid = it }, label = { Text("AVOID") })
                        OutlinedTextField(auto, { auto = it }, label = { Text("AUTO") })

                        OutlinedTextField(forward, { forward = it }, label = { Text("Forward") })
                        OutlinedTextField(backward, { backward = it }, label = { Text("Backward") })
                        OutlinedTextField(left, { left = it }, label = { Text("Left") })
                        OutlinedTextField(right, { right = it }, label = { Text("Right") })

                        OutlinedTextField(forwardLeft, { forwardLeft = it }, label = { Text("Forward-left") })
                        OutlinedTextField(forwardRight, { forwardRight = it }, label = { Text("Forward-right") })
                        OutlinedTextField(backwardLeft, { backwardLeft = it }, label = { Text("Backward-left") })
                        OutlinedTextField(backwardRight, { backwardRight = it }, label = { Text("Backward-right") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onUpdateCommands(
                            CommandConfig(
                                stop, rc, chase, avoid, auto,
                                forward, backward, left, right,
                                forwardLeft, forwardRight,
                                backwardLeft, backwardRight
                            )
                        )
                        showCommandsDialog = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showCommandsDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
fun ModeChip(label: String, active: String, onClick: () -> Unit) {
    val activeNow = active == label
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        modifier = Modifier.height(32.dp).padding(horizontal = 4.dp),
        color = if (activeNow) GlowOuter else Color(0xFF141C21)
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (activeNow) Color.Black else Color(0xFFCBD3DA),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun Joystick(
    tiltVec: Offset,
    tiltEnabled: Boolean,
    commandConfig: CommandConfig,
    sendCommand: (String) -> Unit
) {
    val dragOffset = remember { mutableStateOf(Offset.Zero) }
    val isDragging = remember { mutableStateOf(false) }
    val maxOffset = 70f

    fun applyVector(v: Offset) {
        val nx = (v.x / maxOffset).coerceIn(-1f, 1f)
        val ny = (-v.y / maxOffset).coerceIn(-1f, 1f)
        sendCommand(vectorToCommand(nx, ny, commandConfig))
    }

    val baseTilt = if (tiltEnabled)
        Offset(tiltVec.x.coerceIn(-maxOffset, maxOffset), tiltVec.y.coerceIn(-maxOffset, maxOffset))
    else Offset.Zero

    val finalOffset = if (isDragging.value) dragOffset.value else baseTilt
    applyVector(finalOffset)

    Box(
        modifier = Modifier.size(220.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(GlowOuter.copy(alpha = 0.65f), GlowInner, Color.Transparent))),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(150.dp).clip(CircleShape).background(Color(0xFF02070A))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isDragging.value = true },
                        onDragEnd = {
                            isDragging.value = false
                            dragOffset.value = Offset.Zero
                        },
                        onDragCancel = {
                            isDragging.value = false
                            dragOffset.value = Offset.Zero
                        },
                        onDrag = { _, drag ->
                            val raw = dragOffset.value + drag
                            val dist = sqrt(raw.x * raw.x + raw.y * raw.y)
                            dragOffset.value =
                                if (dist > maxOffset) raw * (maxOffset / dist) else raw
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(64.dp)
                    .offset { IntOffset(finalOffset.x.roundToInt(), finalOffset.y.roundToInt()) }
                    .clip(CircleShape).background(GlowOuter)
            )
        }
    }
}

private fun vectorToCommand(nx: Float, ny: Float, c: CommandConfig): String {
    val mag = sqrt(nx * nx + ny * ny)
    if (mag < 0.25f) return c.stop

    val angle = Math.toDegrees(atan2(ny.toDouble(), nx.toDouble()))
    return when {
        angle >= -22.5 && angle < 22.5 -> c.right
        angle >= 22.5 && angle < 67.5 -> c.forwardRight
        angle >= 67.5 && angle < 112.5 -> c.forward
        angle >= 112.5 && angle < 157.5 -> c.forwardLeft
        angle >= 157.5 || angle < -157.5 -> c.left
        angle >= -157.5 && angle < -112.5 -> c.backwardLeft
        angle >= -112.5 && angle < -67.5 -> c.backward
        else -> c.backwardRight
    }
}

private fun vectorToDirectionLabel(nx: Float, ny: Float): String {
    val angle = Math.toDegrees(atan2(ny.toDouble(), nx.toDouble()))
    return when {
        angle >= -22.5 && angle < 22.5 -> "Right"
        angle >= 22.5 && angle < 67.5 -> "Forward Right"
        angle >= 67.5 && angle < 112.5 -> "Forward"
        angle >= 112.5 && angle < 157.5 -> "Forward Left"
        angle >= 157.5 || angle < -157.5 -> "Left"
        angle >= -157.5 && angle < -112.5 -> "Backward Left"
        angle >= -112.5 && angle < -67.5 -> "Backward"
        else -> "Backward Right"
    }
}

private fun mapSpeechToCommand(text: String, c: CommandConfig): String? {
    val t = text.lowercase(Locale.getDefault())

    // First handle more specific combos
    return when {
        "forward" in t && "left" in t -> c.forwardLeft
        "forward" in t && "right" in t -> c.forwardRight
        ("back" in t || "reverse" in t) && "left" in t -> c.backwardLeft
        ("back" in t || "reverse" in t) && "right" in t -> c.backwardRight

        // Modes
        "rc mode" in t || (t.contains("rc") && t.contains("mode")) || "manual" in t ->
            c.rc
        "chase" in t -> c.chase
        "avoid" in t || "evade" in t -> c.avoid
        "auto" in t || "autonomous" in t -> c.auto

        // Simple directions
        "stop" in t || "halt" in t || "pause" in t -> c.stop
        "forward" in t || "front" in t -> c.forward
        "backward" in t || "reverse" in t || ("back" in t && "forward" !in t) -> c.backward
        "left" in t -> c.left
        "right" in t -> c.right

        else -> null
    }
}
