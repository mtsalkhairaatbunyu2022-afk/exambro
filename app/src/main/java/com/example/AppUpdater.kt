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
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

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
        private const val KEY_SAVED_UPDATE_CODE = "saved_update_code"
        private const val KEY_SAVED_UPDATE_NAME = "saved_update_name"
        private const val KEY_SAVED_UPDATE_APK = "saved_update_apk"
        private const val KEY_SAVED_UPDATE_NOTES = "saved_update_notes"
        private const val PLACEHOLDER_DOMAIN = "DOMAIN-CLOUDFLARE-EXAMBRO-ANDA.pages.dev"
        private const val TIMEOUT_SECONDS = 15L

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(20L, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private fun isPlaceholderUrl(url: String): Boolean {
        return url.isBlank() ||
            url.contains(PLACEHOLDER_DOMAIN, ignoreCase = true) ||
            url.contains("your-project.pages.dev", ignoreCase = true) ||
            url.contains("your-subdomain.workers.dev", ignoreCase = true)
    }

    fun getManifestUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_MANIFEST_URL, null)?.trim()
        if (!saved.isNullOrBlank() && !isPlaceholderUrl(saved)) {
            return saved
        }
        return context.getString(R.string.cloudflare_update_url).trim()
    }

    fun saveManifestUrl(context: Context, rawUrl: String): String {
        val normalized = normalizeManifestUrl(rawUrl)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (normalized.isBlank() || isPlaceholderUrl(normalized)) {
            prefs.edit().remove(KEY_MANIFEST_URL).apply()
        } else {
            prefs.edit().putString(KEY_MANIFEST_URL, normalized).apply()
        }
        return normalized
    }

    fun savePendingUpdate(context: Context, info: RemoteVersionInfo) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_SAVED_UPDATE_CODE, info.versionCode)
            .putString(KEY_SAVED_UPDATE_NAME, info.versionName)
            .putString(KEY_SAVED_UPDATE_APK, info.apkUrl)
            .putString(KEY_SAVED_UPDATE_NOTES, info.releaseNotes)
            .apply()
    }

    fun clearPendingUpdate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_SAVED_UPDATE_CODE)
            .remove(KEY_SAVED_UPDATE_NAME)
            .remove(KEY_SAVED_UPDATE_APK)
            .remove(KEY_SAVED_UPDATE_NOTES)
            .apply()
    }

    fun getSavedPendingUpdate(context: Context): RemoteVersionInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getInt(KEY_SAVED_UPDATE_CODE, 0)
        return if (code > BuildConfig.VERSION_CODE) {
            val name = prefs.getString(KEY_SAVED_UPDATE_NAME, code.toString()) ?: code.toString()
            val apk = prefs.getString(KEY_SAVED_UPDATE_APK, "") ?: ""
            val notes = prefs.getString(KEY_SAVED_UPDATE_NOTES, "") ?: ""
            RemoteVersionInfo(
                versionCode = code,
                versionName = name,
                apkUrl = apk,
                releaseNotes = notes
            )
        } else {
            if (code > 0) {
                clearPendingUpdate(context)
                UpdateNotificationHelper.cancelUpdateNotification(context)
            }
            null
        }
    }

    fun isConfigured(context: Context): Boolean {
        val url = getManifestUrl(context)
        return url.startsWith("https://", ignoreCase = true) && !isPlaceholderUrl(url)
    }

    fun normalizeManifestUrl(rawUrl: String): String {
        var url = rawUrl.trim().replace(" ", "")
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }
        val clean = url.trimEnd('/')
        return if (clean.endsWith(".json", ignoreCase = true)) clean else "$clean/version.json"
    }

    suspend fun checkAppUpdate(context: Context): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        val baseManifestUrl = getManifestUrl(context)
        if (isPlaceholderUrl(baseManifestUrl)) {
            return@withContext UpdateCheckOutcome.NotConfigured
        }

        try {
            val separator = if (baseManifestUrl.contains("?")) "&" else "?"
            val requestUrl = "$baseManifestUrl${separator}t=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(requestUrl)
                .get()
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("User-Agent", "Exambro-Android/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckOutcome.Failure(
                        "Server update merespons kode ${response.code}. Coba lagi nanti."
                    )
                }

                val responseText = response.body?.string().orEmpty()
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
                    val info = RemoteVersionInfo(
                        versionCode = serverVersionCode,
                        versionName = serverVersionName,
                        apkUrl = apkUrl,
                        releaseNotes = releaseNotes
                    )
                    savePendingUpdate(context, info)
                    UpdateNotificationHelper.showUpdateNotification(context, info)
                    UpdateCheckOutcome.UpdateAvailable(info)
                } else {
                    clearPendingUpdate(context)
                    UpdateNotificationHelper.cancelUpdateNotification(context)
                    UpdateCheckOutcome.UpToDate(serverVersionName = serverVersionName)
                }
            }
        } catch (_: SocketTimeoutException) {
            UpdateCheckOutcome.Failure(
                "Koneksi ke server update terlalu lama (timeout 15 detik). Coba lagi."
            )
        } catch (_: UnknownHostException) {
            UpdateCheckOutcome.Failure(
                "Alamat server update tidak terjangkau (offline atau DNS bermasalah)."
            )
        } catch (e: SSLException) {
            UpdateCheckOutcome.Failure(
                "Koneksi HTTPS ke server update gagal (${e.localizedMessage ?: "SSL error"})."
            )
        } catch (e: Exception) {
            UpdateCheckOutcome.Failure(
                "Gagal memeriksa pembaruan (${e.javaClass.simpleName}: ${e.localizedMessage ?: "cek koneksi"})."
            )
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

    private var lastAutoCheckMs = 0L

    init {
        val savedPending = repository.getSavedPendingUpdate(application)
        if (savedPending != null) {
            _uiState.update {
                it.copy(
                    hasUpdate = true,
                    remoteInfo = savedPending,
                    statusMessage = "Versi baru v${savedPending.versionName} tersedia!"
                )
            }
            UpdateNotificationHelper.showUpdateNotification(application, savedPending)
        } else {
            UpdateNotificationHelper.cancelUpdateNotification(application)
        }
    }

    fun triggerAutoCheckIfNeeded(force: Boolean = false) {
        if (MainActivity.isExamActive) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAutoCheckMs < 60_000L) return
        lastAutoCheckMs = now
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

    fun showUpdateDialog() {
        _uiState.update {
            if (it.hasUpdate && it.remoteInfo != null) {
                it.copy(showUpdateDialog = true)
            } else {
                it
            }
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
