package com.homekids.child.service

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.edit
import com.homekids.child.model.ActivityRequest
import com.homekids.child.service.deviceregistration.DeviceRegistration
import java.text.SimpleDateFormat
import java.util.*

class LocalStatsManager(val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("local_stats_v2", Context.MODE_PRIVATE)
    private val dbHelper = StatsDbHelper(context)

    companion object {
        private const val KEY_BLOCKED_COUNT = "blocked_count"
        private const val KEY_LAST_SYNC = "last_sync"
    }

    fun incrementBlockedCount() {
        val current = prefs.getInt(KEY_BLOCKED_COUNT, 0)
        prefs.edit { putInt(KEY_BLOCKED_COUNT, current + 1) }
    }

    fun getBlockedCount(): Int = prefs.getInt(KEY_BLOCKED_COUNT, 0)

    fun resetBlockedCount() {
        prefs.edit { putInt(KEY_BLOCKED_COUNT, 0) }
    }

    fun updateLastSync() {
        prefs.edit { putLong(KEY_LAST_SYNC, System.currentTimeMillis()) }
    }

    fun getLastSync(): Long = prefs.getLong(KEY_LAST_SYNC, 0)

    fun logActivity(domain: String, appName: String, status: String) {
        try {
            val db = dbHelper.writableDatabase
            val ts = System.currentTimeMillis()
            
            // 1. Store in local persistent queue (Phase 1)
            val values = ContentValues().apply {
                put("domain", domain)
                put("app", appName)
                put("ts", ts)
                put("status", status)
            }
            db.insert("pending_activity", null, values)
            
            // 2. Local stats for dashboard
            val actValues = ContentValues().apply {
                put("domain", domain)
                put("app", appName)
                put("ts", ts)
                put("status", status)
            }
            db.insert("activity", null, actValues)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getPendingActivity(): List<ActivityRequest> {
        val list = mutableListOf<ActivityRequest>()
        val childName = DeviceRegistration.getChildName(context)
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT id, domain, app, ts, status FROM pending_activity ORDER BY ts ASC LIMIT 50", null)
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            
            while (cursor.moveToNext()) {
                val rawTs = cursor.getLong(3)
                val formattedTs = if (rawTs > 0) df.format(Date(rawTs)) else df.format(Date())
                val statusVal = try { cursor.getString(4) } catch (_: Exception) { null } ?: "allowed"
                list.add(ActivityRequest(
                    domain = cursor.getString(1),
                    app = cursor.getString(2),
                    timestamp = formattedTs,
                    status = statusVal,
                    child = childName
                ))
            }
            cursor.close()
        } catch (e: Exception) {}
        return list
    }

    fun getRecentActivity(limit: Int = 100): List<ActivityRequest> {
        val list = mutableListOf<ActivityRequest>()
        val childName = DeviceRegistration.getChildName(context)
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT domain, app, ts, status FROM activity ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            while (cursor.moveToNext()) {
                val rawTs = cursor.getLong(2)
                val formattedTs = if (rawTs > 0) df.format(Date(rawTs)) else df.format(Date())
                val statusVal = try { cursor.getString(3) } catch (_: Exception) { null } ?: "allowed"
                list.add(ActivityRequest(
                    domain = cursor.getString(0),
                    app = cursor.getString(1),
                    timestamp = formattedTs,
                    status = statusVal,
                    child = childName
                ))
            }
            cursor.close()
        } catch (e: Exception) {}
        return list
    }

    fun clearPendingActivity(count: Int) {
        try {
            val db = dbHelper.writableDatabase
            // Delete oldest X records
            db.execSQL("DELETE FROM pending_activity WHERE id IN (SELECT id FROM pending_activity ORDER BY ts ASC LIMIT ?)", 
                      arrayOf(count))
        } catch (e: Exception) {}
    }

    fun getTopApps(): List<AppStat> {
        val apps = mutableListOf<AppStat>()
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT app, domain, COUNT(*) as count FROM activity GROUP BY app ORDER BY count DESC LIMIT 3", null)
            while (cursor.moveToNext()) {
                apps.add(AppStat(cursor.getString(0), cursor.getString(1), cursor.getInt(2)))
            }
            cursor.close()
        } catch (e: Exception) {}
        return apps
    }

    fun getCategoryBreakdown(): Map<String, Int> {
        val breakdown = mutableMapOf("Social" to 0, "Gaming" to 0, "Education" to 0, "Other" to 0)
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT app, COUNT(*) FROM activity GROUP BY app", null)
            while (cursor.moveToNext()) {
                val cat = getCategory(cursor.getString(0))
                breakdown[cat] = (breakdown[cat] ?: 0) + cursor.getInt(1)
            }
            cursor.close()
        } catch (e: Exception) {}
        return breakdown
    }

    private fun getCategory(appName: String): String {
        return when (appName) {
            "TikTok", "Instagram", "Facebook", "Snapchat", "Twitter" -> "Social"
            "Roblox", "Steam", "Twitch" -> "Gaming"
            "Google" -> "Education"
            else -> "Other"
        }
    }

    data class AppStat(val name: String, val domain: String, val count: Int)

    private class StatsDbHelper(context: Context) : SQLiteOpenHelper(context, "homekids_local.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS activity (id INTEGER PRIMARY KEY AUTOINCREMENT, domain TEXT, app TEXT, ts LONG, status TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_activity (id INTEGER PRIMARY KEY AUTOINCREMENT, domain TEXT, app TEXT, ts LONG, status TEXT)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("CREATE TABLE IF NOT EXISTS activity (id INTEGER PRIMARY KEY AUTOINCREMENT, domain TEXT, app TEXT, ts LONG, status TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_activity (id INTEGER PRIMARY KEY AUTOINCREMENT, domain TEXT, app TEXT, ts LONG, status TEXT)")
            try { db.execSQL("ALTER TABLE activity ADD COLUMN status TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE pending_activity ADD COLUMN status TEXT") } catch (_: Exception) {}
        }
    }
}
