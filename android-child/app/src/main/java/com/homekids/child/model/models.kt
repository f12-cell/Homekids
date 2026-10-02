package com.homekids.child.model

import com.google.gson.annotations.SerializedName

data class DeviceRegisterResponse(
    @SerializedName("registered") val registered: Boolean,
    @SerializedName("ip") val ip: String,
    @SerializedName("name") val name: String,
    @SerializedName("uuid") val uuid: String? = null,
    @SerializedName("api_key") val apiKey: String? = null
)

data class RulesResponse(
    @SerializedName("blocked_domains") val blockedDomains: List<String>,
    @SerializedName("reason") val reason: String? = null
)

data class ActivityRequest(
    @SerializedName("domain") val domain: String,
    @SerializedName("app") val app: String,
    @SerializedName("timestamp", alternate = ["time", "ts"]) val timestamp: String,
    @SerializedName("status") val status: String? = "allowed",
    @SerializedName("child", alternate = ["child_name"]) val child: String? = null
)

data class ActivityReceived(
    @SerializedName("saved") val saved: Int? = null,
    @SerializedName("error") val error: String? = null
)

data class HeartbeatResponse(
    @SerializedName("ok") val ok: Boolean
)

data class ProfileResponse(
    @SerializedName("name") val name: String,
    @SerializedName("avatar") val avatar: String,
    @SerializedName("daily_limit") val dailyLimit: Int,
    @SerializedName("internet_paused") val internetPaused: Boolean
)
