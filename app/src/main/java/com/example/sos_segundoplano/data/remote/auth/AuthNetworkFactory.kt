package com.example.sos_segundoplano.data.remote.auth

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.example.sos_segundoplano.data.remote.profile.UsersApi
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
