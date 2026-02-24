package com.example.blindpeople.di

import android.content.Context
import com.example.blindpeople.data.ApiKeyProvider
import com.example.blindpeople.data.GeminiRepository
import com.example.blindpeople.data.SecureApiKeyStore
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideSecureApiKeyStore(
        @ApplicationContext context: Context,
    ): SecureApiKeyStore = SecureApiKeyStore(context)

    @Provides
    @Singleton
    fun provideApiKeyProvider(
        store: SecureApiKeyStore,
    ): ApiKeyProvider = store

    @Provides
    @Singleton
    fun provideGeminiRepository(
        apiKeyProvider: ApiKeyProvider,
        client: OkHttpClient,
        moshi: Moshi,
    ): GeminiRepository = GeminiRepository(
        apiKeyProvider = apiKeyProvider,
        client = client,
        moshi = moshi,
    )
}

