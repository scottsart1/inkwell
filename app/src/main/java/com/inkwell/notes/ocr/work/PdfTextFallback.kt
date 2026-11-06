package com.inkwell.notes.ocr.work

/** What the indexer does with a PDF page whose text layer could not be read for now. */
internal object PdfTextFallback {
    /** The plan for one such page. */
    enum class Plan {
        /** Keep the earlier PDF text and OCR rows and try the text layer again later (not indexed). */
        RETRY_LATER,
        /** OCR the rendered page in place of its text layer, and mark it indexed. */
        OCR,
        /**
         * Keep the earlier PDF text rows (nothing replaces them: OCR of imports is off) but stop
         * retrying: mark the page indexed.
         */
        KEEP_PDF_TEXT,
        /** No earlier rows and no OCR: the page has no PDF text for now; mark it indexed. */
        NO_TEXT,
    }

    /**
     * True to OCR the page now (and mark it indexed), false to keep its earlier PDF text and OCR
     * rows and try the text layer again later. Waiting only makes sense when there are earlier
     * rows to keep ([indexedBefore]) and the failure may pass; a page that was never indexed, or
     * a file whose text extraction keeps running out of memory ([givenUp]), would otherwise stay
     * unsearchable and be retried on every run.
     */
    fun ocrInstead(indexedBefore: Boolean, givenUp: Boolean): Boolean = !indexedBefore || givenUp

    /**
     * [ocrInstead], taking into account whether OCR of imported pages will run at all ([autoOcr]):
     * without it, a page indexed before keeps its PDF text rows instead of losing them to nothing.
     */
    fun plan(indexedBefore: Boolean, givenUp: Boolean, autoOcr: Boolean): Plan = when {
        !ocrInstead(indexedBefore, givenUp) -> Plan.RETRY_LATER
        autoOcr -> Plan.OCR
        indexedBefore -> Plan.KEEP_PDF_TEXT
        else -> Plan.NO_TEXT
    }
}
