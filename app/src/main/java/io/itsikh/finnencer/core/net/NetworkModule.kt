package io.itsikh.finnencer.core.net

import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.itsikh.finnencer.data.api.AnthropicService
import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.api.GeminiService
import io.itsikh.finnencer.data.api.RssService
import io.itsikh.finnencer.data.api.SecEdgarService
import io.itsikh.finnencer.data.repo.ApiKey
import io.itsikh.finnencer.data.repo.ApiKeysRepository
import io.itsikh.finnencer.util.AppSigningInfo
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier annotation class FinnhubRetrofit
@Qualifier annotation class AnthropicRetrofit
@Qualifier annotation class GeminiRetrofit
@Qualifier annotation class EdgarRetrofit
@Qualifier annotation class RssRetrofit

/**
 * Reads the matching key from [ApiKeysRepository] on every outbound request
 * and attaches it as the provider-specific auth header. If the key is not yet
 * configured the request is allowed through unauthenticated; the provider
 * will return a 401/403 and the calling code surfaces a "configure key"
 * error.
 */
class AuthHeaderInterceptor(
    private val repo: ApiKeysRepository,
    private val key: ApiKey,
    private val headerBuilder: (token: String) -> Pair<String, String>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = repo.get(key)
        val req = if (!token.isNullOrBlank()) {
            val (h, v) = headerBuilder(token)
            chain.request().newBuilder().header(h, v).build()
        } else chain.request()
        return chain.proceed(req)
    }
}

/**
 * Adds the two headers Google checks when a Cloud API key has Android-app
 * restrictions configured in GCP Console:
 *  - `X-Android-Package` = applicationId
 *  - `X-Android-Cert`    = SHA-1 of the APK signing cert, uppercase hex,
 *                           no separators
 *
 * Without these headers, a restricted key returns HTTP 403 even with valid
 * auth.
 */
class AndroidAttributionInterceptor(
    private val info: AppSigningInfo,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val b = chain.request().newBuilder()
            .header("X-Android-Package", info.packageName)
        info.signingCertSha1Hex?.let { b.header("X-Android-Cert", it) }
        return chain.proceed(b.build())
    }
}

/**
 * SEC EDGAR requires a custom User-Agent identifying who you are (any string
 * containing an email is enough). The user enters this once in the keys
 * screen; we attach it on every EDGAR request.
 */
class EdgarUserAgentInterceptor(
    private val repo: ApiKeysRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val ua = repo.get(ApiKey.EDGAR_UA) ?: "finnencer (no-contact-set)"
        val req = chain.request().newBuilder()
            .header("User-Agent", ua)
            .header("Accept-Encoding", "gzip, deflate")
            .build()
        return chain.proceed(req)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private fun logging(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }

    @Provides @Singleton
    fun provideBaseOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(logging())
            .build()

    // ───────── Finnhub ─────────

    @Provides @Singleton @FinnhubRetrofit
    fun provideFinnhubRetrofit(gson: Gson, repo: ApiKeysRepository): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                AuthHeaderInterceptor(repo, ApiKey.FINNHUB) { token ->
                    "X-Finnhub-Token" to token
                }
            )
            .addInterceptor(logging())
            .build()
        return Retrofit.Builder()
            .baseUrl("https://finnhub.io/api/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides @Singleton
    fun provideFinnhubService(@FinnhubRetrofit retrofit: Retrofit): FinnhubService =
        retrofit.create(FinnhubService::class.java)

    // ───────── Anthropic ─────────

    @Provides @Singleton @AnthropicRetrofit
    fun provideAnthropicRetrofit(gson: Gson, repo: ApiKeysRepository): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                AuthHeaderInterceptor(repo, ApiKey.ANTHROPIC) { token ->
                    "x-api-key" to token
                }
            )
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("anthropic-version", "2023-06-01")
                        .header("content-type", "application/json")
                        .build()
                )
            }
            .addInterceptor(logging())
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides @Singleton
    fun provideAnthropicService(@AnthropicRetrofit retrofit: Retrofit): AnthropicService =
        retrofit.create(AnthropicService::class.java)

    // ───────── Gemini ─────────

    @Provides @Singleton @GeminiRetrofit
    fun provideGeminiRetrofit(
        gson: Gson,
        repo: ApiKeysRepository,
        signingInfo: AppSigningInfo,
    ): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                AuthHeaderInterceptor(repo, ApiKey.GEMINI) { token ->
                    "x-goog-api-key" to token
                }
            )
            .addInterceptor(AndroidAttributionInterceptor(signingInfo))
            .addInterceptor(logging())
            .build()
        return Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides @Singleton
    fun provideGeminiService(@GeminiRetrofit retrofit: Retrofit): GeminiService =
        retrofit.create(GeminiService::class.java)

    // ───────── SEC EDGAR ─────────

    @Provides @Singleton @EdgarRetrofit
    fun provideEdgarRetrofit(gson: Gson, repo: ApiKeysRepository): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor(EdgarUserAgentInterceptor(repo))
            .addInterceptor(logging())
            .build()
        return Retrofit.Builder()
            .baseUrl("https://data.sec.gov/")
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides @Singleton
    fun provideEdgarService(@EdgarRetrofit retrofit: Retrofit): SecEdgarService =
        retrofit.create(SecEdgarService::class.java)

    // ───────── RSS ─────────

    @Provides @Singleton @RssRetrofit
    fun provideRssRetrofit(): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "finnencer-rss/1.0")
                        .build()
                )
            }
            .addInterceptor(logging())
            .build()
        return Retrofit.Builder()
            .baseUrl("https://example.invalid/") // each call uses @Url
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
    }

    @Provides @Singleton
    fun provideRssService(@RssRetrofit retrofit: Retrofit): RssService =
        retrofit.create(RssService::class.java)
}
