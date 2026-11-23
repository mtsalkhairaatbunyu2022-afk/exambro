package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class RemoteVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String
)

sealed class UpdateCheckOutcome {
    data class UpdateAvailable(val info: RemoteVersionInfo) : UpdateCheckOutcome()
    data class UpToDate(val serverVersionName: String) : UpdateCheckOutcome()
    data object NotConfigured : UpdateCheckOutcome()
    data class Failure(val message: String) : UpdateCheckOutcome()
}

data class AppUpdateUiState(
    val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    val currentVersionName: String = BuildConfig.VERSION_NAME,
    val isChecking: Boolean = false,
    val hasUpdate: Boolean = false,
    val isUpToDate: Boolean = false,
    val remoteInfo: RemoteVersionInfo? = null,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    val showUpdateDialog: Boolean = false,
    val showUpToDateDialog: Boolean = false,
    val showNotConfiguredDialog: Boolean = false
)

class AppUpdateRepository {
    companion object {
        private const val PREFS_NAME = "exambro_updater_prefs"
        private const val KEY_MANIFEST_URL = "cloudflare_manifest_url"
        private const val PLACEHOLDER_DOMAIN = "DOMAIN-CLOUDFLARE-EXAMBRO-ANDA.pages.dev"
        private const val TIMEOUT_MS = 5000
    }

    fun getManifestUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_MANIFEST_URL, null)?.trim()
        if (!saved.isNullOrBlank()) return saved
        return context.getString(R.string.cloudflare_update_url).trim()
    }

    fun saveManifestUrl(context: Context, rawUrl: String): String {
        val normalized = normalizeManifestUrl(rawUrl)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MANIFEST_URL, normalized).apply()
        return normalized
    }

    fun isConfigured(context: Context): Boolean {
        val url = getManifestUrl(context)
        return url.startsWith("https://", ignoreCase = true) &&
            !url.contains(PLACEHOLDER_DOMAIN, ignoreCase = true) &&
            !url.contains("your-project.pages.dev", ignoreCase = true) &&
            !url.contains("your-subdomain.workers.dev", ignoreCase = true)
    }

    fun normalizeManifestUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }
        val clean = url.trimEnd('/')
        return if (clean.endsWith(".json", ignoreCase = true)) clean else "$clean/version.json"
    }

    suspend fun checkAppUpdate(context: Context): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        val baseManifestUrl = getManifestUrl(context)
        if (baseManifestUrl.isBlank() ||
            baseManifestUrl.contains(PLACEHOLDER_DOMAIN, ignoreCase = true) ||
            baseManifestUrl.contains("your-project.pages.dev", ignoreCase = true) ||
            baseManifestUrl.contains("your-subdomain.workers.dev", ignoreCase = true)
        ) {
            return@withContext UpdateCheckOutcome.NotConfigured
        }

        var connection: HttpURLConnection? = null
        try {
            val separator = if (baseManifestUrl.contains("?")) "&" else "?"
            val requestUrl = "$baseManifestUrl${separator}t=${System.currentTimeMillis()}"
            val url = URL(requestUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Expires", "0")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext UpdateCheckOutcome.Failure(
                    "Server update merespons kode $responseCode. Coba lagi nanti."
                )
            }

            val responseText = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }
            if (responseText.isBlank()) {
                return@withContext UpdateCheckOutcome.Failure("Data versi kosong dari Cloudflare Pages.")
            }

            val json = JSONObject(responseText)
            val serverVersionCode = json.optInt("versionCode", 0)
            val serverVersionName = json.optString("versionName", serverVersionCode.toString())
            var apkUrl = json.optString("apkUrl", "").trim()
            val releaseNotes = json.optString("releaseNotes", "Pembaruan aplikasi Exambro.")

            if (apkUrl.isBlank()) {
                apkUrl = resolveRelativeApkUrl(baseManifestUrl, "exambro-release.apk")
            } else if (apkUrl.startsWith("/")) {
                apkUrl = resolveRelativeApkUrl(baseManifestUrl, apkUrl.removePrefix("/"))
            }

            if (serverVersionCode <= 0) {
                return@withContext UpdateCheckOutcome.Failure("Format version.json tidak valid.")
            }

            if (serverVersionCode > BuildConfig.VERSION_CODE) {
                UpdateCheckOutcome.UpdateAvailable(
                    RemoteVersionInfo(
                        versionCode = serverVersionCode,
                        versionName = serverVersionName,
                        apkUrl = apkUrl,
                        releaseNotes = releaseNotes
                    )
                )
            } else {
                UpdateCheckOutcome.UpToDate(serverVersionName = serverVersionName)
            }
        } catch (e: Exception) {
            UpdateCheckOutcome.Failure(
                "Tidak dapat memeriksa pembaruan (offline atau koneksi lambat)."
            )
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveRelativeApkUrl(manifestUrl: String, fileName: String): String {
        return try {
            val uri = Uri.parse(manifestUrl)
            val scheme = uri.scheme ?: "https"
            val authority = uri.authority ?: return manifestUrl
            "$scheme://$authority/$fileName"
        } catch (_: Exception) {
            manifestUrl
        }
    }
}

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppUpdateRepository()

    private val _uiState = MutableStateFlow(AppUpdateUiState())
    val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

    private var hasPerformedInitialAutoCheck = false

    fun triggerAutoCheckIfNeeded() {
        if (hasPerformedInitialAutoCheck || MainActivity.isExamActive) return
        hasPerformedInitialAutoCheck = true
        if (repository.isConfigured(getApplication())) {
            checkAppUpdate(isManual = false)
        }
    }

    fun checkAppUpdate(isManual: Boolean = true) {
        if (_uiState.value.isChecking || MainActivity.isExamActive) return

        _uiState.update {
            it.copy(
                isChecking = true,
                isError = false,
                statusMessage = if (isManual) "Memeriksa pembaruan..." else it.statusMessage
            )
        }

        viewModelScope.launch {
            val outcome = repository.checkAppUpdate(getApplication())
            _uiState.update { current ->
                when (outcome) {
                    is UpdateCheckOutcome.UpdateAvailable -> {
                        current.copy(
                            isChecking = false,
                            hasUpdate = true,
                            isUpToDate = false,
                            remoteInfo = outcome.info,
                            isError = false,
                            statusMessage = "Versi baru v${outcome.info.versionName} tersedia!",
                            showUpdateDialog = true,
                            showUpToDateDialog = false,
                            showNotConfiguredDialog = false
                        )
                    }
                    is UpdateCheckOutcome.UpToDate -> {
                        current.copy(
                            isChecking = false,
                            hasUpdate = false,
                            isUpToDate = true,
                            isError = false,
                            statusMessage = if (isManual) {
                                "Aplikasi sudah menggunakan versi paling baru (v${BuildConfig.VERSION_NAME})."
                            } else {
                                null
                            },
                            showUpdateDialog = false,
                            showUpToDateDialog = isManual,
                            showNotConfiguredDialog = false
                        )
                    }
                    is UpdateCheckOutcome.NotConfigured -> {
                        current.copy(
                            isChecking = false,
                            hasUpdate = false,
                            isError = isManual,
                            statusMessage = if (isManual) {
                                "Domain Cloudflare Pages belum diatur."
                            } else {
                                null
                            },
                            showNotConfiguredDialog = isManual
                        )
                    }
                    is UpdateCheckOutcome.Failure -> {
                        current.copy(
                            isChecking = false,
                            isError = isManual,
                            statusMessage = if (isManual) outcome.message else current.statusMessage
                        )
                    }
                }
            }
        }
    }

    fun dismissDialogs() {
        _uiState.update {
            it.copy(
                showUpdateDialog = false,
                showUpToDateDialog = false,
                showNotConfiguredDialog = false
            )
        }
    }

    fun getCurrentManifestUrl(): String = repository.getManifestUrl(getApplication())

    fun saveManifestUrlAndCheck(rawUrl: String): String {
        val saved = repository.saveManifestUrl(getApplication(), rawUrl)
        if (saved.isNotBlank()) {
            checkAppUpdate(isManual = true)
        }
        return saved
    }

    fun openApkDownloadUrl(context: Context, apkUrl: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
