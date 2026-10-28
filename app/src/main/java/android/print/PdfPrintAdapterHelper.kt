package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred

suspend fun PrintDocumentAdapter.printLayout(
    attributes: PrintAttributes,
    signal: CancellationSignal
) {
    val layout = CompletableDeferred<Unit>()
    onStart()
    onLayout(null, attributes, signal, object : PrintDocumentAdapter.LayoutResultCallback() {
        override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) { layout.complete(Unit) }
        override fun onLayoutFailed(error: CharSequence?) { layout.completeExceptionally(IllegalStateException("pdf_layout_failed")) }
        override fun onLayoutCancelled() { layout.cancel() }
    }, null)
    layout.await()
}

suspend fun PrintDocumentAdapter.printWrite(
    descriptor: ParcelFileDescriptor,
    signal: CancellationSignal
) {
    val write = CompletableDeferred<Unit>()
    onWrite(arrayOf(PageRange.ALL_PAGES), descriptor, signal, object : PrintDocumentAdapter.WriteResultCallback() {
        override fun onWriteFinished(pages: Array<out PageRange>?) { write.complete(Unit) }
        override fun onWriteFailed(error: CharSequence?) { write.completeExceptionally(IllegalStateException("pdf_write_failed")) }
        override fun onWriteCancelled() { write.cancel() }
    })
    write.await()
}
