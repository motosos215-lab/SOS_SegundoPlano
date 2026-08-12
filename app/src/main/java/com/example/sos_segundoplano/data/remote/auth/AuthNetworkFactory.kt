package com.example.sos_segundoplano.data.remote.auth

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.example.sos_segundoplano.data.remote.incident.IncidentsApi
import com.example.sos_segundoplano.data.remote.incident.MobileSosAlertsApi
import com.example.sos_segundoplano.data.remote.emergency.EmergencyContactsApi
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertsApi
import com.example.sos_segundoplano.data.remote.profile.UsersApi
import com.example.sos_segundoplano.data.remote.push.PushNotificationTokensApi
import com.example.sos_segundoplano.data.remote.trip.TripsApi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object AuthNetworkFactory {
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    fun createMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    fun createApi(
        baseUrl: String,
        moshi: Moshi = createMoshi(),
        client: OkHttpClient = createClient()
    ): AuthApi {
        return createRetrofit(baseUrl, moshi, client).create(AuthApi::class.java)
    }

    fun createUsersApi(
        baseUrl: String,
        moshi: Moshi = createMoshi(),
        client: OkHttpClient = createClient()
    ): UsersApi = createRetrofit(baseUrl, moshi, client).create(UsersApi::class.java)

    fun createIncidentsApi(
        baseUrl: String,
        moshi: Moshi = createMoshi(),
        client: OkHttpClient = createClient()
    ): IncidentsApi = createRetrofit(baseUrl, moshi, client).create(IncidentsApi::class.java)

    fun createMobileSosAlertsApi(
        baseUrl: String,
        moshi: Moshi = createMoshi(),
        client: OkHttpClient = createClient()
    ): MobileSosAlertsApi = createRetrofit(baseUrl, moshi, client).create(MobileSosAlertsApi::class.java)

    fun createEmergencyContactsApi(
        baseUrl: String,
        moshi: Moshi = createMoshi(),
        client: OkHttpClient = createClient()
    ): EmergencyContactsApi = createRetrofit(baseUrl, moshi, client).create(EmergencyContactsApi::class.java)

    fun createMonitorAlertsApi(
        baseUrl: String,
        moshi: Moshi = createMoshi(),
        client: OkHttpClient = createClient()
    ): MonitorAlertsApi = createRetrofit(baseUrl, moshi, client).create(MonitorAlertsApi::class.java)

    fun createTripsApi(
        baseUrl: String,
        moshi: Moshi = createMoshi(),
        client: OkHttpClient = createClient()
    ): TripsApi = createRetrofit(baseUrl, moshi, client).create(TripsApi::class.java)

    fun createPushNotificationTokensApi(
        baseUrl: String,
        moshi: Moshi = createMoshi(),
        client: OkHttpClient = createClient()
    ): PushNotificationTokensApi =
        createRetrofit(baseUrl, moshi, client).create(PushNotificationTokensApi::class.java)

    private fun createRetrofit(
        baseUrl: String,
        moshi: Moshi,
        client: OkHttpClient
    ): Retrofit {
        require(baseUrl.endsWith('/')) { "API base URL must end with /" }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    private fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}
