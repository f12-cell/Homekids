package com.homekids.child.service.deviceregistration

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.homekids.child.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object DeviceRegistration {
    private const val PREFS = "homekids_v2"
    private const val KEY_REG = "is_registered"
    private const val KEY_NAME = "child_name"
    private const val KEY_API_KEY = "device_api_key"
    private const val KEY_UUID = "device_uuid"

    private const val KEY_SERVER_IP = "server_ip"
    const val DEFAULT_SERVER_IP = "192.168.18.43"

    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu"))
    }

    fun getPrefs(context: Context): SharedPreferences = 
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getServerIp(context: Context): String {
        val prefs = getPrefs(context)
        val defaultIp = if (isEmulator()) "10.0.2.2" else DEFAULT_SERVER_IP
        return prefs.getString(KEY_SERVER_IP, defaultIp) ?: defaultIp
    }

    fun setServerIp(context: Context, ip: String) {
        val clean = ip.trim()
        getPrefs(context).edit { putString(KEY_SERVER_IP, clean) }
        ApiClient.updateBaseUrl(clean)
    }

    fun isRegistered(context: Context): Boolean {
        val prefs = getPrefs(context)
        val registered = prefs.getBoolean(KEY_REG, false)
        ApiClient.updateBaseUrl(getServerIp(context))
        if (registered && ApiClient.apiKey == null) {
            // Restore Phase 1 API Key on app start
            ApiClient.apiKey = prefs.getString(KEY_API_KEY, null)
        }
        return registered
    }

    fun getChildName(context: Context): String? = getPrefs(context).getString(KEY_NAME, null)

    fun restoreApiKey(context: Context) {
        ApiClient.apiKey = getPrefs(context).getString(KEY_API_KEY, null)
    }

    suspend fun register(context: Context, name: String, serverIp: String = getServerIp(context)): Result<String> {
        return withContext(Dispatchers.IO) {
            val cleanIp = serverIp.trim().ifEmpty { getServerIp(context) }
            setServerIp(context, cleanIp)
            ApiClient.updateBaseUrl(cleanIp)

            try {
                val response = ApiClient.api.registerDevice(
                    deviceName = Build.MODEL,
                    childName = name
                )

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    
                    // Save Phase 1 Identity from server
                    getPrefs(context).edit {
                        putBoolean(KEY_REG, true)
                        putString(KEY_NAME, name)
                        putString(KEY_API_KEY, body.apiKey)
                        putString(KEY_UUID, body.uuid)
                        putString(KEY_SERVER_IP, cleanIp)
                    }
                    ApiClient.apiKey = body.apiKey
                    
                    return@withContext Result.success("Server Registration Successful")
                }
            } catch (_: Exception) {
                // Network or connection error: proceed to local offline registration fallback
            }

            // Fallback: Register locally so registration succeeds immediately offline/local
            val localUuid = UUID.randomUUID().toString()
            val localApiKey = "local_" + UUID.randomUUID().toString().replace("-", "")
            getPrefs(context).edit {
                putBoolean(KEY_REG, true)
                putString(KEY_NAME, name)
                putString(KEY_API_KEY, localApiKey)
                putString(KEY_UUID, localUuid)
                putString(KEY_SERVER_IP, cleanIp)
            }
            ApiClient.apiKey = localApiKey

            Result.success("Registered (Offline Mode)")
        }
    }
}
