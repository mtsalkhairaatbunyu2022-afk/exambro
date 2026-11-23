package com.example

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.graphics.Bitmap
import android.media.AudioManager
import android.content.Context
import android.media.ToneGenerator
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.animation.core.*
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import android.webkit.JavascriptInterface
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private val pendingSpeechQueue = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())

    companion object {
        var isExamActive by mutableStateOf(false)
        var violationCount by mutableStateOf(0)
        var hasCheatedLockout by mutableStateOf(false)
        var showWarningDialog by mutableStateOf(false)
        var lastViolationReason by mutableStateOf("")
        var instance: MainActivity? = null

        fun triggerViolation(reason: String) {
            if (!isExamActive) return
            lastViolationReason = reason
            violationCount++
            
            // Force maximum volume so the audio alert plays loudly and un-mutes the device!
            instance?.forceMaxVolume()
            
            // Unlimited attempts allowed (no permanent lockout), only show warning dialog
            showWarningDialog = true
        }
    }

    fun forceMaxVolume() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_PLAY_SOUND)
            
            // Request audio focus transiently to ensure TTS sound plays loudly on all Android versions
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        instance = this
        
        // Initialize Text-to-Speech (TTS) engine with robust multi-vendor support
        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val indonesianLocales = listOf(
                        Locale("id", "ID"),
                        Locale("id"),
                        Locale("in", "ID"),
                        Locale("in"),
                        Locale.getDefault(),
                        Locale.US
                    )
                    for (loc in indonesianLocales) {
                        try {
                            val res = tts?.setLanguage(loc)
                            if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                                break
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            val attrs = android.media.AudioAttributes.Builder()
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .build()
                            tts?.setAudioAttributes(attrs)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    try {
                        tts?.setPitch(1.0f)
                        tts?.setSpeechRate(1.0f)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    isTtsInitialized = true
                    forceMaxVolume()

                    // Drain queued speeches once TTS is initialized
                    synchronized(pendingSpeechQueue) {
                        if (pendingSpeechQueue.isEmpty()) {
                            pendingSpeechQueue.add(Pair("Selamat datang", TextToSpeech.QUEUE_FLUSH))
                        }
                        for ((speechText, mode) in pendingSpeechQueue) {
                            speakInternal(speechText, mode)
                        }
                        pendingSpeechQueue.clear()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Add default welcome speech
        speak("Selamat datang", TextToSpeech.QUEUE_FLUSH)
        
        // Note: FLAG_SECURE is applied only when entering ActiveExamScreen so the main menu renders normally in Preview
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SecureExamApp()
                }
            }
        }
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (text.isBlank()) return
        if (isTtsInitialized) {
            forceMaxVolume()
            speakInternal(text, queueMode)
        } else {
            synchronized(pendingSpeechQueue) {
                if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                    pendingSpeechQueue.clear()
                }
                pendingSpeechQueue.add(Pair(text, queueMode))
            }
        }
    }

    private fun speakInternal(text: String, queueMode: Int) {
        try {
            forceMaxVolume()
            if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                tts?.stop()
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val params = android.os.Bundle()
                params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                tts?.speak(text, queueMode, params, "ExambroTTS_${System.currentTimeMillis()}")
            } else {
                @Suppress("DEPRECATION")
                val params = HashMap<String, String>()
                params[TextToSpeech.Engine.KEY_PARAM_STREAM] = AudioManager.STREAM_MUSIC.toString()
                params[TextToSpeech.Engine.KEY_PARAM_VOLUME] = "1.0"
                @Suppress("DEPRECATION")
                tts?.speak(text, queueMode, params)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (instance == this) {
            instance = null
        }
    }

    override fun onPause() {
        super.onPause()
        if (isExamActive && !hasCheatedLockout) {
            triggerViolation("Menekan Tombol Home / Swipe Keluar Aplikasi / Membuka Recent Apps")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (isExamActive && !hasFocus && !hasCheatedLockout) {
            triggerViolation("Membuka Menu Recent Apps / Aplikasi Mengambang (Floating App like ChatGPT / AI Bubble) / Menarik Status Bar")
        }
    }

    override fun onActionModeStarted(mode: android.view.ActionMode?) {
        super.onActionModeStarted(mode)
        if (isExamActive) {
            mode?.menu?.clear()
            mode?.finish()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isExamActive) {
            val isClipboardShortcut =
                event.keyCode == KeyEvent.KEYCODE_COPY ||
                event.keyCode == KeyEvent.KEYCODE_CUT ||
                event.keyCode == KeyEvent.KEYCODE_PASTE ||
                ((event.isCtrlPressed || event.isMetaPressed) && (
                    event.keyCode == KeyEvent.KEYCODE_C ||
                    event.keyCode == KeyEvent.KEYCODE_X ||
                    event.keyCode == KeyEvent.KEYCODE_V ||
                    event.keyCode == KeyEvent.KEYCODE_A ||
                    event.keyCode == KeyEvent.KEYCODE_INSERT
                ))
            if (isClipboardShortcut) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    Toast.makeText(this, "Copy/Paste tidak diizinkan saat ujian!", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isExamActive && keyCode == KeyEvent.KEYCODE_BACK) {
            Toast.makeText(this, "Navigasi tidak diizinkan!", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}



data class BlockedApp(val name: String, val packageName: String)

fun getDetectedBlockedApps(context: Context): List<BlockedApp> {
    val pm = context.packageManager
    val blacklist = listOf(
        BlockedApp("ChatGPT", "com.openai.chatgpt"),
        BlockedApp("Copilot", "com.microsoft.copilot"),
        BlockedApp("Perplexity", "ai.perplexity.app.android"),
        BlockedApp("Gemini", "com.google.android.apps.bard"),
        BlockedApp("Claude", "com.anthropic.claude"),
        BlockedApp("Brainly", "co.brainly"),
        BlockedApp("Quora", "com.quora.android"),
        BlockedApp("Meta AI", "com.meta.ai")
    )
    val foundApps = mutableListOf<BlockedApp>()
    for (app in blacklist) {
        try {
            pm.getPackageInfo(app.packageName, 0)
            foundApps.add(app)
        } catch (e: PackageManager.NameNotFoundException) {
            // Not installed
        }
    }
    return foundApps
}

@Composable
fun RunningText3D() {
    val infiniteTransition = rememberInfiniteTransition(label = "marquee")
    val offsetFraction by infiniteTransition.animateFloat(
        initialValue = 1.2f,
        targetValue = -1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F2615), // Deep forest green
                        Color(0xFF051009)  // Dark carbon-green backplate
                    )
                )
            )
            .border(
                width = 1.5.dp,
                color = Color(0xFF2E7D32),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            )
            .padding(vertical = 10.dp, horizontal = 12.dp)
    ) {
        val width = maxWidth
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.offset(x = width * offsetFraction)
            ) {
                // Layer 3: Backmost 3D drop shadow (Deep forest dark shadow)
                Text(
                    text = "★ CBT MTs Bunyu ★",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF050C06),
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.offset(x = 3.dp, y = 3.dp)
                )
                // Layer 2: Main 3D thick bevel (Solid green depth)
                Text(
                    text = "★ CBT MTs Bunyu ★",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF1B5E20),
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.offset(x = 1.5.dp, y = 1.5.dp)
                )
                // Layer 1: Frontmost glowing 3D Text (Neon / Lime vivid highlight)
                Text(
                    text = "★ CBT MTs Bunyu ★",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF39FF14), // True Neon Green
                        shadow = Shadow(
                            color = Color(0xFFC2FFB3),
                            offset = Offset(0f, 0f),
                            blurRadius = 6f
                        )
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
fun SecureExamApp() {
    var examUrl by remember { mutableStateOf<String?>(null) }

    if (examUrl != null) {
        ActiveExamScreen(
            url = examUrl!!,
            onFinishExam = { examUrl = null }
        )
    } else {
        DashboardScreen(
            onStartExam = { url -> examUrl = url }
        )
    }
}

class WebAppInterface(private val onNameExtracted: (String) -> Unit) {
    @JavascriptInterface
    fun sendStudentName(name: String) {
        onNameExtracted(name)
    }
}

class SecureExamWebView(context: Context) : android.webkit.WebView(context) {
    init {
        isLongClickable = false
        isHapticFeedbackEnabled = false
        setOnLongClickListener { true }
    }

    override fun performLongClick(): Boolean = true

    override fun performLongClick(x: Float, y: Float): Boolean = true

    override fun startActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? = null

    override fun startActionMode(callback: android.view.ActionMode.Callback?, type: Int): android.view.ActionMode? = null

    override fun showContextMenu(): Boolean = false

    override fun showContextMenu(x: Float, y: Float): Boolean = false

    override fun onCreateContextMenu(menu: android.view.ContextMenu?) {
        // Blocked during exam
    }
}

fun clearExamClipboard(context: Context) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val hasContent = try {
            val clip = clipboard.primaryClip
            clip != null && clip.itemCount > 0 && !clip.getItemAt(0).text.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
        if (hasContent) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun injectAntiCopyProtection(view: android.webkit.WebView) {
    view.evaluateJavascript(
        """
        (function() {
            function applyAntiCopyToDoc(doc) {
                if (!doc) return;
                try {
                    if (!doc.getElementById('exambro-anticopy-style')) {
                        var style = doc.createElement('style');
                        style.id = 'exambro-anticopy-style';
                        style.innerHTML = 'html, body, *, *::before, *::after { -webkit-touch-callout: none !important; -webkit-user-select: none !important; user-select: none !important; } input, textarea, [contenteditable="true"] { -webkit-user-select: text !important; user-select: text !important; }';
                        var target = doc.head || doc.documentElement;
                        if (target) target.appendChild(style);
                    }
                    if (!doc.__exambroAntiCopyAttached) {
                        doc.__exambroAntiCopyAttached = true;
                        var blockEvent = function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            return false;
                        };
                        doc.addEventListener('copy', blockEvent, true);
                        doc.addEventListener('cut', blockEvent, true);
                        doc.addEventListener('paste', blockEvent, true);
                        doc.addEventListener('contextmenu', blockEvent, true);
                        doc.addEventListener('dragstart', blockEvent, true);
                        doc.addEventListener('beforeinput', function(e) {
                            if (e.inputType === 'insertFromPaste' || e.inputType === 'insertFromDrop') {
                                e.preventDefault();
                                e.stopPropagation();
                                return false;
                            }
                        }, true);
                        doc.addEventListener('selectstart', function(e) {
                            var el = e.target;
                            var tag = el && el.tagName ? el.tagName.toUpperCase() : '';
                            var editable = el && el.isContentEditable;
                            if (tag !== 'INPUT' && tag !== 'TEXTAREA' && !editable) {
                                e.preventDefault();
                                e.stopPropagation();
                                return false;
                            }
                        }, true);
                        doc.addEventListener('selectionchange', function() {
                            var active = doc.activeElement;
                            var tag = active && active.tagName ? active.tagName.toUpperCase() : '';
                            if (tag !== 'INPUT' && tag !== 'TEXTAREA' && !(active && active.isContentEditable)) {
                                var win = doc.defaultView || window;
                                var sel = win && win.getSelection ? win.getSelection() : null;
                                if (sel && !sel.isCollapsed) {
                                    sel.removeAllRanges();
                                }
                            }
                        }, true);
                    }
                } catch (err) {}
            }
            applyAntiCopyToDoc(document);
            if (!window.__exambroSelectionGuard) {
                window.__exambroSelectionGuard = setInterval(function() {
                    applyAntiCopyToDoc(document);
                    var active = document.activeElement;
                    var tag = active && active.tagName ? active.tagName.toUpperCase() : '';
                    if (tag !== 'INPUT' && tag !== 'TEXTAREA' && !(active && active.isContentEditable)) {
                        var sel = window.getSelection();
                        if (sel && !sel.isCollapsed) {
                            sel.removeAllRanges();
                        }
                    }
                    var frames = document.querySelectorAll('iframe');
                    for (var i = 0; i < frames.length; i++) {
                        try {
                            applyAntiCopyToDoc(frames[i].contentDocument);
                        } catch (e) {}
                    }
                }, 400);
            }
        })();
        """.trimIndent(),
        null
    )
}

@Composable
fun WatermarkOverlay(
    studentName: String,
    opacity: Float = 0.20f,
    textSizeSp: Int = 22,
    speedSeconds: Int = 12
) {
    if (studentName.isBlank()) return

    val infiniteTransition = rememberInfiniteTransition(label = "watermark")
    
    // Animate across the screen, right to left
    val xOffsetFraction by infiniteTransition.animateFloat(
        initialValue = 1.2f, // Start off-screen right
        targetValue = -1.2f, // End off-screen left
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = speedSeconds * 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "xOffset"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val screenWidth = maxWidth
        
        Text(
            text = studentName.uppercase(),
            fontSize = textSizeSp.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            color = Color(0xFF2E7D32).copy(alpha = opacity), // Green with semi-transparent opacity for legibility
            style = androidx.compose.ui.text.TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = opacity * 0.4f),
                    offset = Offset(1f, 1f),
                    blurRadius = 2f
                )
            ),
            modifier = Modifier.offset(x = screenWidth * xOffsetFraction)
        )
    }
}

@Composable
fun ActiveExamScreen(url: String, onFinishExam: () -> Unit) {
    var studentName by remember { mutableStateOf("") }
    val context = LocalContext.current
    val activity = context as? Activity

    // Watermark customization state
    var watermarkOpacity by remember { mutableFloatStateOf(0.20f) }
    var watermarkTextSizeSp by remember { mutableIntStateOf(22) }
    var watermarkSpeedSeconds by remember { mutableIntStateOf(12) }



    // Settings dialog state
    var showSettingsDialog by remember { mutableStateOf(false) }
    
    // Manage Exam Lifecycle, FLAG_SECURE, clear clipboard, and reset stats
    LaunchedEffect(Unit) {
        MainActivity.isExamActive = true
        MainActivity.violationCount = 0
        MainActivity.hasCheatedLockout = false
        MainActivity.showWarningDialog = false
        clearExamClipboard(context)
        try {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Exception) {
        }
        try {
            activity?.startLockTask()
        } catch (e: Exception) {
            Toast.makeText(context, "Lock Task Mode dimulai. Beberapa fitur bawaan HP dinonaktifkan.", Toast.LENGTH_SHORT).show()
        }
    }
    
    DisposableEffect(Unit) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            if (MainActivity.isExamActive) {
                try {
                    val clip = clipboardManager?.primaryClip
                    val hasText = clip != null && clip.itemCount > 0 && !clip.getItemAt(0).text.isNullOrEmpty()
                    if (hasText) {
                        clearExamClipboard(context)
                        Toast.makeText(context, "Copy/Paste diblokir selama ujian!", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                }
            }
        }
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {
        }

        onDispose {
            MainActivity.isExamActive = false
            try {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } catch (_: Exception) {
            }
            try {
                clipboardManager?.removePrimaryClipChangedListener(clipListener)
            } catch (_: Exception) {
            }
            clearExamClipboard(context)
            try {
                activity?.stopLockTask()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }



    var showExitDialog by remember { mutableStateOf(false) }

    // Intercept physical Back button press during an active exam to prevent student from exiting (Lock it completely)
    BackHandler(enabled = true) {
        Toast.makeText(context, "Navigasi tidak diizinkan!", Toast.LENGTH_SHORT).show()
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Keluar Ujian") },
            text = { Text("Apakah Anda yakin ingin keluar dari halaman ujian yang aman?") },
            confirmButton = {
                TextButton(onClick = { 
                    MainActivity.isExamActive = false
                    MainActivity.instance?.speak("Anda keluar dari ujian")
                    showExitDialog = false
                    onFinishExam() 
                }) {
                    Text("Keluar")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    MainActivity.instance?.stopSpeaking()
                    showExitDialog = false 
                }) {
                    Text("Batal")
                }
            }
        )
    }

    // Settings Dialog for Watermark & Running Text adjustments
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("⚙️ Pengaturan Running Text Nama") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Nama Siswa (Running Text):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = studentName,
                        onValueChange = { studentName = it },
                        label = { Text("Nama Siswa") },
                        placeholder = { Text("Otomatis dari Google Form / Ketik Nama") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Text("Transparansi Watermark Nama:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = watermarkOpacity == 0.12f, onClick = { watermarkOpacity = 0.12f }, label = { Text("Tipis (12%)") })
                        FilterChip(selected = watermarkOpacity == 0.20f, onClick = { watermarkOpacity = 0.20f }, label = { Text("Sedang (20%)") })
                        FilterChip(selected = watermarkOpacity == 0.35f, onClick = { watermarkOpacity = 0.35f }, label = { Text("Jelas (35%)") })
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Ukuran Teks Watermark:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = watermarkTextSizeSp == 18, onClick = { watermarkTextSizeSp = 18 }, label = { Text("Kecil") })
                        FilterChip(selected = watermarkTextSizeSp == 22, onClick = { watermarkTextSizeSp = 22 }, label = { Text("Sedang") })
                        FilterChip(selected = watermarkTextSizeSp == 28, onClick = { watermarkTextSizeSp = 28 }, label = { Text("Besar") })
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Kecepatan Running Text:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = watermarkSpeedSeconds == 16, onClick = { watermarkSpeedSeconds = 16 }, label = { Text("Lambat") })
                        FilterChip(selected = watermarkSpeedSeconds == 12, onClick = { watermarkSpeedSeconds = 12 }, label = { Text("Sedang") })
                        FilterChip(selected = watermarkSpeedSeconds == 8, onClick = { watermarkSpeedSeconds = 8 }, label = { Text("Cepat") })
                    }


                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Selesai")
                }
            }
        )
    }

    // Violation Warning Dialog (Unlimited count, no lockout)
    if (MainActivity.showWarningDialog) {
        AlertDialog(
            onDismissRequest = { /* forces student to click button */ },
            title = { Text("⚠️ Peringatan Pelanggaran (Ke-${MainActivity.violationCount})") },
            text = { 
                Text(
                    "Anda terdeteksi keluar dari aplikasi atau mencoba melakukan kecurangan!\n\n" +
                    "Tindakan: ${MainActivity.lastViolationReason}\n\n" +
                    "Harap tetap berada di dalam aplikasi ini selama ujian berlangsung demi keandalan nilai Anda."
                ) 
            },
            confirmButton = {
                Button(
                    onClick = { MainActivity.showWarningDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Saya Mengerti & Kembali ke Ujian")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SecureExamWebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(true)
                    
                    addJavascriptInterface(WebAppInterface { name ->
                        studentName = name
                    }, "Android")

                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageStarted(view: android.webkit.WebView, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            injectAntiCopyProtection(view)
                        }

                        override fun doUpdateVisitedHistory(view: android.webkit.WebView, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            injectAntiCopyProtection(view)
                        }

                        override fun onPageFinished(view: android.webkit.WebView, url: String) {
                            super.onPageFinished(view, url)
                            injectAntiCopyProtection(view)
                            view.evaluateJavascript(
                                """
                                (function() {
                                    function extractName() {
                                        // Scan Google Form items
                                        var items = document.querySelectorAll('div[role="listitem"], div[data-params], div[role="list"] > div');
                                        for (var i = 0; i < items.length; i++) {
                                            var item = items[i];
                                            var itemText = (item.innerText || "").toLowerCase();
                                            if (itemText.indexOf('nama') !== -1 || itemText.indexOf('siswa') !== -1 || itemText.indexOf('peserta') !== -1) {
                                                // Check text input
                                                var input = item.querySelector('input[type="text"], input:not([type="hidden"]), textarea');
                                                if (input && input.value && input.value.trim() !== "") {
                                                    window.Android.sendStudentName(input.value.trim());
                                                    return;
                                                }
                                                // Check checked radio
                                                var radio = item.querySelector('div[role="radio"][aria-checked="true"]');
                                                if (radio) {
                                                    var val = radio.getAttribute('data-value') || radio.innerText;
                                                    if (val && val.trim() !== "") {
                                                        window.Android.sendStudentName(val.trim());
                                                        return;
                                                    }
                                                }
                                                // Check dropdown selected option
                                                var option = item.querySelector('div[role="option"][aria-selected="true"], div[role="listbox"] div[aria-selected="true"]');
                                                if (option) {
                                                    var val = option.getAttribute('data-value') || option.innerText;
                                                    if (val && val.trim() !== "" && val.toLowerCase().indexOf('pilih') === -1) {
                                                        window.Android.sendStudentName(val.trim());
                                                        return;
                                                    }
                                                }
                                                // Check combobox text
                                                var combobox = item.querySelector('div[role="combobox"]');
                                                if (combobox) {
                                                    var val = combobox.innerText;
                                                    if (val && val.trim() !== "" && val.toLowerCase().indexOf('pilih') === -1) {
                                                        window.Android.sendStudentName(val.trim());
                                                        return;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    setInterval(extractName, 1500);
                                })();
                                """.trimIndent(), null
                            )
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            if (view != null && newProgress >= 20) {
                                injectAntiCopyProtection(view)
                            }
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { webView ->
                // Ensure it loads the URL on update if needed
            }
        )
        
        // Watermark Overlay
        WatermarkOverlay(
            studentName = studentName,
            opacity = watermarkOpacity,
            textSizeSp = watermarkTextSizeSp,
            speedSeconds = watermarkSpeedSeconds
        )
        
        // Status Bar (Settings) di pojok kiri atas
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                androidx.compose.material3.IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.size(22.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "Pengaturan Display",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Tombol Exit di pojok kanan atas dengan icon panah merah
        androidx.compose.material3.IconButton(
            onClick = { 
                MainActivity.instance?.speak("Anda keluar dari ujian")
                showExitDialog = true 
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.ArrowForward,
                contentDescription = "Exit Ujian",
                tint = Color.Red
            )
        }
    }
}
