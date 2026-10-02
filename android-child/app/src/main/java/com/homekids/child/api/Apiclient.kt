package com.homekids.child.api

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private var currentUrl = "http://192.168.18.4:8000/"
    
    // Phase 1: Strong Device Identity (API Key)
    var apiKey: String? = null

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
        // Add unique device key to every request header
        apiKey?.let { request.header("X-HomeKids-Key", it) }
        chain.proceed(request.build())
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var retrofit: Retrofit = build(currentUrl)

    private fun build(url: String): Retrofit {
        val finalUrl = if (url.endsWith("/")) url else "$url/"
        return Retrofit.Builder()
            .baseUrl(finalUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    fun updateBaseUrl(ip: String) {
        val resolved = if (ip.startsWith("http")) ip else "http://$ip:8000"
        currentUrl = resolved
        retrofit = build(currentUrl)
    }

    val api: ApiService get() = retrofit.create(ApiService::class.java)
}
