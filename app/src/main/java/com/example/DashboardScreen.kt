package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

data class DeviceCheckSnapshot(
    val isConnected: Boolean,
    val connectionLabel: String,
    val batteryPercent: Int,
    val environmentViolations: List<String>,
    val blockedApps: List<BlockedApp>
)

private fun readDeviceCheckSnapshot(context: Context): DeviceCheckSnapshot {
    var isConnected = false
    var connectionLabel = "Offline (Tidak terhubung)"
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(network)
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            isConnected = true
            connectionLabel = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Terhubung (Wi-Fi)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Terhubung (Data Seluler)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Terhubung (Ethernet)"
                else -> "Terhubung (Online)"
            }
        }
    } catch (_: Exception) {
    }

    var batteryPercent = -1
    try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        batteryPercent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    } catch (_: Exception) {
    }

    val envViolations = try {
        AntiCheatEngine.scanEnvironment(context)
    } catch (_: Exception) {
        emptyList()
    }
    val blockedApps = try {
        getDetectedBlockedApps(context)
    } catch (_: Exception) {
        emptyList()
    }

    return DeviceCheckSnapshot(
        isConnected = isConnected,
        connectionLabel = connectionLabel,
        batteryPercent = batteryPercent,
        environmentViolations = envViolations,
        blockedApps = blockedApps
    )
}

@Composable
fun DashboardScreen(
    onStartExam: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboardManager = LocalClipboardManager.current

    var deviceSnapshot by remember { mutableStateOf(readDeviceCheckSnapshot(context)) }
    var manualUrl by remember { mutableStateOf("https://google.com") }
    var urlError by remember { mutableStateOf(false) }

    var showInputLinkDialog by remember { mutableStateOf(false) }
    var showDeviceStatusDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }

    val updateViewModel: AppUpdateViewModel = viewModel()
    val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var customServerUrl by remember { mutableStateOf(updateViewModel.getCurrentManifestUrl()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                deviceSnapshot = readDeviceCheckSnapshot(context)
                updateViewModel.triggerAutoCheckIfNeeded(force = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showQrScanner by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showQrScanner = true
        } else {
            Toast.makeText(context, "Izin kamera diperlukan untuk scan QR Code.", Toast.LENGTH_LONG).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && updateState.hasUpdate && updateState.remoteInfo != null) {
            UpdateNotificationHelper.showUpdateNotification(context, updateState.remoteInfo!!)
        }
    }

    fun launchQrScanner() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showQrScanner = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val activity = context as? Activity
        val shouldShowFromNotification = activity?.intent?.getBooleanExtra("SHOW_UPDATE_DIALOG", false) == true
        if (shouldShowFromNotification) {
            activity?.intent?.removeExtra("SHOW_UPDATE_DIALOG")
            updateViewModel.showUpdateDialog()
        }
        deviceSnapshot = readDeviceCheckSnapshot(context)
        updateViewModel.triggerAutoCheckIfNeeded(force = true)
    }

    LaunchedEffect(updateState.hasUpdate, updateState.remoteInfo) {
        if (updateState.hasUpdate && updateState.remoteInfo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    UpdateNotificationHelper.showUpdateNotification(context, updateState.remoteInfo!!)
                }
            } else {
                UpdateNotificationHelper.showUpdateNotification(context, updateState.remoteInfo!!)
            }
        }
    }

    if (showQrScanner) {
        QrScannerScreen(
            onQrCodeScanned = { scannedUrl ->
                showQrScanner = false
                try {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
                    val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                    toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 250)
                } catch (_: Exception) {
                }
                Toast.makeText(context, "Berhasil dipindai! Membuka halaman ujian...", Toast.LENGTH_SHORT).show()
                onStartExam(scannedUrl)
            },
            onClose = {
                showQrScanner = false
            }
        )
        return
    }

    // Dialog 1: Input Link / Token Ujian
    if (showInputLinkDialog) {
        AlertDialog(
            onDismissRequest = { showInputLinkDialog = false },
            title = { Text("Input Link / Token Ujian", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Tempel atau ketik alamat link Google Form / CBT server ujian yang diberikan pengawas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = manualUrl,
                        onValueChange = {
                            manualUrl = it
                            urlError = false
                        },
                        label = { Text("Link Ujian (URL)") },
                        placeholder = { Text("https://forms.gle/...") },
                        isError = urlError,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("exam_url_input")
                    )
                    OutlinedButton(
                        onClick = {
                            val clipData = clipboardManager.getText()
                            if (clipData != null && clipData.text.isNotBlank()) {
                                manualUrl = clipData.text.toString().trim()
                                urlError = false
                                Toast.makeText(context, "Link berhasil ditempel!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Papan klip kosong!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("paste_link_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Tempel Link dari Clipboard", fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        var trimmed = manualUrl.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
                            trimmed = "http://$trimmed"
                        }
                        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                            showInputLinkDialog = false
                            onStartExam(trimmed)
                        } else {
                            urlError = true
                            Toast.makeText(context, "URL harus menggunakan http:// atau https://", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("enter_exam_button")
                ) {
                    Text("Masuk Ujian", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showInputLinkDialog = false },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Batal")
                }
            }
        )
    }

    // Dialog 2: Cek Status Koneksi & Perangkat
    if (showDeviceStatusDialog) {
        val isDeviceClean = deviceSnapshot.environmentViolations.isEmpty() && deviceSnapshot.blockedApps.isEmpty()
        AlertDialog(
            onDismissRequest = { showDeviceStatusDialog = false },
            title = { Text("Status Koneksi & Perangkat", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DeviceStatusRow(
                        icon = if (deviceSnapshot.isConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                        title = "Koneksi Internet",
                        subtitle = deviceSnapshot.connectionLabel,
                        isHealthy = deviceSnapshot.isConnected
                    )
                    DeviceStatusRow(
                        icon = Icons.Filled.BatteryChargingFull,
                        title = "Daya Baterai",
                        subtitle = if (deviceSnapshot.batteryPercent >= 0) "${deviceSnapshot.batteryPercent}% tersedia" else "Tidak terbaca",
                        isHealthy = deviceSnapshot.batteryPercent < 0 || deviceSnapshot.batteryPercent >= 20
                    )
                    DeviceStatusRow(
                        icon = Icons.Filled.Security,
                        title = "Keamanan Sistem",
                        subtitle = if (deviceSnapshot.environmentViolations.isEmpty()) {
                            "Perangkat aman (Non-Root & siap ujian)"
                        } else {
                            deviceSnapshot.environmentViolations.joinToString(", ")
                        },
                        isHealthy = deviceSnapshot.environmentViolations.isEmpty()
                    )
                    DeviceStatusRow(
                        icon = if (isDeviceClean) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        title = "Aplikasi AI / Terlarang",
                        subtitle = if (deviceSnapshot.blockedApps.isEmpty()) {
                            "Tidak ada aplikasi terlarang terdeteksi"
                        } else {
                            "Terdeteksi: ${deviceSnapshot.blockedApps.joinToString { it.name }}"
                        },
                        isHealthy = deviceSnapshot.blockedApps.isEmpty()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        deviceSnapshot = readDeviceCheckSnapshot(context)
                        Toast.makeText(context, "Status diperbarui", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Muat Ulang")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeviceStatusDialog = false },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Tutup")
                }
            }
        )
    }

    // Dialog 3: Panduan / Tata Tertib Ujian
    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = { Text("Panduan & Tata Tertib Ujian", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GuideRuleItem("1", "Pastikan koneksi internet stabil dan baterai minimal 20% sebelum memulai ujian.")
                    GuideRuleItem("2", "Pilih 'Masuk Ujian / Scan QR' untuk memindai QR Code dari pengawas, atau 'Input Link / Token Ujian' untuk menempel link ujian.")
                    GuideRuleItem("3", "Selama ujian berlangsung, layar akan masuk ke Mode Aman (anti-screenshot, anti-copy-paste, dan penguncian layar).")
                    GuideRuleItem("4", "Dilarang menekan tombol Home, membuka Recent Apps, split-screen, atau membuka aplikasi mengambang.")
                    GuideRuleItem("5", "Setelah selesai mengirim jawaban ujian, tekan tombol panah merah di pojok kanan atas untuk keluar dengan aman.")
                }
            },
            confirmButton = {
                Button(
                    onClick = { showGuideDialog = false },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Saya Mengerti")
                }
            }
        )
    }

    // Dialog 4: Pengaturan URL Cloudflare Pages
    if (showServerConfigDialog) {
        AlertDialog(
            onDismissRequest = { showServerConfigDialog = false },
            title = { Text("Server Update Cloudflare Pages") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Masukkan URL Cloudflare Pages Anda yang menyajikan file version.json:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = customServerUrl,
                        onValueChange = { customServerUrl = it },
                        label = { Text("URL Cloudflare Pages") },
                        placeholder = { Text("https://DOMAIN-CLOUDFLARE-EXAMBRO-ANDA.pages.dev/version.json") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cloudflare_url_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val saved = updateViewModel.saveManifestUrlAndCheck(customServerUrl)
                        customServerUrl = saved
                        showServerConfigDialog = false
                        if (saved.isBlank()) {
                            Toast.makeText(context, "URL tidak boleh kosong", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("save_cloudflare_url_button")
                ) {
                    Text("Simpan & Cek")
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerConfigDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Dialog 5: Update Tersedia / Sudah Terbaru / Belum Diatur
    if (updateState.showUpdateDialog && updateState.remoteInfo != null) {
        val remote = updateState.remoteInfo!!
        AlertDialog(
            onDismissRequest = { updateViewModel.dismissDialogs() },
            title = { Text("Pembaruan Tersedia (v${remote.versionName})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Versi aktif: v${updateState.currentVersionName} (${updateState.currentVersionCode})\n" +
                            "Versi terbaru: v${remote.versionName} (${remote.versionCode})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (remote.releaseNotes.isNotBlank()) {
                        Text(
                            text = "Catatan Pembaruan:\n${remote.releaseNotes}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val opened = updateViewModel.openApkDownloadUrl(context, remote.apkUrl)
                        if (!opened) {
                            Toast.makeText(context, "Tidak dapat membuka tautan pembaruan.", Toast.LENGTH_SHORT).show()
                        }
                        updateViewModel.dismissDialogs()
                    },
                    modifier = Modifier.testTag("update_now_button")
                ) {
                    Text("Perbarui Sekarang")
                }
            },
            dismissButton = {
                TextButton(onClick = { updateViewModel.dismissDialogs() }) {
                    Text("Nanti")
                }
            }
        )
    }

    if (updateState.showUpToDateDialog) {
        AlertDialog(
            onDismissRequest = { updateViewModel.dismissDialogs() },
            title = { Text("Aplikasi Sudah Terbaru") },
            text = {
                Text(
                    text = "Aplikasi sudah menggunakan versi paling baru (v${updateState.currentVersionName}).",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = { updateViewModel.dismissDialogs() }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        updateViewModel.dismissDialogs()
                        customServerUrl = updateViewModel.getCurrentManifestUrl()
                        showServerConfigDialog = true
                    }
                ) {
                    Text("Atur URL")
                }
            }
        )
    }

    if (updateState.showNotConfiguredDialog) {
        AlertDialog(
            onDismissRequest = { updateViewModel.dismissDialogs() },
            title = { Text("Server Update Belum Diatur") },
            text = {
                Text(
                    text = "Domain Cloudflare Pages masih menggunakan placeholder. Tekan 'Atur URL Cloudflare' untuk memasukkan domain Cloudflare Pages Anda.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        updateViewModel.dismissDialogs()
                        customServerUrl = updateViewModel.getCurrentManifestUrl()
                        showServerConfigDialog = true
                    }
                ) {
                    Text("Atur URL Cloudflare")
                }
            },
            dismissButton = {
                TextButton(onClick = { updateViewModel.dismissDialogs() }) {
                    Text("Tutup")
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Header / Banner Identitas Sekolah (Konsep E-Book Sekolah)
                SchoolIdentityBanner(
                    isOnline = deviceSnapshot.isConnected,
                    connectionText = if (deviceSnapshot.isConnected) "Koneksi Siap" else "Mode Offline"
                )

                // Running Text 3D "CBT MTs Bunyu"
                RunningText3D()

                // 2. Judul Seksi Menu Utama
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Menu Utama Ujian",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Pilih akses ujian",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Grid 2x2 Kartu Menu Utama Interaktif
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DashboardMenuTile(
                            title = "Masuk Ujian /\nScan QR",
                            subtitle = "Pindai QR Pengawas",
                            icon = Icons.Filled.QrCodeScanner,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("menu_scan_qr"),
                            onClick = { launchQrScanner() }
                        )
                        DashboardMenuTile(
                            title = "Input Link /\nToken Ujian",
                            subtitle = "Tempel URL Google Form",
                            icon = Icons.Filled.Link,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("menu_input_link"),
                            onClick = { showInputLinkDialog = true }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DashboardMenuTile(
                            title = "Cek Status\nKoneksi / HP",
                            subtitle = if (deviceSnapshot.isConnected) "Jaringan Aktif" else "Periksa Jaringan",
                            icon = Icons.Filled.VerifiedUser,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("menu_device_status"),
                            onClick = {
                                deviceSnapshot = readDeviceCheckSnapshot(context)
                                showDeviceStatusDialog = true
                            }
                        )
                        DashboardMenuTile(
                            title = "Panduan /\nTata Tertib",
                            subtitle = "Aturan Ujian CBT",
                            icon = Icons.Filled.MenuBook,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("menu_exam_guide"),
                            onClick = { showGuideDialog = true }
                        )
                    }
                }

                // 3. Footer Ringkas: Status Versi Aplikasi & Tombol Cek Update
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "EXAMBRO MTs Alkhairaat Bunyu",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Versi Aktif: v${updateState.currentVersionName} (Build ${updateState.currentVersionCode})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    customServerUrl = updateViewModel.getCurrentManifestUrl()
                                    showServerConfigDialog = true
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("update_server_settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Pengaturan URL Update Cloudflare",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        val statusMsg = updateState.statusMessage
                        if (!statusMsg.isNullOrBlank()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Text(
                                text = statusMsg,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (updateState.hasUpdate) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    updateState.isError -> MaterialTheme.colorScheme.error
                                    updateState.hasUpdate -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        FilledTonalButton(
                            onClick = { updateViewModel.checkAppUpdate(isManual = true) },
                            enabled = !updateState.isChecking,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("check_update_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (updateState.isChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Memeriksa Update...", fontWeight = FontWeight.SemiBold)
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.SystemUpdate,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cek Update", fontWeight = FontWeight.Bold)
                            }
                        }

                        val infiniteTransition = rememberInfiniteTransition(label = "update_blink")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 0.2f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 600, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "alpha_blink"
                        )

                        Text(
                            text = if (updateState.hasUpdate) "PEMBAHARUAN TERSEDIA" else "Aplikasi Sudah Versi Terbaru",
                            fontFamily = FontFamily(Font(R.font.times)),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (updateState.hasUpdate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .graphicsLayer(alpha = if (updateState.hasUpdate) alpha else 1f)
                                .testTag("update_status_text")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SchoolIdentityBanner(
    isOnline: Boolean,
    connectionText: String
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0F4C29),
                            Color(0xFF1B6B3A),
                            Color(0xFF2E7D32)
                        )
                    )
                )
        ) {
            // Decorative E-Book School style circles
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 32.dp, y = (-28).dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
            )
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-16).dp, y = 26.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f))
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Logo MTs Alkhairaat Bunyu (Ujung Kiri Aplikasi)
                Surface(
                    modifier = Modifier.size(54.dp),
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 4.dp
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logo_alkhairaat),
                        contentDescription = "Logo MTs Alkhairaat Bunyu",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(5.dp)
                    )
                }

                // Informasi Identitas Sekolah (Bagian Tengah - Rata Tengah)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "KEMENAG • MTs ALKHAIRAAT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = Color(0xFFB8F1C7),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Exambro CBT",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Browser Ujian Aman",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.88f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(99.dp),
                        color = Color.White.copy(alpha = 0.16f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = if (isOnline) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                                contentDescription = null,
                                tint = if (isOnline) Color(0xFFA5F39C) else Color(0xFFFFCC80),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = connectionText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Logo Kemenag RI (Ujung Kanan)
                Surface(
                    modifier = Modifier.size(54.dp),
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 4.dp
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logo_kemenag),
                        contentDescription = "Logo Kementerian Agama RI",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardMenuTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = modifier
            .height(142.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = containerColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title.replace("\n", " "),
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DeviceStatusRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isHealthy: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isHealthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GuideRuleItem(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(22.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
