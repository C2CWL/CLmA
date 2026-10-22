package me.rerere.rikkahub.ui.pages.videogen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.VideoGenerationParams
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.VideoGenerationItem
import me.rerere.rikkahub.data.ai.tools.local.MAX_VIDEO_DURATION_SECONDS
import me.rerere.rikkahub.data.ai.tools.local.MIN_VIDEO_DURATION_SECONDS
import me.rerere.rikkahub.data.ai.tools.local.clampVideoCount
import me.rerere.rikkahub.data.ai.tools.local.videoGalleryRelativePath
import me.rerere.rikkahub.data.ai.tools.local.videoGenFilename
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * One generated video, as the page shows it. Mirrors `GeneratedImage` from the image page.
 */
@Serializable
data class GeneratedVideo(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String,
)

/** The on-disk prefix the page writes into `gen_media.path` for every clip. */
private const val VIDEO_PREFIX = "videos/"

/**
 * Resolves a `gen_media` row to its file, exactly the way the writer stored it
 * ([videoGalleryRelativePath] → `videos/<name>`).
 */
private fun GenMediaEntity.toGeneratedVideo(filesManager: FilesManager): GeneratedVideo =
    GeneratedVideo(
        id = this.id,
        prompt = this.prompt,
        filePath = File(filesManager.getVideosDir(), this.path.removePrefix(VIDEO_PREFIX)).absolutePath,
        timestamp = this.createAt,
        model = this.modelId,
    )

/**
 * Pure selection logic backing the video gallery's orphan purge: given every persisted video row
 * and the videos directory, returns the entities whose backing file no longer exists on disk.
 * Top-level so it is unit-testable without constructing the VM (mirrors the image page's
 * `selectOrphanedGenMedia`).
 */
internal fun selectOrphanedVideoMedia(
    entities: List<GenMediaEntity>,
    videosDir: File,
): List<GenMediaEntity> =
    entities.filter { entity -> !File(videosDir, entity.path.removePrefix(VIDEO_PREFIX)).exists() }

class VideoGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    val providerManager: ProviderManager,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
) : AndroidViewModel(context) {
    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _aspectRatio = MutableStateFlow(ImageAspectRatio.LANDSCAPE)
    val aspectRatio: StateFlow<ImageAspectRatio> = _aspectRatio

    private val _durationSeconds = MutableStateFlow(DEFAULT_DURATION_SECONDS)
    val durationSeconds: StateFlow<Int> = _durationSeconds

    /**
     * How many clips one generate call produces. Shared with the `generate_video` tool: the provider
     * runs N sequential jobs (neither vendor takes an `n` for video).
     */
    private val _numberOfVideos = MutableStateFlow(1)
    val numberOfVideos: StateFlow<Int> = _numberOfVideos

    /**
     * The single first frame for image-to-video, or `null` for text-to-video. Deliberately one
     * slot: the wired i2v endpoints take exactly one first frame.
     */
    private val _referenceImage = MutableStateFlow<String?>(null)
    val referenceImage: StateFlow<String?> = _referenceImage

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating
    private var cancelJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentVideos = MutableStateFlow<List<GeneratedVideo>>(emptyList())
    val currentVideos: StateFlow<List<GeneratedVideo>> = _currentVideos

    val pager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getMediaByType(GenMediaEntity.TYPE_VIDEO_GENERATION) },
    )
    val generatedVideos: Flow<PagingData<GeneratedVideo>> = pager.flow
        .map { pagingData -> pagingData.map { entity -> entity.toGeneratedVideo(filesManager) } }
        .cachedIn(viewModelScope)

    init {
        purgeOrphanedVideoMedia()
    }

    // One-shot purge of gallery entries whose backing file is missing (mirrors the image page).
    private fun purgeOrphanedVideoMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entities = genMediaRepository.getAllMediaByType(GenMediaEntity.TYPE_VIDEO_GENERATION)
                val orphans = selectOrphanedVideoMedia(entities, filesManager.getVideosDir())
                orphans.forEach { genMediaRepository.deleteMedia(it.id) }
                if (orphans.isNotEmpty()) {
                    Log.i(TAG, "Purged ${orphans.size} orphaned video gallery entries")
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Failed to purge orphaned video gallery entries", e)
            }
        }
    }

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateAspectRatio(aspectRatio: ImageAspectRatio) {
        _aspectRatio.value = aspectRatio
    }

    /** Clamped to the same 2–15 window the `generate_video` tool advertises. */
    fun updateDurationSeconds(seconds: Int) {
        _durationSeconds.value = seconds.coerceIn(MIN_VIDEO_DURATION_SECONDS, MAX_VIDEO_DURATION_SECONDS)
    }

    /** Clamped to the same 1–4 window (and the same helper) the `generate_video` tool uses. */
    fun updateNumberOfVideos(count: Int) {
        _numberOfVideos.value = clampVideoCount(count)
    }

    fun setReferenceImage(path: String?) {
        _referenceImage.value = path
    }

    fun clearError() {
        _error.value = null
    }

    fun startNewSession() {
        cancelJob?.cancel()
        _prompt.value = ""
        _referenceImage.value = null
        _currentVideos.value = emptyList()
        _error.value = null
        _isGenerating.value = false
    }

    fun generateVideo() {
        val promptText = _prompt.value.trim()
        if (promptText.isEmpty()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.videoGenerationModelId)
                    ?: throw IllegalStateException("No video model selected")
                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                val items = providerManager.getProviderByType(provider).generateVideo(
                    providerSetting = provider,
                    params = VideoGenerationParams(
                        model = model,
                        prompt = promptText,
                        aspectRatio = _aspectRatio.value,
                        durationSeconds = _durationSeconds.value,
                        numOfVideos = _numberOfVideos.value,
                        sourceImages = listOfNotNull(_referenceImage.value),
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )

                collectVideoGeneration(
                    items = items,
                    prompt = promptText,
                    modelName = model.displayName,
                )
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Failed to generate video", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    private suspend fun collectVideoGeneration(
        items: Flow<VideoGenerationItem>,
        prompt: String,
        modelName: String,
    ) {
        val collected = mutableListOf<GeneratedVideo>()
        items.collect { item ->
            if (item.partial) return@collect
            val timestamp = System.currentTimeMillis()
            val filename = videoGenFilename(modelName, timestamp, collected.size)
            val file = File(filesManager.getVideosDir(), filename)
            filesManager.createVideoFileFromBase64(item.data, file.absolutePath)
            genMediaRepository.insertMedia(
                GenMediaEntity(
                    path = videoGalleryRelativePath(filename),
                    modelId = modelName,
                    prompt = prompt,
                    createAt = timestamp,
                    type = GenMediaEntity.TYPE_VIDEO_GENERATION,
                )
            )
            collected += GeneratedVideo(
                id = 0,
                prompt = prompt,
                filePath = file.absolutePath,
                timestamp = timestamp,
                model = modelName,
            )
            _currentVideos.value = collected.toList()
        }
    }

    fun deleteVideo(video: GeneratedVideo) {
        viewModelScope.launch { deleteVideos(listOf(video)) }
    }

    /** Deletes the rows that succeed; returns the ones that failed, for the caller to report. */
    suspend fun deleteVideos(videos: List<GeneratedVideo>): List<GeneratedVideo> =
        withContext(Dispatchers.IO) {
            videos.filter { video ->
                try {
                    val file = File(video.filePath)
                    check(!file.exists() || file.delete()) { "Failed to delete video file" }
                    genMediaRepository.deleteMedia(video.id)
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete video ${video.id}", e)
                    true
                }
            }
        }

    companion object {
        private const val TAG = "VideoGenVM"
        const val DEFAULT_DURATION_SECONDS = 5
    }
}
