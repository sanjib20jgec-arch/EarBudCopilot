package com.copilot.earbud.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.copilot.earbud.domain.model.CopilotUiState
import com.copilot.earbud.domain.model.LlmProvider
import com.copilot.earbud.domain.model.LogEntry
import com.copilot.earbud.domain.model.PipelineStage
import com.copilot.earbud.domain.model.SttLanguage
import com.copilot.earbud.domain.model.SttProvider
import com.copilot.earbud.domain.model.WhisperMode
import com.copilot.earbud.service.CopilotStateBus

class MainActivity : ComponentActivity() {

    private val viewModel: CopilotViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    secondary = Color(0xFF4ADE80),
                    background = Color(0xFF0B0F19),
                    surface = Color(0xFF161F30),
                    onBackground = Color(0xFFF1F5F9),
                    onSurface = Color(0xFFE2E8F0)
                )
            ) {
                val state by viewModel.uiState.collectAsState()
                CopilotControlScreen(
                    state = state,
                    stageFlow = viewModel.pipelineStage,
                    rmsFlow = viewModel.rms,
                    logsFlow = viewModel.logs,
                    onToggleService = { viewModel.toggleService() },
                    onSttProviderChange = viewModel::selectSttProvider,
                    onLlmProviderChange = viewModel::selectLlmProvider,
                    onGroqKeyChange = viewModel::updateGroqApiKey,
                    onDeepgramKeyChange = viewModel::updateDeepgramApiKey,
                    onGeminiKeyChange = viewModel::updateGeminiApiKey,
                    onVadThresholdChange = viewModel::updateVadThreshold,
                    onRequireEarphonesChange = viewModel::setRequireEarphones,
                    onKeepListeningChange = viewModel::setKeepListeningDuringTts,
                    onSttLanguageChange = viewModel::selectSttLanguage,
                    onWhisperModeChange = viewModel::selectWhisperMode,
                    onRequestBatteryExemption = { requestBatteryOptimizationExemption() },
                    onSimulateTranscript = viewModel::simulateBanglishTranscript,
                    onRunDiagnostics = viewModel::runDiagnostics,
                    onClearLogs = viewModel::clearLogs
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshBatteryOptimizationState()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopilotControlScreen(
    state: CopilotUiState,
    stageFlow: StateFlow<PipelineStage>,
    rmsFlow: StateFlow<Float>,
    logsFlow: StateFlow<List<LogEntry>>,
    onToggleService: () -> Unit,
    onSttProviderChange: (SttProvider) -> Unit,
    onLlmProviderChange: (LlmProvider) -> Unit,
    onGroqKeyChange: (String) -> Unit,
    onDeepgramKeyChange: (String) -> Unit,
    onGeminiKeyChange: (String) -> Unit,
    onVadThresholdChange: (Float) -> Unit,
    onRequireEarphonesChange: (Boolean) -> Unit,
    onKeepListeningChange: (Boolean) -> Unit,
    onSttLanguageChange: (SttLanguage) -> Unit,
    onWhisperModeChange: (WhisperMode) -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onSimulateTranscript: (String) -> Unit,
    onRunDiagnostics: () -> Unit,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    var showConfigSection by remember { mutableStateOf(true) }

    // Key material is never read back from storage; these buffers only hold what the user is
    // typing right now, and are cleared once saved.
    var groqKeyInput by remember { mutableStateOf("") }
    var geminiKeyInput by remember { mutableStateOf("") }
    var deepgramKeyInput by remember { mutableStateOf("") }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            onToggleService()
        } else {
            CopilotStateBus.log("PERM", "RECORD_AUDIO permission is required to listen.", isError = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Earbud Ambient Copilot",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Banglish Real-Time Discreet Whisper Assistant",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showConfigSection = !showConfigSection }) {
                        Icon(
                            imageVector = if (showConfigSection) Icons.Default.ExpandLess else Icons.Default.Tune,
                            contentDescription = "Toggle Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Earphone Connection & Pipeline Status Banner
            StatusHeaderCard(
                state = state,
                stageFlow = stageFlow,
                rmsFlow = rmsFlow,
                onToggleClick = {
                    val hasMic = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasMic) {
                        onToggleService()
                    } else {
                        permissionLauncher.launch(requiredPermissions.toTypedArray())
                    }
                }
            )

            // 2. Collapsible API Keys & Pipeline Settings
            if (showConfigSection) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 310.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "API Keys & Pipeline Engine",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "keys are stored encrypted",
                                fontSize = 10.sp,
                                color = Color(0xFF64748B)
                            )
                        }

                        // STT Provider Selector
                        Text("Speech-to-Text (Banglish):", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SttProvider.entries.forEach { provider ->
                                FilterChip(
                                    selected = state.sttProvider == provider,
                                    onClick = { onSttProviderChange(provider) },
                                    label = { Text(provider.displayName, fontSize = 11.sp) }
                                )
                            }
                        }

                        // LLM Provider Selector
                        Text("AI Brain (Single-Sentence Filter):", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LlmProvider.entries.forEach { provider ->
                                FilterChip(
                                    selected = state.llmProvider == provider,
                                    onClick = { onLlmProviderChange(provider) },
                                    label = { Text(provider.displayName, fontSize = 11.sp) }
                                )
                            }
                        }

                        // API Key Inputs (write-only: a saved key is never displayed again)
                        OutlinedTextField(
                            value = groqKeyInput,
                            onValueChange = {
                                groqKeyInput = it
                                onGroqKeyChange(it)
                            },
                            label = {
                                Text(
                                    if (state.groqKeySet) "Groq API Key - saved (type to replace)"
                                    else "Groq API Key (Whisper STT / Llama brain)"
                                )
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = geminiKeyInput,
                            onValueChange = {
                                geminiKeyInput = it
                                onGeminiKeyChange(it)
                            },
                            label = {
                                Text(
                                    if (state.geminiKeySet) "Gemini API Key - saved (type to replace)"
                                    else "Google Gemini API Key (AI brain)"
                                )
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (state.sttProvider == SttProvider.DEEPGRAM_NOVA2) {
                            OutlinedTextField(
                                value = deepgramKeyInput,
                                onValueChange = {
                                    deepgramKeyInput = it
                                    onDeepgramKeyChange(it)
                                },
                                label = {
                                    Text(
                                        if (state.deepgramKeySet) "Deepgram API Key - saved (type to replace)"
                                        else "Deepgram API Key (Nova-2)"
                                    )
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // VAD Sensitivity Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("VAD Voice Trigger Threshold", fontSize = 12.sp)
                                VadThresholdLabels(
                                    threshold = state.vadThresholdRms,
                                    rmsFlow = rmsFlow
                                )
                            }
                            Slider(
                                value = state.vadThresholdRms,
                                onValueChange = onVadThresholdChange,
                                valueRange = 150f..1500f
                            )
                        }

                        // Strict Earphone Privacy Lock & Battery Exemption
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Strict Earbud-Only Whisper", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "Never speak over phone loudspeaker if earbuds disconnect",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Switch(
                                checked = state.requireEarphonesForTts,
                                onCheckedChange = onRequireEarphonesChange
                            )
                        }

                        // Continuous listening: mic stays open even while the answer is spoken
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Keep Listening While Speaking", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "Mic stays open during the answer so surrounding talk is not missed " +
                                        "(our own voice is filtered out)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Switch(
                                checked = state.keepListeningDuringTts,
                                onCheckedChange = onKeepListeningChange
                            )
                        }

                        // Transcription language hint (biggest single lever on Banglish accuracy)
                        Text("Transcription Language:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SttLanguage.entries.forEach { language ->
                                FilterChip(
                                    selected = state.sttLanguage == language,
                                    onClick = { onSttLanguageChange(language) },
                                    label = { Text(language.chipLabel, fontSize = 11.sp) }
                                )
                            }
                        }

                        // Whisper model trade-off: speed vs accuracy
                        Text("Whisper Model:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WhisperMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.whisperMode == mode,
                                    onClick = { onWhisperModeChange(mode) },
                                    label = { Text(mode.chipLabel, fontSize = 11.sp) }
                                )
                            }
                        }

                        if (!state.isBatteryOptimizationIgnored) {
                            OutlinedButton(
                                onClick = onRequestBatteryExemption,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Disable Battery Optimization (Screen-Off Lock)", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 3. Quick Banglish Test Bar (Test factual vs social filter anytime)
            QuickTestBar(onSimulateTranscript = onSimulateTranscript)

            OutlinedButton(
                onClick = onRunDiagnostics,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Build, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Run Diagnostics (TTS / earphones / API keys)", fontSize = 12.sp)
            }

            Text(
                text = "Privacy: each detected utterance (up to 10 s of ambient audio) is uploaded " +
                    "to Groq or Deepgram for transcription, and the transcript is sent to Gemini or " +
                    "Groq to generate the answer. Nothing is written to disk on the phone and API " +
                    "keys are stored encrypted.",
                fontSize = 10.sp,
                color = Color(0xFF64748B)
            )

            // 4. Real-Time Pipeline Status Logs
            LogsPanel(
                logsFlow = logsFlow,
                onClearLogs = onClearLogs,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatusHeaderCard(
    state: CopilotUiState,
    stageFlow: StateFlow<PipelineStage>,
    rmsFlow: StateFlow<Float>,
    onToggleClick: () -> Unit
) {
    val buttonColor by animateColorAsState(
        targetValue = if (state.isServiceRunning) Color(0xFFEF4444) else Color(0xFF10B981),
        label = "serviceToggleColor"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.earphoneState.isConnected) Color(0xFF4ADE80) else Color(0xFFF59E0B)
                            )
                    )
                    Column {
                        Text(
                            text = state.earphoneState.deviceName,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = state.earphoneState.deviceTypeDescription,
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                Button(
                    onClick = onToggleClick,
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (state.isServiceRunning) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (state.isServiceRunning) "STOP" else "START",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Pipeline stage and live level change many times per second, so they collect their
            // own flows: only these two small composables recompose, not the whole screen.
            StagePill(stageFlow = stageFlow, rmsFlow = rmsFlow)
            StageHint(stageFlow = stageFlow)
        }
    }
}

@Composable
private fun StagePill(stageFlow: StateFlow<PipelineStage>, rmsFlow: StateFlow<Float>) {
    val stage by stageFlow.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F172A))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Stage: ${stage.name}",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = when (stage) {
                PipelineStage.SPEECH_DETECTED -> Color(0xFFFACC15)
                PipelineStage.WHISPERING_TTS -> Color(0xFF4ADE80)
                PipelineStage.FILTERED -> Color(0xFF94A3B8)
                PipelineStage.ERROR,
                PipelineStage.TTS_FAILED -> Color(0xFFF87171)
                PipelineStage.TTS_BLOCKED -> Color(0xFFF59E0B)
                else -> Color(0xFF38BDF8)
            }
        )
        LiveRmsText(rmsFlow = rmsFlow, prefix = "Mic RMS: ")
    }
}

/** One-line explanation for the states where nothing was heard on purpose / on failure. */
@Composable
private fun StageHint(stageFlow: StateFlow<PipelineStage>) {
    val stage by stageFlow.collectAsState()
    val stageHint = when (stage) {
        PipelineStage.ERROR -> "Last attempt failed - read the red line in the activity log."
        PipelineStage.FILTERED -> "The AI decided this was not a factual question."
        PipelineStage.TTS_BLOCKED -> "Answer ready but not spoken: no earphones detected (strict mode)."
        PipelineStage.TTS_FAILED -> "Answer ready but the TTS engine could not speak it."
        else -> null
    }
    if (stageHint != null) {
        Text(
            text = stageHint,
            fontSize = 11.sp,
            color = if (stage == PipelineStage.FILTERED) Color(0xFF94A3B8) else Color(0xFFF87171)
        )
    }
}

/** Live microphone level; recomposes on its own every few hundred milliseconds. */
@Composable
private fun LiveRmsText(
    rmsFlow: StateFlow<Float>,
    prefix: String,
    fontSize: TextUnit = 12.sp,
    color: Color = Color(0xFF94A3B8)
) {
    val rms by rmsFlow.collectAsState()
    Text(
        text = "$prefix${rms.toInt()}",
        fontSize = fontSize,
        fontFamily = FontFamily.Monospace,
        color = color
    )
}

@Composable
private fun VadThresholdLabels(threshold: Float, rmsFlow: StateFlow<Float>) {
    val rms by rmsFlow.collectAsState()
    Text(
        text = "RMS: ${threshold.toInt()} (Live: ${rms.toInt()})",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun QuickTestBar(onSimulateTranscript: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AssistChip(
            onClick = { onSimulateTranscript("কলকাতা থেকে দিঘার দূরত্ব কত?") },
            label = { Text("Test: কলকাতা-দিঘা দূরত্ব?", fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) }
        )
        AssistChip(
            onClick = { onSimulateTranscript("আজকে খুব গরম পড়েছে তাই না, চা খাবি?") },
            label = { Text("Test: চা খাবি? (Ignore)", fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(16.dp)) }
        )
    }
}

@Composable
private fun LogsPanel(
    logsFlow: StateFlow<List<LogEntry>>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The log changes on every pipeline event; collecting it here keeps that churn out of the
    // control screen's recomposition scope.
    val logs by logsFlow.collectAsState()
    LogsPanelContent(logs = logs, onClearLogs = onClearLogs, modifier = modifier)
}

@Composable
private fun LogsPanelContent(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Copy/share the whole log as plain text, oldest line first so it reads like a timeline.
    // Device details are included because most "it lags / it is silent" reports depend on them.
    val exportLog: () -> String = {
        buildString {
            appendLine("Earbud Ambient Copilot - activity log (${logs.size} lines)")
            appendLine(
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
            appendLine("---")
            logs.asReversed().forEach { entry ->
                appendLine("[${entry.timestamp}] [${entry.tag}] ${entry.message}")
            }
        }
    }

    // CopilotStateBus already hands us the newest entry first, but a LazyColumn anchors itself to
    // the item it is currently showing: every new line used to push the visible content down, so the
    // latest line silently slid off the top and had to be scrolled back into view. These two effects
    // keep the newest line pinned at the top unless the user deliberately scrolled down to read
    // history - and offer a one-tap jump back when new lines arrive meanwhile.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val newestId = logs.firstOrNull()?.id
    val isFollowingNewest by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 1 }
    }
    var unseenEntries by remember { mutableIntStateOf(0) }

    LaunchedEffect(newestId) {
        if (newestId == null) return@LaunchedEffect
        if (isFollowingNewest) listState.scrollToItem(0) else unseenEntries += 1
    }
    LaunchedEffect(isFollowingNewest) {
        if (isFollowingNewest) unseenEntries = 0
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Real-Time Copilot Activity Log",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.weight(1f)
                )
                if (!isFollowingNewest) {
                    TextButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } }
                    ) {
                        Text(
                            text = if (unseenEntries > 0) "\u2191 Newest ($unseenEntries)" else "\u2191 Newest",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(exportLog()))
                        Toast.makeText(context, "Activity log copied", Toast.LENGTH_SHORT).show()
                    },
                    enabled = logs.isNotEmpty(),
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy the whole activity log",
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Earbud Ambient Copilot activity log")
                            putExtra(Intent.EXTRA_TEXT, exportLog())
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(shareIntent, "Share activity log"))
                        }
                    },
                    enabled = logs.isNotEmpty(),
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share the whole activity log",
                        modifier = Modifier.size(16.dp)
                    )
                }
                TextButton(onClick = onClearLogs) {
                    Text("Clear", fontSize = 11.sp)
                }
            }

            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Press START to begin ambient Banglish listening...",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(logs, key = { it.id }) { entry ->
                        val textColor = when {
                            entry.isError -> Color(0xFFF87171)
                            entry.isHighlight -> Color(0xFF4ADE80)
                            else -> Color(0xFFE2E8F0)
                        }
                        Text(
                            text = "[${entry.timestamp}] [${entry.tag}] ${entry.message}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}
