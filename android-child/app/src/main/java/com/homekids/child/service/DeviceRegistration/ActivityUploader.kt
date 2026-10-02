package com.homekids.child.service.deviceregistration

import android.content.Context
import android.util.Log
import com.homekids.child.api.ApiClient
import com.homekids.child.service.LocalStatsManager

object ActivityUploader {

    private const val TAG = "ActivityUploader"

    /**
     * Called by HomeKidsVpnService every 10 seconds.
     * Reads pending activity from local DB and uploads to backend.
     */
    suspend fun flushQueue(context: Context) {
        try {
            val stats = LocalStatsManager(context)
            val pending = stats.getPendingActivity()

            if (pending.isEmpty()) return

            val childName = DeviceRegistration.getChildName(context)
            val serverIp = DeviceRegistration.getServerIp(context)
            ApiClient.updateBaseUrl(serverIp)

            val response = ApiClient.api.uploadActivity(pending, childName)

            if (response.isSuccessful) {
                val saved = response.body()?.saved ?: 0
                Log.d(TAG, "Uploaded $saved activity entries")
                // Clear only what we successfully uploaded
                stats.clearPendingActivity(pending.size)
                stats.updateLastSync()
            } else {
                Log.e(TAG, "Upload failed: ${response.code()}")
            }
        } catch (e: Exception) {
            // Don't crash — activity stays in local DB until next flush
            Log.e(TAG, "Flush error: ${e.message}")
        }
    }

    /**
     * Legacy method name used in older VpnService versions.
     */
    suspend fun flushBuffer() {
        // No-op without context — flushQueue(context) is preferred
        Log.d(TAG, "flushBuffer called without context — use flushQueue(context)")
    }
}
