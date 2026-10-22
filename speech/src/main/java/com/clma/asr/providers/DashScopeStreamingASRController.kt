package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "DashScopeStreamingASR"
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L
private const val FINISH_TIMEOUT_MS = 10_000L

/**
 * 阿里云百炼「实时语音识别」WebSocket 协议
 * (Qwen-Audio-3.x-ASR-Flash-Streaming / Fun-ASR-Realtime)。
 *
 * 与 [DashScopeASRController] 的 OpenAI-Realtime 兼容协议不同：
 * - 端点固定为 `.../api-ws/v1/inference`
 * - 指令为 JSON 文本帧：run-task / continue-task / finish-task
 * - 音频为「裸 PCM 二进制帧」（无自定义头）
 * - 服务端事件：task-started / result-generated / task-finished / task-failed
 *
 * 文档：https://help.aliyun.com/zh/model-studio/fun-asr-realtime-websocket-api
 */
class DashScopeStreamingASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.DashScopeStreaming
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var recorderJob: Job? = null
    private var finishTimeoutJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private val taskId = Uuid.random().toString()
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())
    private val partialTranscripts = ConcurrentHashMap<Int, String>()

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (provider.apiKey.isBlank()) {
            setError("DashScope API Key is required")
            return
        }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        completedTranscripts.clear()
        partialTranscripts.clear()
        _state.update { ASRState(status = ASRStatus.Connecting, isAvailable = true) }

        val request = Request.Builder()
            .url(provider.websocketUrl.trim().trimEnd('/'))
            .addHeader("Authorization", "Bearer ${provider.apiKey.trim()}")
            .addHeader("X-DashScope-DataInspection", "disable")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (this@DashScopeStreamingASRController.webSocket !== webSocket ||
                    state.value.status != ASRStatus.Connecting
                ) {
                    webSocket.close(1000, "cancelled")
                    return
                }
                if (!webSocket.send(runTaskEvent().toString())) {
                    setError("Failed to initialize ASR session")
                    return
                }
                // Wait for `task-started` before streaming audio (protocol requirement).
                _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@DashScopeStreamingASRController.webSocket === webSocket) {
                    handleServerEvent(webSocket, text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // The Qwen/Fun ASR protocol answers with JSON text frames only.
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val handshake = response?.let { " (HTTP ${it.code} ${it.message})" }.orEmpty()
                Log.e(TAG, "DashScope streaming ASR websocket failed$handshake", t)
                if (this@DashScopeStreamingASRController.webSocket !== webSocket) return
                finishTimeoutJob?.cancel()
                finishTimeoutJob = null
                this@DashScopeStreamingASRController.webSocket = null
                releaseRecorder()
                setError(t.message ?: "ASR websocket failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "DashScope streaming ASR websocket closed: code=$code, reason=$reason")
                if (this@DashScopeStreamingASRController.webSocket !== webSocket) return
                finishTimeoutJob?.cancel()
                finishTimeoutJob = null
                this@DashScopeStreamingASRController.webSocket = null
                releaseRecorder()
                _state.update { it.copy(status = ASRStatus.Idle, errorMessage = null) }
            }
        })
    }

    override fun pauseCapture() {
        recorderJob?.cancel()
        runCatching { audioRecord?.stop() }
    }

    override fun stop() {
        val wasListening = state.value.status == ASRStatus.Listening
        val activeRecorderJob = recorderJob
        val socket = webSocket
        if (socket == null && activeRecorderJob == null) {
            releaseRecorder()
            _state.update { it.copy(status = ASRStatus.Idle) }
            return
        }

        _state.update { it.copy(status = ASRStatus.Stopping) }
        activeRecorderJob?.cancel()
        // AudioRecord.read() is blocking, so stop it to let the cancelled recorder job finish.
        runCatching { audioRecord?.stop() }

        finishTimeoutJob?.cancel()
        finishTimeoutJob = scope.launch {
            activeRecorderJob?.join()
            releaseRecorder()

            if (socket == null) {
                _state.update { it.copy(status = ASRStatus.Idle) }
                return@launch
            }
            if (webSocket !== socket) return@launch

            if (!wasListening) {
                socket.cancel()
                webSocket = null
                _state.update { it.copy(status = ASRStatus.Idle) }
                return@launch
            }

            if (!socket.send(finishTaskEvent().toString())) {
                Log.w(TAG, "Failed to finish DashScope streaming ASR session; closing websocket")
                socket.cancel()
                webSocket = null
                _state.update { it.copy(status = ASRStatus.Idle) }
                return@launch
            }

            delay(FINISH_TIMEOUT_MS)
            if (webSocket === socket) {
                Log.w(TAG, "Timed out waiting for task-finished; closing websocket")
                socket.close(1000, "task finish timeout")
                webSocket = null
                _state.update { it.copy(status = ASRStatus.Idle) }
            }
        }
    }

    override fun dispose() {
        recorderJob?.cancel()
        releaseRecorder()
        finishTimeoutJob?.cancel()
        finishTimeoutJob = null
        webSocket?.cancel()
        webSocket = null
        scope.cancel()
    }

    private fun handleServerEvent(socket: WebSocket, text: String) {
        val event = runCatching { JSONObject(text) }.getOrElse {
            Log.w(TAG, "Invalid ASR event: $text", it)
            return
        }
        val header = event.optJSONObject("header") ?: return
        when (val type = header.optString("event")) {
            "task-started" -> {
                if (webSocket === socket && recorderJob == null) {
                    startRecorder(socket)
                }
            }

            "result-generated" -> {
                val sentence = event.optJSONObject("payload")
                    ?.optJSONObject("output")
                    ?.optJSONObject("sentence") ?: return
                if (sentence.optBoolean("heartbeat", false)) return
                val text = sentence.optString("text")
                val sentenceId = sentence.optInt("sentence_id", 0)
                if (sentence.optBoolean("sentence_end", false)) {
                    partialTranscripts.remove(sentenceId)
                    if (text.isNotBlank()) completedTranscripts.add(text)
                } else if (text.isNotEmpty()) {
                    partialTranscripts[sentenceId] = text
                } else {
                    return
                }
                publishTranscript()
            }

            "task-finished" -> {
                finishTimeoutJob?.cancel()
                finishTimeoutJob = null
                pauseCapture()
                socket.close(1000, "recognition finished")
            }

            "task-failed" -> {
                val message = header.optString("error_message")
                    .ifBlank { header.optString("error_code").ifBlank { "ASR task failed" } }
                Log.e(TAG, "DashScope streaming ASR task failed: $message")
                setError(message)
            }

            else -> Log.v(TAG, "Ignored ASR event: $type")
        }
    }

    private fun publishTranscript() {
        val transcript = (completedTranscripts + partialTranscripts.values)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch { onTranscriptChange?.invoke(transcript) }
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(socket: WebSocket) {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                provider.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBufferSize
                .coerceAtLeast(provider.sampleRate / 10 * 2)
                .coerceAtLeast(4096)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                provider.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            audioRecord = recorder

            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
                        if (socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                            // Raw PCM binary frame (no custom header).
                            socket.send(buffer.copyOfRange(0, read).toByteString())
                        } else {
                            Log.w(TAG, "WebSocket queue full, dropping audio frame")
                        }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Audio recording failed", e)
                    scope.launch(Dispatchers.Main.immediate) {
                        if (webSocket === socket) setError(e.message ?: "Audio recording failed")
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                if (audioRecord === recorder) audioRecord = null
            }
        }
    }

    private fun runTaskEvent(): JSONObject {
        val parameters = JSONObject()
            .put("format", "pcm")
            .put("sample_rate", provider.sampleRate)
        if (provider.language.isNotBlank()) {
            parameters.put("language_hints", JSONArray().put(provider.language.trim()))
        }
        if (provider.keepDialect) {
            parameters.put("keep_dialect", true)
        }
        return JSONObject().put(
            "header",
            JSONObject()
                .put("action", "run-task")
                .put("task_id", taskId)
                .put("streaming", "duplex")
        ).put(
            "payload",
            JSONObject()
                .put("task_group", "audio")
                .put("task", "asr")
                .put("function", "recognition")
                .put("model", provider.model.trim())
                .put("parameters", parameters)
                .put("input", JSONObject())
        )
    }

    private fun finishTaskEvent(): JSONObject {
        return JSONObject().put(
            "header",
            JSONObject()
                .put("action", "finish-task")
                .put("task_id", taskId)
                .put("streaming", "duplex")
        ).put("payload", JSONObject().put("input", JSONObject()))
    }

    private fun setError(message: String) {
        pauseCapture()
        finishTimeoutJob?.cancel()
        val socket = webSocket
        webSocket = null
        socket?.cancel()
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }
}
