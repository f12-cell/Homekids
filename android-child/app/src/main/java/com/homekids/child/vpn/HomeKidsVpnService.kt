package com.homekids.child.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.homekids.child.MainActivity
import com.homekids.child.api.ApiClient
import com.homekids.child.service.deviceregistration.ActivityUploader
import com.homekids.child.service.RuleManager
import com.homekids.child.service.LocalStatsManager
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class HomeKidsVpnService : VpnService() {

    private val tag = "HomeKidsVPN"
    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false
    private lateinit var statsManager: LocalStatsManager

    companion object {
        const val ACTION_START = "START_VPN"
        const val ACTION_STOP  = "STOP_VPN"
        const val CHANNEL_ID   = "vpn_channel"
        var isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START && !running) {
            startVpn()
            // Phase 1: Report service startup (detects if it was killed)
            scope.launch {
                reportTamper("ServiceStart", "VPN Service started or restarted")
            }
        }
        return when (intent?.action) {
            ACTION_STOP -> { stopVpn(); START_NOT_STICKY }
            else        -> { START_STICKY }
        }
    }

    private fun startVpn() {
        if (running) return
        
        statsManager = LocalStatsManager(this)
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HomeKids Protection Active")
            .setContentText("Your device is being protected.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        try {
            RuleManager.loadRulesFromDisk(this)

            val builder = Builder()
                .setSession("HomeKids")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("10.0.0.2")
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                // Route ONLY DNS traffic to designated DNS servers so all other traffic goes directly over Wi-Fi/Cellular
                .addRoute("10.0.0.2", 32)
                .addRoute("8.8.8.8", 32)
                .addRoute("8.8.4.4", 32)
                .addRoute("1.1.1.1", 32)
                .addRoute("1.0.0.1", 32)
                .addRoute("9.9.9.9", 32)
                .addRoute("208.67.222.222", 32)
                .addRoute("208.67.220.220", 32)
                .setMtu(1500)
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(tag, "Failed to establish VPN interface")
                stopVpn()
                return
            }

            running      = true
            isRunning    = true
            Log.d(tag, "VPN started - Enhanced Mode")

            // Sync rules every 10 seconds (Phase 1 Optimization)
            scope.launch {
                // Apply the current server policy immediately, then pick up
                // later block/unblock changes on the normal refresh interval.
                RuleManager.syncRules(this@HomeKidsVpnService)
                while (running) {
                    RuleManager.syncRules(this@HomeKidsVpnService)
                    ActivityUploader.flushQueue(this@HomeKidsVpnService)
                    
                    // Phase 1: Tamper Detection - Check for Private DNS
                    checkPrivateDNS()
                    
                    delay(10000)
                }
            }

            scope.launch {
                while (running) {
                    sendHeartbeat()
                    delay(30_000)
                }
            }

            scope.launch {
                processPackets()
            }

        } catch (e: Exception) {
            Log.e(tag, "VPN start failed: ${e.message}")
            stopVpn()
        }
    }

    private fun stopVpn() {
        running   = false
        isRunning = false
        scope.coroutineContext.cancelChildren()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing interface: ${e.message}")
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(tag, "VPN stopped")
    }

    private fun checkPrivateDNS() {
        try {
            // Android 9+ Private DNS detection
            val mode = Settings.Global.getString(contentResolver, "private_dns_mode")
            if (mode != null && mode != "off") {
                scope.launch {
                    reportTamper("BypassAttempt", "Private DNS is enabled (Mode: $mode)")
                }
            }
        } catch (e: Exception) {}
    }

    private suspend fun reportTamper(type: String, detail: String) {
        try {
            ApiClient.api.reportTamper(type, detail)
        } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Protection",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private suspend fun processPackets() {
        val vpn = vpnInterface ?: return
        val input = FileInputStream(vpn.fileDescriptor)
        val output = FileOutputStream(vpn.fileDescriptor)
        
        while (running) {
            try {
                val buffer = ByteArray(32767)
                val length = withContext(Dispatchers.IO) { 
                    try { input.read(buffer) } catch(e: Exception) { -1 }
                }
                if (length <= 0) { delay(10); continue }

                scope.launch {
                    handlePacket(buffer.copyOf(length), output)
                }
            } catch (e: Exception) {
                if (running) Log.e(tag, "Packet capture error: ${e.message}")
                delay(10)
            }
        }
    }

    private suspend fun handlePacket(data: ByteArray, output: FileOutputStream) {
        try {
            if (data.size < 28) return
            val version = (data[0].toInt() and 0xF0) shr 4
            if (version != 4) return

            val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
            if (data.size < ipHeaderLen + 8) return

            val protocol = data[9].toInt() and 0xFF
            val destPort = ((data[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                           (data[ipHeaderLen + 3].toInt() and 0xFF)

            // Intercept Standard DNS Queries (UDP Port 53)
            if (protocol == 17 && destPort == 53) {
                val domain = extractDomainFromData(data)
                if (!domain.isNullOrBlank()) {
                    val isBlocked = RuleManager.isDomainBlocked(domain)
                    val appName = identifyApp(domain)

                    // Log activity
                    statsManager.logActivity(domain, appName, if (isBlocked) "blocked" else "allowed")

                    if (isBlocked) {
                        Log.d(tag, "BLOCKED DNS: $domain")
                        statsManager.incrementBlockedCount()
                        val blockResponse = constructDnsBlockResponse(data)
                        withContext(Dispatchers.IO) {
                            try { output.write(blockResponse) } catch (_: Exception) {}
                        }
                        return
                    }

                    relayDns(data, output)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Handle packet error: ${e.message}")
        }
    }

    private fun constructDnsBlockResponse(originalPacket: ByteArray): ByteArray {
        val ipHeaderLen = (originalPacket[0].toInt() and 0x0F) * 4
        val dnsStart = ipHeaderLen + 8
        val queryDnsLen = originalPacket.size - dnsStart
        if (queryDnsLen < 12) return originalPacket

        // Construct 0.0.0.0 DNS Answer Response
        val responseDns = ByteArray(queryDnsLen + 16)
        
        // Copy Header & Question section
        System.arraycopy(originalPacket, dnsStart, responseDns, 0, queryDnsLen)
        
        // Flags: 0x8180 (Response, No Error)
        responseDns[2] = 0x81.toByte()
        responseDns[3] = 0x80.toByte()
        
        // Answer Count = 1 (0x00, 0x01)
        responseDns[6] = 0x00.toByte()
        responseDns[7] = 0x01.toByte()

        // Append 16-byte Answer section at end of Question section
        val ansStart = queryDnsLen
        responseDns[ansStart]     = 0xC0.toByte() // Pointer to name at offset 12
        responseDns[ansStart + 1] = 0x0C.toByte()
        responseDns[ansStart + 2] = 0x00.toByte() // Type A
        responseDns[ansStart + 3] = 0x01.toByte()
        responseDns[ansStart + 4] = 0x00.toByte() // Class IN
        responseDns[ansStart + 5] = 0x01.toByte()
        responseDns[ansStart + 6] = 0x00.toByte() // TTL 0 keeps unblock changes immediate
        responseDns[ansStart + 7] = 0x00.toByte()
        responseDns[ansStart + 8] = 0x00.toByte()
        responseDns[ansStart + 9] = 0x00.toByte()
        responseDns[ansStart + 10] = 0x00.toByte() // Data length = 4
        responseDns[ansStart + 11] = 0x04.toByte()
        responseDns[ansStart + 12] = 0x00.toByte() // 0.0.0.0
        responseDns[ansStart + 13] = 0x00.toByte()
        responseDns[ansStart + 14] = 0x00.toByte()
        responseDns[ansStart + 15] = 0x00.toByte()

        return constructIpUdpPacket(originalPacket, responseDns)
    }

    private suspend fun relayDns(originalData: ByteArray, output: FileOutputStream) {
        var dnsSocket: DatagramSocket? = null
        try {
            dnsSocket = DatagramSocket()
            protect(dnsSocket)
            
            val ipHeaderLen = (originalData[0].toInt() and 0x0F) * 4
            val udpTotalLen = ((originalData[ipHeaderLen + 4].toInt() and 0xFF) shl 8) or
                              (originalData[ipHeaderLen + 5].toInt() and 0xFF)
            val dnsPayloadLen = (udpTotalLen - 8).coerceIn(12, originalData.size - ipHeaderLen - 8)
            val dnsPayload = originalData.copyOfRange(ipHeaderLen + 8, ipHeaderLen + 8 + dnsPayloadLen)
            val outPacket = DatagramPacket(dnsPayload, dnsPayload.size, InetAddress.getByName("8.8.8.8"), 53)
            
            withContext(Dispatchers.IO) {
                dnsSocket.send(outPacket)
                val responseData = ByteArray(4096)
                val inPacket = DatagramPacket(responseData, responseData.size)
                dnsSocket.soTimeout = 4000
                dnsSocket.receive(inPacket)

                val responseLength = inPacket.length
                val finalPacket = constructIpUdpPacket(originalData, responseData.copyOfRange(0, responseLength))
                output.write(finalPacket)
            }
        } catch (e: Exception) {
            Log.e(tag, "Relay DNS error: ${e.message}")
        } finally {
            dnsSocket?.close()
        }
    }

    private fun constructIpUdpPacket(originalPacket: ByteArray, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLen = (originalPacket[0].toInt() and 0x0F) * 4
        val totalLen = ipHeaderLen + 8 + dnsResponse.size
        val result = ByteArray(totalLen)

        System.arraycopy(originalPacket, 0, result, 0, ipHeaderLen)
        System.arraycopy(originalPacket, 12, result, 16, 4) 
        System.arraycopy(originalPacket, 16, result, 12, 4) 
        
        result[2] = (totalLen shr 8).toByte()
        result[3] = (totalLen and 0xFF).toByte()
        
        val srcPort = originalPacket.copyOfRange(ipHeaderLen, ipHeaderLen + 2)
        val dstPort = originalPacket.copyOfRange(ipHeaderLen + 2, ipHeaderLen + 4)
        System.arraycopy(dstPort, 0, result, ipHeaderLen, 2)
        System.arraycopy(srcPort, 0, result, ipHeaderLen + 2, 2)
        
        result[ipHeaderLen + 4] = ((dnsResponse.size + 8) shr 8).toByte()
        result[ipHeaderLen + 5] = ((dnsResponse.size + 8) and 0xFF).toByte()
        
        System.arraycopy(dnsResponse, 0, result, ipHeaderLen + 8, dnsResponse.size)
        
        result[10] = 0; result[11] = 0
        val checksum = calculateChecksum(result, ipHeaderLen)
        result[10] = (checksum shr 8).toByte()
        result[11] = (checksum and 0xFF).toByte()
        
        result[ipHeaderLen + 6] = 0; result[ipHeaderLen + 7] = 0
        return result
    }

    private fun calculateChecksum(data: ByteArray, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i < (length - 1)) {
            sum += (data[i].toInt() and 0xFF shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < length) sum += (data[i].toInt() and 0xFF shl 8)
        while (sum shr 16 > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.toInt().inv()) and 0xFFFF
    }

    private fun extractDomainFromData(data: ByteArray): String? {
        try {
            val len = data.size
            if (len < 28) return null
            val isIpv6 = (data[0].toInt() and 0xF0) == 0x60
            val ipHeaderLen = if (isIpv6) 40 else (data[0].toInt() and 0x0F) * 4
            val dnsStart = ipHeaderLen + 8
            if (len < dnsStart + 12) return null
            val flags = ((data[dnsStart + 2].toInt() and 0xFF) shl 8) or (data[dnsStart + 3].toInt() and 0xFF)
            if (flags and 0x8000 != 0) return null // Skip responses
            var pos = dnsStart + 12
            val sb = StringBuilder()
            while (pos < len) {
                val labelLen = data[pos].toInt() and 0xFF
                if (labelLen == 0) break
                if (labelLen >= 192) {
                    pos += 2
                    break
                }
                if (pos + 1 + labelLen > len) break
                if (sb.isNotEmpty()) sb.append('.')
                val label = String(data, pos + 1, labelLen, Charsets.US_ASCII)
                sb.append(label)
                pos += labelLen + 1
            }
            return if (sb.isNotEmpty()) sb.toString().lowercase() else null
        } catch (e: Exception) { return null }
    }

    private fun identifyApp(domain: String): String {
        val d = domain.lowercase()
        return when {
            "youtube" in d || "ytimg" in d || "googlevideo" in d -> "YouTube"
            "tiktok" in d || "muscdn" in d || "tiktokv" in d || "byteoversea" in d -> "TikTok"
            "instagram" in d || "cdninstagram" in d -> "Instagram"
            "facebook" in d || "fbcdn" in d || "messenger" in d || "fb.com" in d -> "Facebook"
            "snapchat" in d || "sc-static" in d || "snapads" in d -> "Snapchat"
            "roblox" in d || "rbxcdn" in d || "rbxmgr" in d -> "Roblox"
            "twitter" in d || "twimg" in d || "t.co" in d || "x.com" in d -> "Twitter"
            "netflix" in d || "nflxext" in d || "nflxvideo" in d -> "Netflix"
            "discord" in d || "discordapp" in d || "discord.gg" in d -> "Discord"
            "twitch" in d || "ttvnw" in d || "jtvnw" in d -> "Twitch"
            "whatsapp" in d || "wa.me" in d -> "WhatsApp"
            "steam" in d || "steampowered" in d -> "Steam"
            "google" in d -> "Google"
            "amazon" in d -> "Amazon"
            else -> "Other"
        }
    }

    private suspend fun sendHeartbeat() {
        withContext(Dispatchers.IO) {
            try {
                val response = ApiClient.api.heartbeat()
                if (response.isSuccessful) {
                    Log.d(tag, "Heartbeat OK")
                }
            } catch (e: Exception) {
                Log.e(tag, "Heartbeat failed")
            }
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
