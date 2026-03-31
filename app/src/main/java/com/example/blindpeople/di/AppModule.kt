package com.example.blindpeople.di

import android.content.Context
import com.example.blindpeople.data.ApiKeyProvider
import com.example.blindpeople.data.LabelTranslator
import com.example.blindpeople.data.SecureApiKeyStore
import com.example.blindpeople.detector.ObjectDetectorHelper
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
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
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
    fun provideObjectDetectorHelper(
        @ApplicationContext context: Context,
    ): ObjectDetectorHelper = ObjectDetectorHelper(context)

    @Provides
    @Singleton
    fun provideLabelTranslator(
        apiKeyProvider: ApiKeyProvider,
        client: OkHttpClient,
    ): LabelTranslator = LabelTranslator(
        apiKeyProvider = apiKeyProvider,
        client = client,
    )
}
