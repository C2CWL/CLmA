package com.clma.app.ui.pages.backup.tabs

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import com.clma.app.R
import com.clma.app.data.sync.BackupItem
import com.clma.app.data.sync.BackupLayout
import com.clma.app.data.sync.BackupNaming
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.StickyHeader
import com.clma.app.ui.context.LocalToaster
import com.clma.app.ui.pages.backup.BackupVM
import com.clma.app.ui.pages.backup.components.BackupItemPicker
import com.clma.app.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selectedBackupItems by vm.localBackupItems.collectAsStateWithLifecycle()
    val backupItemSizes by vm.backupItemSizes.collectAsStateWithLifecycle()
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }

    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirm by remember { mutableStateOf("") }
    var exportPasswordVisible by remember { mutableStateOf(false) }

    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var restorePassword by remember { mutableStateOf("") }
    var restorePasswordVisible by remember { mutableStateOf(false) }
    var pendingRestoreFile by remember { mutableStateOf<File?>(null) }

    // 导入类型：local 为本地备份，chatbox 为 Chatbox 导入，cherry 为 Cherry Studio 导入
    var importType by remember { mutableStateOf("local") }

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    val pwd = if (BackupItem.SECRETS in selectedBackupItems) exportPassword else ""
                    val exportFile = vm.exportToFile(pwd)

                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(exportFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    val exportedSize = exportFile.length()
                    exportFile.delete()

                    toaster.show(
                        context.getString(
                            R.string.backup_page_export_success_size,
                            exportedSize.fileSizeToString(),
                        ),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isExporting = false
                exportPassword = ""
                exportPasswordConfirm = ""
            }
        }
    }

    val performLocalRestore: (File, String) -> Unit = { tempFile, pwd ->
        scope.launch {
            isRestoring = true
            runCatching {
                vm.restoreFromLocalFile(tempFile, pwd)
                tempFile.delete()
                toaster.show(
                    context.getString(R.string.backup_page_restore_success),
                    type = ToastType.Success
                )
                onShowRestartDialog()
            }.onFailure { e ->
                e.printStackTrace()
                tempFile.delete()
                toaster.show(
                    context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                    type = ToastType.Error
                )
            }
            isRestoring = false
        }
    }

    // 创建文件选择的launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            when (importType) {
                "local" -> {
                    scope.launch {
                        isRestoring = true
                        val tempFile =
                            File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")
                        val copyOk = runCatching {
                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }.isSuccess

                        if (!copyOk) {
                            tempFile.delete()
                            isRestoring = false
                            toaster.show(
                                context.getString(R.string.backup_page_restore_failed, "Failed to read file"),
                                type = ToastType.Error
                            )
                            return@launch
                        }

                        val hasEncryptedSecrets = runCatching {
                            java.util.zip.ZipFile(tempFile).use { zip ->
                                zip.getEntry(BackupLayout.SECRETS_FILE) != null
                            }
                        }.getOrDefault(false)

                        isRestoring = false
                        if (hasEncryptedSecrets && BackupItem.SECRETS in selectedBackupItems) {
                            pendingRestoreFile = tempFile
                            restorePassword = ""
                            restorePasswordVisible = false
                            showRestorePasswordDialog = true
                        } else {
                            performLocalRestore(tempFile, "")
                        }
                    }
                }

                "chatbox" -> {
                    scope.launch {
                        isRestoring = true
                        runCatching {
                            val tempFile =
                                File(context.cacheDir, "temp_chatbox_${System.currentTimeMillis()}.zip")
                            try {
                                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                    FileOutputStream(tempFile).use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                                vm.restoreFromChatBox(tempFile)
                            } finally {
                                tempFile.delete()
                            }
                            toaster.show(
                                context.getString(R.string.backup_page_restore_success),
                                type = ToastType.Success
                            )
                            onShowRestartDialog()
                        }.onFailure { e ->
                            e.printStackTrace()
                            toaster.show(
                                context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                                type = ToastType.Error
                            )
                        }
                        isRestoring = false
                    }
                }

                "cherry" -> {
                    scope.launch {
                        isRestoring = true
                        runCatching {
                            val tempFile =
                                File(context.cacheDir, "temp_cherry_${System.currentTimeMillis()}.zip")
                            try {
                                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                    FileOutputStream(tempFile).use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                                vm.restoreFromCherryStudio(tempFile)
                            } finally {
                                tempFile.delete()
                            }
                            toaster.show(
                                context.getString(R.string.backup_page_restore_success),
                                type = ToastType.Success
                            )
                            onShowRestartDialog()
                        }.onFailure { e ->
                            e.printStackTrace()
                            toaster.show(
                                context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                                type = ToastType.Error
                            )
                        }
                        isRestoring = false
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
                    supportingContent = {
                        BackupItemPicker(
                            selected = selectedBackupItems,
                            sizes = backupItemSizes,
                            onToggle = { item ->
                                val newItems = if (item in selectedBackupItems) {
                                    selectedBackupItems - item
                                } else {
                                    selectedBackupItems + item
                                }
                                vm.updateLocalBackupItems(BackupItem.normalize(newItems))
                            },
                        )
                    },
                )
                item(
                    onClick = if (!isExporting) {
                        {
                            if (BackupItem.SECRETS in selectedBackupItems) {
                                exportPassword = ""
                                exportPasswordConfirm = ""
                                exportPasswordVisible = false
                                showExportPasswordDialog = true
                            } else {
                                exportPassword = ""
                                val timestamp = LocalDateTime.now()
                                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                                createDocumentLauncher.launch(BackupNaming.localExportFileName(timestamp))
                            }
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            showImportConfirmDialog = true
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                stringResource(R.string.backup_page_importing)
                            } else {
                                stringResource(R.string.backup_page_import_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_import_from_other_app))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "chatbox"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "chatbox") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "cherry"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "cherry") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showImportConfirmDialog = false },
            title = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            text = { Text(stringResource(R.string.backup_page_import_overwrite_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        importType = "local"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showExportPasswordDialog) {
        val passwordMismatch = exportPasswordConfirm.isNotEmpty() && exportPassword != exportPasswordConfirm
        val canConfirm = exportPassword.isNotBlank() && exportPassword == exportPasswordConfirm
        AlertDialog(
            onDismissRequest = { showExportPasswordDialog = false },
            title = { Text(stringResource(R.string.backup_secrets_password_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.backup_secrets_password_required))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = exportPassword,
                        onValueChange = { exportPassword = it },
                        label = { Text(stringResource(R.string.backup_secrets_encryption_password)) },
                        placeholder = { Text(stringResource(R.string.backup_secrets_encryption_password_hint)) },
                        visualTransformation = if (exportPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            val image = if (exportPasswordVisible) HugeIcons.ViewOff else HugeIcons.View
                            IconButton(onClick = { exportPasswordVisible = !exportPasswordVisible }) {
                                Icon(imageVector = image, contentDescription = null)
                            }
                        },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = exportPasswordConfirm,
                        onValueChange = { exportPasswordConfirm = it },
                        label = { Text(stringResource(R.string.backup_secrets_confirm_password)) },
                        isError = passwordMismatch,
                        supportingText = if (passwordMismatch) {
                            { Text(stringResource(R.string.backup_secrets_password_mismatch)) }
                        } else null,
                        visualTransformation = if (exportPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportPasswordDialog = false
                        val timestamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        createDocumentLauncher.launch(BackupNaming.localExportFileName(timestamp))
                    },
                    enabled = canConfirm
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportPasswordDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showRestorePasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showRestorePasswordDialog = false
                pendingRestoreFile?.delete()
                pendingRestoreFile = null
            },
            title = { Text(stringResource(R.string.backup_secrets_password_dialog_restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.backup_secrets_enter_password))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text(stringResource(R.string.backup_secrets_encryption_password)) },
                        placeholder = { Text(stringResource(R.string.backup_secrets_encryption_password_hint)) },
                        visualTransformation = if (restorePasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            val image = if (restorePasswordVisible) HugeIcons.ViewOff else HugeIcons.View
                            IconButton(onClick = { restorePasswordVisible = !restorePasswordVisible }) {
                                Icon(imageVector = image, contentDescription = null)
                            }
                        },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val fileToRestore = pendingRestoreFile
                        val pwd = restorePassword
                        showRestorePasswordDialog = false
                        pendingRestoreFile = null
                        if (fileToRestore != null) {
                            performLocalRestore(fileToRestore, pwd)
                        }
                    },
                    enabled = restorePassword.isNotBlank()
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRestorePasswordDialog = false
                        pendingRestoreFile?.delete()
                        pendingRestoreFile = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
