package com.example.blindpeople.di

import android.content.Context
import com.example.blindpeople.data.ApiKeyProvider
import com.example.blindpeople.data.GeminiLiveSession
import com.example.blindpeople.data.SecureApiKeyStore
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

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
    fun provideGeminiLiveSession(
        apiKeyProvider: ApiKeyProvider,
        moshi: Moshi,
    ): GeminiLiveSession = GeminiLiveSession(
        apiKeyProvider = apiKeyProvider,
        moshi = moshi,
    )
}
