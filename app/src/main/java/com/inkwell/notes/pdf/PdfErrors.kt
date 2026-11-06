package com.inkwell.notes.pdf

import android.content.Context
import androidx.annotation.StringRes
import com.inkwell.notes.R
import com.inkwell.notes.core.storage.UnreadablePageException

/** Why a PDF operation failed; each maps to a message the user can act on. */
enum class PdfError(@StringRes val messageRes: Int) {
    OPEN_FAILED(R.string.pdf_error_open_failed),
    NOT_PDF(R.string.pdf_error_not_pdf),
    EMPTY_FILE(R.string.pdf_error_empty_file),
    PASSWORD_REQUIRED(R.string.pdf_error_password),
    DAMAGED(R.string.pdf_error_damaged),
    NO_PAGES(R.string.pdf_error_no_pages),
    NO_SPACE(R.string.pdf_error_no_space),
    SAVE_FAILED(R.string.pdf_error_save_failed),
    MERGE_FAILED(R.string.pdf_error_merge_failed),
    SOURCE_UNREADABLE(R.string.pdf_error_source_unreadable),
    IMAGES_UNREADABLE(R.string.pdf_error_images_unreadable),
    NO_IMAGES(R.string.pdf_error_no_images),
    DOCUMENT_MISSING(R.string.pdf_error_document_missing),
    UNSUPPORTED_DOCUMENT(R.string.pdf_error_unsupported_document),
    NO_PAGES_SELECTED(R.string.pdf_error_no_pages_selected),
    INVALID_SPLIT(R.string.pdf_error_invalid_split),
    NOTHING_SCANNED(R.string.pdf_error_nothing_scanned),
    TOO_LARGE(R.string.pdf_error_too_large),
    /** The stored content of a page to copy could not be read. */
    PAGE_UNREADABLE(R.string.pdf_error_page_unreadable),
}

/**
 * A failed PDF operation. [message] is a complete, user-facing sentence (localised), safe to
 * show in a snackbar; it never contains file contents or paths.
 */
class PdfException(val error: PdfError, message: String, cause: Throwable? = null) : Exception(message, cause)

internal object PdfErrors {
    fun create(context: Context, error: PdfError, cause: Throwable? = null): PdfException {
        val message = runCatching { context.getString(error.messageRes) }.getOrNull()
            ?: error.name.lowercase().replace('_', ' ')
        return PdfException(error, message, cause)
    }

    /** True when [t] (or a cause) reports a full disk. */
    fun isOutOfSpace(t: Throwable?): Boolean {
        var c = t
        var depth = 0
        while (c != null && depth < 8) {
            val m = c.message.orEmpty()
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return true
            c = c.cause
            depth++
        }
        return false
    }

    /** Maps an unexpected failure while writing files to the most helpful error. */
    fun forWrite(context: Context, t: Throwable): PdfException =
        if (t is PdfException) t else create(context, writeError(t), t)

    /** The error [forWrite] reports for [t]. */
    fun writeError(t: Throwable): PdfError = when {
        t is PdfException -> t.error
        isOutOfSpace(t) -> PdfError.NO_SPACE
        t is OutOfMemoryError -> PdfError.TOO_LARGE
        t is UnreadablePageException -> PdfError.PAGE_UNREADABLE
        else -> PdfError.SAVE_FAILED
    }
}
