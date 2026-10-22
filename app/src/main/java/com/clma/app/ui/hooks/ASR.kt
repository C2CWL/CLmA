package com.clma.app.ui.hooks

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.clma.asr.ASRController
import com.clma.asr.ASRProviderSetting
import com.clma.asr.ASRState
import com.clma.asr.providers.DashScopeASRController
import com.clma.asr.providers.DashScopeStreamingASRController
import com.clma.asr.providers.MiMoASRController
import com.clma.asr.providers.OpenAIRealtimeASRController
import com.clma.asr.providers.StepASRController
import com.clma.asr.providers.VolcengineASRController
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.datastore.getSelectedASRProvider
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

@Composable
fun rememberCustomAsrState(): CustomAsrState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val httpClient = koinInject<OkHttpClient>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    val asrState = remember {
        CustomAsrStateImpl(context.applicationContext, httpClient)
    }

    DisposableEffect(settings.selectedASRProviderId, settings.asrProviders) {
        asrState.updateProvider(settings.getSelectedASRProvider())
        onDispose { }
    }

    DisposableEffect(asrState) {
        onDispose {
            asrState.cleanup()
        }
    }

    return asrState
}

interface CustomAsrState {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit)
    fun stop()
    fun cleanup()
}

private class CustomAsrStateImpl(
    private val context: Context,
    private val httpClient: OkHttpClient
) : CustomAsrState {
    private var controller: ASRController? = null
    private val idleState = MutableStateFlow(ASRState())

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAcceptsDelayedFocusGain(false)
        .build()

    override val state: StateFlow<ASRState>
        get() = controller?.state ?: idleState

    fun updateProvider(provider: ASRProviderSetting?) {
        controller?.dispose()
        controller = provider?.let { createController(it) }
        if (controller == null) {
            idleState.value = ASRState()
        }
    }

    override fun start(onTranscriptChange: (String) -> Unit) {
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            controller?.start(onTranscriptChange)
        }
    }

    override fun stop() {
        controller?.stop()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun cleanup() {
        controller?.dispose()
        controller = null
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun createController(provider: ASRProviderSetting): ASRController? {
        return when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> {
                if (provider.apiKey.isBlank()) return null
                OpenAIRealtimeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.DashScope -> {
                if (provider.apiKey.isBlank()) return null
                DashScopeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Volcengine -> {
                if (provider.apiKey.isBlank()) return null
                VolcengineASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.MiMo -> {
                if (provider.apiKey.isBlank()) return null
                MiMoASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Step -> {
                if (provider.apiKey.isBlank()) return null
                StepASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.DashScopeStreaming -> {
                if (provider.apiKey.isBlank()) return null
                DashScopeStreamingASRController(context, httpClient, provider)
            }
        }
    }
}
