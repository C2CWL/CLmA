package com.clma.app.di

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.serialization.json.Json
import com.clma.ai.provider.ProviderManager
import com.clma.common.http.AcceptLanguageBuilder
import com.clma.app.BuildConfig
import com.clma.app.AppScope
import com.clma.app.data.ai.AIRequestInterceptor
import com.clma.app.data.ai.RequestLoggingInterceptor
import com.clma.app.data.ai.transformers.AssistantTemplateLoader
import com.clma.app.data.ai.GenerationLoop
import com.clma.app.data.ai.TranslationHandler
import com.clma.app.data.ai.transformers.TemplateTransformer
import com.clma.app.data.api.HuggingFaceAPI
import com.clma.app.data.api.RikkaHubAPI
import com.clma.app.data.codex.CodexAccountRepository
import com.clma.app.data.codex.CodexCredentialStore
import com.clma.app.data.codex.CodexOAuthManager
import com.clma.app.data.codex.CodexProvider
import com.clma.app.data.grok.GrokAccountRepository
import com.clma.app.data.grok.GrokCredentialStore
import com.clma.app.data.grok.GrokOAuthManager
import com.clma.app.data.gemini.GeminiAccountRepository
import com.clma.app.data.gemini.GeminiCredentialStore
import com.clma.app.data.gemini.GeminiOAuthManager
import com.clma.app.data.gemini.GeminiProvider
import com.clma.app.data.grok.GrokProvider
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.sync.BackupManager
import com.clma.app.data.db.AppDatabaseFactory
import com.clma.app.data.db.AppDatabase
import com.clma.app.data.db.fts.MessageFtsManager
import com.clma.app.data.ai.mcp.McpManager
import com.clma.app.data.agentrun.AgentRunBootRecovery
import com.clma.app.data.agentrun.AgentRunRepository
import com.clma.app.data.ai.OrphanedReasoningRecovery
import com.clma.app.data.network.SettingsProxySelector
import com.clma.app.data.network.SettingsProxyAuthenticator
import com.clma.app.data.network.SettingsSocks5Authenticator
import com.clma.app.data.sync.webdav.WebDavSync
import com.clma.search.SearchService
import com.clma.app.data.sync.S3Sync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import org.koin.core.qualifier.named
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.clma.app.data.agentdef.AgentDefinitionDatabase
import com.clma.app.data.agentdef.AgentDefinitionDatabaseFactory
import com.clma.app.data.agentdef.AgentDefinitionRepository
import com.clma.app.data.usage.UsageLedger
import com.clma.app.data.usage.UsageLedgerDatabase
import com.clma.app.data.usage.UsageLedgerDatabaseFactory
import com.clma.app.data.usage.decorateForUsage

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        AppDatabaseFactory.create(context)
    }

    // P2-11c - the usage ledger lives in its own database file (see UsageLedgerDatabase for
    // why), so a telemetry retention sweep can never touch the chat database.
    single { UsageLedgerDatabaseFactory.create(context = get()) }
    single { get<UsageLedgerDatabase>().usageRecordDao() }
    single { UsageLedger(get()) }

    // P2-06 - the expert library also lives in its own database file (see
    // AgentDefinitionDatabase for why). Unlike the usage ledger this holds USER data, so a
    // future shape change ships a real migration rather than dropping and recreating the
    // file. Nothing in the expert path joins against the chat database.
    single { AgentDefinitionDatabaseFactory.create(context = get()) }
    single { get<AgentDefinitionDatabase>().agentDefinitionDao() }
    single { AgentDefinitionRepository(dao = get(), appScope = get()) }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().conversationCompactionDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        MessageFtsManager(get())
    }

    // Phase 24 — unified AgentRun ledger. DAO + the single shared writer/reader + the
    // boot-recovery sweep. AgentRunRepository has no cross-dependencies (only the DAO), so
    // there is no DI-cycle risk here.
    single { get<AppDatabase>().agentRunDao() }
    single { AgentRunRepository(get()) }
    single { AgentRunBootRecovery(context = get(), repository = get()) }
    single {
        OrphanedReasoningRecovery(
            conversationDAO = get<AppDatabase>().conversationDao(),
            messageNodeDAO = get<AppDatabase>().messageNodeDao(),
        )
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get()) }

    single {
        GenerationLoop(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepo = get(),
            conversationRepo = get(),
            aiLoggingManager = get(),
            systemPromptBuilder = get(),
        )
    }

    single { com.clma.app.data.ai.SystemPromptBuilder() }

    single {
        TranslationHandler(providerManager = get())
    }

    single<OkHttpClient> {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
        val initialNetworkSetting = settingsStore.settingsFlow.value.networkSetting
        val appliedProxySetting = AtomicReference(
            Triple(
                initialNetworkSetting.proxyUrl,
                initialNetworkSetting.proxyUsername,
                initialNetworkSetting.proxyPassword,
            )
        )
        lateinit var client: OkHttpClient
        client = OkHttpClient.Builder()
            .proxySelector(SettingsProxySelector(settingsStore))
            .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            // HTTP/2 keepalive. Without it a half-open socket (app backgrounded across a network
            // change, OEM freeze, or a peer that vanishes without a FIN) is invisible: readTimeout
            // only bounds the gap BETWEEN bytes, so a stream that goes quiet can hang for the full
            // 10 minutes with the UI stuck on "thinking". A PING every 30s makes OkHttp tear the
            // dead connection down promptly, which surfaces as an ordinary stream failure the
            // retry policy can recover (see StreamFirstOutputWatchdog for the HTTP/1.1 / silent
            // -server case). HTTP/1.1 connections simply ignore pingInterval.
            .pingInterval(30, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            // This one client carries EVERY conversation, cron job, workflow and sub-agent. OkHttp
            // caps concurrent requests PER HOST at 5 by default, and a streamed reply holds its
            // slot for the whole turn (tens of seconds to minutes). With several chats - or one
            // chat that fanned out to several sub-agents - streaming to the same provider host at
            // once, request #6 onwards sat in OkHttp's queue having sent ZERO bytes: the UI showed
            // "thinking..." indefinitely, nothing appeared in logcat, and it read exactly like a
            // hang (see the multi-agent concurrency reports). Raise both ceilings so the bound is
            // the provider's own limit - which answers 429 promptly and visibly - instead of our
            // transport queue.
            .dispatcher(HttpConcurrency.dispatcher())
            .addInterceptor { chain ->
                val networkSetting = settingsStore.settingsFlow.value.networkSetting
                val currentProxySetting = Triple(
                    networkSetting.proxyUrl,
                    networkSetting.proxyUsername,
                    networkSetting.proxyPassword,
                )
                if (appliedProxySetting.getAndSet(currentProxySetting) != currentProxySetting) {
                    client.connectionPool.evictAll()
                }

                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent
                        .trim()
                        .ifEmpty { "CLmA-Android/${BuildConfig.VERSION_NAME}" }
                    requestBuilder.addHeader(HttpHeaders.UserAgent, userAgent)
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .apply {
                // HEADERS-level logging prints Authorization: Bearer <api-key> to logcat.
                // Debug-only so release builds never leak provider keys to logcat.
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                    })
                }
            }
            .build().also { SearchService.init(it, get()) }
        client
    }

    single<OkHttpClient>(named("codex")) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    single<OkHttpClient>(named("grok")) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    single<OkHttpClient>(named("gemini")) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    single {
        GrokAccountRepository(
            store = GrokCredentialStore(context = get(), json = get()),
            client = get(named("grok")),
            json = get(),
        )
    }

    single {
        GrokOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("grok")),
            repository = get(),
            json = get(),
        )
    }

    single {
        GeminiAccountRepository(
            store = GeminiCredentialStore(context = get(), json = get()),
            client = get(named("gemini")),
            json = get(),
        )
    }

    single {
        GeminiOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("gemini")),
            repository = get(),
        )
    }

    single {
        HuggingFaceAPI.create(get())
    }

    single {
        CodexAccountRepository(
            store = CodexCredentialStore(context = get(), json = get()),
            client = get(named("codex")),
            json = get(),
        )
    }

    single {
        CodexOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("codex")),
            repository = get(),
        )
    }

    single {
        val settingsStore: com.clma.app.data.datastore.SettingsStore = get()
        val codexRepository: CodexAccountRepository = get()
        val json: Json = get()
        val usageLedger: UsageLedger = get()
        ProviderManager(client = get(), context = get()).also { pm ->
            // P2-11c2 - every provider handed out gets wrapped, so each model round trip
            // writes one ledger row. Installed on the raw accessor so both getProvider and
            // getProviderByType are covered exactly once.
            pm.providerDecorator = { provider -> decorateForUsage(provider, usageLedger) }
            pm.registerProvider(
                "local_litert",
                com.clma.locallm.litert.LiteRtProvider(
                    context = get(),
                    runtime = get(),
                    prefs = get(),
                    settingsUpdater = { transform ->
                        settingsStore.update { old -> old.copy(providers = transform(old.providers)) }
                    },
                ),
            )
            pm.registerProvider(
                "local_llamacpp",
                com.clma.llamacpp.LlamaCppProvider(
                    context = get(),
                    runtime = get(),
                    prefs = get(),
                ),
            )
            pm.registerProvider(
                "codex",
                CodexProvider(
                    client = get(named("codex")),
                    repository = codexRepository,
                    json = json,
                    scope = get(),
                )
            )
            pm.registerProvider(
                "grok",
                GrokProvider(
                    client = get(named("grok")),
                    repository = get<GrokAccountRepository>(),
                    json = json,
                    scope = get(),
                )
            )
            pm.registerProvider(
                "gemini_oauth",
                GeminiProvider(
                    client = get(named("gemini")),
                    repository = get<GeminiAccountRepository>(),
                    json = json,
                )
            )
        }
    }

    single { BackupManager(context = get(), database = get(), settingsStore = get(), json = get()) }

    single {
        WebDavSync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<RikkaHubAPI> {
        get<Retrofit>().create(RikkaHubAPI::class.java)
    }
}
