package com.homekids.child.service

import android.content.Context
import android.util.Log
import com.homekids.child.api.ApiClient
import com.homekids.child.service.deviceregistration.DeviceRegistration
import com.google.gson.Gson
import java.io.File

object RuleManager {
    private const val TAG = "RuleManager"
    private const val RULES_FILE = "hk_p1_rules.json"
    @Volatile
    private var blockedDomains: Set<String> = emptySet()
    @Volatile
    private var isInternetPaused = false
    @Volatile
    private var policyReady = false

    private val APP_DOMAIN_ALIASES = mapOf(
        "tiktok.com" to listOf("tiktok.com", "tiktokv.com", "tiktokcdn.com", "muscdn.com", "byteoversea.com", "ibytedtos.com"),
        "youtube.com" to listOf("youtube.com", "googlevideo.com", "ytimg.com", "youtubei.googleapis.com", "youtu.be"),
        "instagram.com" to listOf("instagram.com", "cdninstagram.com"),
        "facebook.com" to listOf("facebook.com", "fbcdn.net", "fb.com", "messenger.com"),
        "snapchat.com" to listOf("snapchat.com", "sc-static.net", "snapads.com"),
        "roblox.com" to listOf("roblox.com", "rbxcdn.com", "rbxmgr.com"),
        "whatsapp.com" to listOf("whatsapp.com", "whatsapp.net", "wa.me"),
        "netflix.com" to listOf("netflix.com", "nflxext.com", "nflxvideo.net"),
        "twitter.com" to listOf("twitter.com", "twimg.com", "t.co", "x.com"),
        "discord.com" to listOf("discord.com", "discordapp.com", "discord.gg", "discord.media"),
        "twitch.tv" to listOf("twitch.tv", "ttvnw.net", "jtvnw.net"),
        "steampowered.com" to listOf("steampowered.com", "steamcommunity.com", "steamstatic.com")
    )

    fun isDomainBlocked(domain: String): Boolean {
        // Never turn a failed policy sync into an unprotected device.
        if (!policyReady) return true

        // Phase 1: Global Pause
        if (isInternetPaused) return true
        
        val d = domain.lowercase().trim()
        val cleanD = d.replace("www.", "")
        
        // 1. Check direct match or subdomain match against active rules & app aliases
        for (b in blockedDomains) {
            val bl = b.lowercase().trim().replace("www.", "")
            if (bl == "*" || cleanD == bl || d == bl || cleanD.endsWith(".$bl") || d.endsWith(".$bl")) return true

            // Expand alias domains
            val aliases = APP_DOMAIN_ALIASES[bl] ?: emptyList()
            for (alias in aliases) {
                val cleanAlias = alias.lowercase().trim()
                if (cleanD == cleanAlias || d == cleanAlias || cleanD.endsWith(".$cleanAlias") || d.endsWith(".$cleanAlias")) return true
            }
        }
        
        // 2. Keyword fallback matching
        val keywords = listOf("tiktok", "muscdn", "tiktokv", "byteoversea", "youtube", "ytimg", "googlevideo", "fbcdn", "facebook", "snapchat", "roblox", "x.com", "twitter", "netflix", "discord", "twitch", "wa.me", "instagram")
        for (b in blockedDomains) {
            val bl = b.lowercase().trim().replace("www.", "")
            for (k in keywords) {
                if (k in bl && k in cleanD) return true
            }
        }
        
        return false
    }

    fun loadRulesFromDisk(context: Context) {
        try {
            val file = File(context.filesDir, RULES_FILE)
            if (file.exists()) {
                val json = file.readText()
                val rules = Gson().fromJson(json, Array<String>::class.java)?.toList().orEmpty()
                applyRules(rules)
                policyReady = rules.isNotEmpty()
                Log.d(TAG, "Loaded Phase 1 rules from disk: ${blockedDomains.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rules: ${e.message}")
        }
    }

    suspend fun syncRules(context: Context): Boolean {
        try {
            val serverIp = DeviceRegistration.getServerIp(context)
            ApiClient.updateBaseUrl(serverIp)
            // The VPN service can be restarted without recreating MainActivity.
            // Restore the persisted device key before making the policy request.
            if (ApiClient.apiKey.isNullOrBlank()) DeviceRegistration.restoreApiKey(context)

            val response = ApiClient.api.getRules("")
            if (response.isSuccessful && response.body() != null) {
                val rulesResponse = response.body()!!
                applyRules(rulesResponse.blockedDomains)
                policyReady = true
                
                saveRulesToDisk(context)
                LocalStatsManager(context).updateLastSync()
                Log.d(TAG, "Phase 1 Sync: ${blockedDomains.size} rules active")
                return true
            }
            Log.e(TAG, "Rules sync returned HTTP ${response.code()}: ${response.errorBody()?.string()}")
        } catch (e: Exception) {
            Log.e(TAG, "Phase 1 Sync Failed", e)
        }
        return false
    }

    private fun applyRules(rules: Collection<String>) {
        blockedDomains = rules
            .asSequence()
            .map { it.trim().lowercase().removePrefix("www.") }
            .filter { it.isNotEmpty() }
            .toSet()
        isInternetPaused = blockedDomains.contains("*")
    }

    private fun saveRulesToDisk(context: Context) {
        try {
            val json = Gson().toJson(blockedDomains.toList())
            File(context.filesDir, RULES_FILE).writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save rules: ${e.message}")
        }
    }
}
