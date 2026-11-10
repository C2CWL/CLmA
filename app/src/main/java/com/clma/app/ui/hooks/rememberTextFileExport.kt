package com.clma.app.ui.hooks

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.clma.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uses SAF so exporting never requires broad storage access or the clipboard. */
@Composable
fun rememberTextFileExport(filePrefix: String, content: () -> String): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentContent by rememberUpdatedState(content)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val text = currentContent()
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Cannot open export destination")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                R.string.diagnostic_export_saved
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                R.string.diagnostic_export_failed
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    return {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        launcher.launch("$filePrefix-$timestamp.txt")
    }
}
