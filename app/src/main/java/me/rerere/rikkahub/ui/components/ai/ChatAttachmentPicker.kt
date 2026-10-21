package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.ChatAttachmentKind
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.classifyChatAttachment
import me.rerere.rikkahub.utils.guessMimeFromFileName
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

/**
 * D3 — a pragmatic client-side ceiling on a single clip, chosen so an attachment cannot be
 * copied into the app's private dir and then base64-inflated (~1.33x) into a request the
 * provider would reject anyway. NOT a provider-published limit; adjust freely.
 */
private const val MAX_VIDEO_ATTACHMENT_BYTES = 100L * 1024 * 1024
private const val MAX_AUDIO_ATTACHMENT_BYTES = 20L * 1024 * 1024

internal data class ChatAttachmentPickerActions(
    val onTakePicture: () -> Unit,
    val onPickImage: () -> Unit,
    val onPickVideo: () -> Unit,
    val onPickAudio: () -> Unit,
    val onPickFile: () -> Unit,
)

@Composable
internal fun rememberChatAttachmentPickerActions(
    inputState: ChatInputState,
    setting: Settings,
    onAttachmentAdded: () -> Unit,
): ChatAttachmentPickerActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            onAttachmentAdded()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                onAttachmentAdded()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onTakePicture: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            onAttachmentAdded()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    onAttachmentAdded()
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        val source = selectedUris.first()
                        // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                        val converted = ImageUtils.isHeifImage(context, source) &&
                            ImageUtils.convertHeifToJpeg(context, source, tempFile)
                        if (!converted) {
                            context.contentResolver.openInputStream(source)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        preCropTempFile = tempFile
                        launchImageCrop(tempFile.toUri())
                    }.onFailure {
                        Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    onAttachmentAdded()
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            val accepted = selectedUris.filterNot { uri ->
                val size = filesManager.getFileSize(uri)
                if (size != null && size > MAX_VIDEO_ATTACHMENT_BYTES) {
                    toaster.show(
                        resources.getString(
                            R.string.chat_input_attachment_too_large,
                            resources.getString(R.string.video),
                            size / (1024 * 1024),
                        ),
                        type = ToastType.Error,
                    )
                    true
                } else {
                    false
                }
            }
            if (accepted.isNotEmpty()) {
                inputState.addVideos(filesManager.createChatFilesByContents(accepted))
                onAttachmentAdded()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            val accepted = selectedUris.filterNot { uri ->
                val size = filesManager.getFileSize(uri)
                if (size != null && size > MAX_AUDIO_ATTACHMENT_BYTES) {
                    toaster.show(
                        resources.getString(
                            R.string.chat_input_attachment_too_large,
                            resources.getString(R.string.audio),
                            size / (1024 * 1024),
                        ),
                        type = ToastType.Error,
                    )
                    true
                } else {
                    false
                }
            }
            if (accepted.isNotEmpty()) {
                inputState.addAudios(filesManager.createChatFilesByContents(accepted))
                onAttachmentAdded()
            }
        }

    // Prefer GetMultipleContents over OpenMultipleDocuments: several OEM document UIs
    // mishandle ACTION_OPEN_DOCUMENT + */*, while GET_CONTENT is what the image/video
    // buttons already use successfully. Any format is accepted; media is reclassified.
    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isEmpty()) {
                Log.d(TAG, "File picker: no files selected")
                return@rememberLauncherForActivityResult
            }
            Log.d(TAG, "File picker: selected ${uris.size} uri(s)")
            val images = mutableListOf<Uri>()
            val videos = mutableListOf<Uri>()
            val audios = mutableListOf<Uri>()
            val documents = mutableListOf<UIMessagePart.Document>()

            uris.forEach { uri ->
                val fileName = filesManager.getFileNameFromUri(uri)
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "file"
                val mime = filesManager.getFileMimeType(uri)
                    ?: guessMimeFromFileName(fileName)
                    ?: "application/octet-stream"
                val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                    ?: run {
                        toaster.show(
                            resources.getString(R.string.chat_input_file_read_failed, fileName),
                            type = ToastType.Error,
                        )
                        return@forEach
                    }

                when (classifyChatAttachment(fileName, mime)) {
                    ChatAttachmentKind.IMAGE -> images.add(localUri)
                    ChatAttachmentKind.VIDEO -> {
                        val size = filesManager.getFileSize(uri)
                        if (size != null && size > MAX_VIDEO_ATTACHMENT_BYTES) {
                            toaster.show(
                                resources.getString(
                                    R.string.chat_input_attachment_too_large,
                                    resources.getString(R.string.video),
                                    size / (1024 * 1024),
                                ),
                                type = ToastType.Error,
                            )
                            filesManager.deleteChatFiles(listOf(localUri))
                        } else {
                            videos.add(localUri)
                        }
                    }
                    ChatAttachmentKind.AUDIO -> {
                        val size = filesManager.getFileSize(uri)
                        if (size != null && size > MAX_AUDIO_ATTACHMENT_BYTES) {
                            toaster.show(
                                resources.getString(
                                    R.string.chat_input_attachment_too_large,
                                    resources.getString(R.string.audio),
                                    size / (1024 * 1024),
                                ),
                                type = ToastType.Error,
                            )
                            filesManager.deleteChatFiles(listOf(localUri))
                        } else {
                            audios.add(localUri)
                        }
                    }
                    ChatAttachmentKind.DOCUMENT -> {
                        documents.add(
                            UIMessagePart.Document(
                                url = localUri.toString(),
                                fileName = fileName,
                                mime = mime,
                            )
                        )
                    }
                }
            }

            if (images.isNotEmpty()) inputState.addImages(images)
            if (videos.isNotEmpty()) inputState.addVideos(videos)
            if (audios.isNotEmpty()) inputState.addAudios(audios)
            if (documents.isNotEmpty()) inputState.addFiles(documents)
            if (images.isNotEmpty() || videos.isNotEmpty() || audios.isNotEmpty() || documents.isNotEmpty()) {
                onAttachmentAdded()
            }
        }

    return ChatAttachmentPickerActions(
        onTakePicture = onTakePicture,
        onPickImage = { imagePickerLauncher.launch("image/*") },
        onPickVideo = { videoPickerLauncher.launch("video/*") },
        onPickAudio = { audioPickerLauncher.launch("audio/*") },
        onPickFile = { filePickerLauncher.launch("*/*") },
    )
}

private const val TAG = "ChatAttachmentPicker"
