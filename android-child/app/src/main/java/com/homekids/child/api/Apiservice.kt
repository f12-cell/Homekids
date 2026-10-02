package com.homekids.child.api

import com.homekids.child.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @POST("api/register")
    suspend fun registerDevice(
        @Query("device_name") deviceName: String,
        @Query("child_name") childName: String,
        @Query("mac") mac: String = ""
    ): Response<DeviceRegisterResponse>

    @GET("api/rules")
    suspend fun getRules(
        @Query("device_ip") deviceIp: String
    ): Response<RulesResponse>

    @POST("api/activity")
    suspend fun uploadActivity(
        @Body body: List<ActivityRequest>,
        @Query("child_name") childName: String? = null
    ): Response<ActivityReceived>

    @GET("api/activity/{child}")
    suspend fun getActivity(
        @Path("child") child: String,
        @Query("limit") limit: Int = 100
    ): Response<List<ActivityRequest>>

    @POST("api/heartbeat")
    suspend fun heartbeat(): Response<HeartbeatResponse>

    @GET("api/profiles")
    suspend fun getProfiles(): Response<List<ProfileResponse>>

    @POST("api/security/tamper")
    suspend fun reportTamper(
        @Query("event_type") eventType: String,
        @Query("detail") detail: String
    ): Response<Map<String, Any>>
}
