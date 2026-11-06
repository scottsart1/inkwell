package com.inkwell.notes.pdf

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Remembers an ML Kit document scanner (edge detection, perspective correction, cleanup,
 * gallery import) and returns a function that opens it. [onResult] receives the scan as a PDF
 * plus one JPEG per page; nothing is called when the user cancels.
 *
 * When the scanner is unavailable (no Google Play services, module not downloadable yet) a
 * short message is shown as a toast.
 */
@Composable
fun rememberDocumentScanner(onResult: (pdfUri: Uri?, imageUris: List<Uri>) -> Unit): () -> Unit =
    rememberDocumentScanner(onResult, onUnavailable = null)

/** As above; [onUnavailable] receives the user-facing message instead of a toast when set. */
@Composable
fun rememberDocumentScanner(
    onResult: (pdfUri: Uri?, imageUris: List<Uri>) -> Unit,
    onUnavailable: ((message: String) -> Unit)?,
): () -> Unit {
    val context = LocalContext.current
    val latestResult by rememberUpdatedState(onResult)
    val latestUnavailable by rememberUpdatedState(onUnavailable)
    var launching by remember { mutableStateOf(false) }

    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF, GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build(),
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        launching = false
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val scan = try {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unreadable scanner result (${e.javaClass.simpleName})")
            null
        } ?: return@rememberLauncherForActivityResult
        val pdf = scan.pdf?.uri
        val pages = scan.pages.orEmpty().mapNotNull { it.imageUri }
        if (pdf != null || pages.isNotEmpty()) latestResult(pdf, pages)
    }

    fun report(messageRes: Int) {
        launching = false
        val message = context.getString(messageRes)
        val custom = latestUnavailable
        if (custom != null) custom(message) else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    return remember(scanner, launcher, context) {
        launch@{
            if (launching) return@launch
            val activity = context.findActivity()
            if (activity == null) {
                report(R.string.pdf_scanner_unavailable)
                return@launch
            }
            launching = true
            try {
                scanner.getStartScanIntent(activity)
                    .addOnSuccessListener { sender ->
                        try {
                            launcher.launch(IntentSenderRequest.Builder(sender).build())
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "Scanner launch failed (${e.javaClass.simpleName})")
                            report(R.string.pdf_scanner_failed)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Scanner unavailable (${e.javaClass.simpleName})")
                        report(R.string.pdf_scanner_unavailable)
                    }
                    .addOnCanceledListener { launching = false }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Scanner unavailable (${e.javaClass.simpleName})")
                report(R.string.pdf_scanner_unavailable)
            }
        }
    }
}

/**
 * Imports what [rememberDocumentScanner] returned, preferring the scanner's PDF and falling
 * back to its page images. The document gets the standard "YYYYMMDD_Untitled" name, so it is
 * auto-named from its recognised text. The scanner's temporary files are removed afterwards,
 * whether or not the import succeeded.
 */
suspend fun PdfService.importScanResult(pdfUri: Uri?, imageUris: List<Uri>, folderId: String? = null): DocumentMeta {
    val service = this
    try {
        return when {
            pdfUri != null -> try {
                if (service is PdfServiceImpl) service.importScannedPdf(pdfUri, folderId) else service.importPdf(pdfUri, folderId)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (imageUris.isEmpty()) throw e
                Log.w(TAG, "Scanned PDF import failed (${e.javaClass.simpleName}); using page images")
                service.importImages(imageUris, folderId, title = null)
            }
            imageUris.isNotEmpty() -> service.importImages(imageUris, folderId, title = null)
            else -> throw nothingScanned()
        }
    } finally {
        // The scan cannot be imported again from these files, so they go even when the import failed.
        withContext(NonCancellable) { deleteScannerFiles(listOfNotNull(pdfUri) + imageUris) }
    }
}

/** [importScanResult] on the process graph's PDF service. */
suspend fun importScanResult(pdfUri: Uri?, imageUris: List<Uri>, folderId: String? = null): DocumentMeta =
    AppGraph.get().pdf.importScanResult(pdfUri, imageUris, folderId)

private fun nothingScanned(): PdfException {
    val context = try {
        AppGraph.get().context
    } catch (e: IllegalStateException) {
        null
    }
    return if (context != null) PdfErrors.create(context, PdfError.NOTHING_SCANNED)
    else PdfException(PdfError.NOTHING_SCANNED, "The scan did not contain any pages.")
}

/** The scanner writes into this app's cache; those copies are not needed after import. */
private suspend fun deleteScannerFiles(uris: List<Uri>) = withContext(Dispatchers.IO) {
    for (uri in uris) {
        if (uri.scheme != "file") continue
        val path = uri.path ?: continue
        if (PdfNames.isScannerOutput(path)) runCatching { File(path).delete() }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val TAG = "DocumentScanner"
