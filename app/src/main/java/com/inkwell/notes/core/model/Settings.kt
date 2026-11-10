package com.inkwell.notes.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What a single finger does on the canvas. */
@Serializable
enum class FingerAction { SCROLL, DRAW, SELECT }

/** S Pen side button behaviour. */
@Serializable
enum class SideButtonMode {
    /** Eraser while the button is held; release returns to the previous tool. */
    HOLD_TO_ERASE,
    /** Each press toggles between the current pen and the eraser. */
    TOGGLE_ERASER,
    /** Button held = lasso select while held. */
    HOLD_TO_LASSO,
    OFF,
}

@Serializable
enum class LongPressAction { CONTEXT_MENU, LASSO, PASTE_MENU, NONE }

@Serializable
enum class TapGestureAction { UNDO, REDO, TOGGLE_ERASER, NONE }

@Serializable
data class InputPrefs(
    /** When true only the stylus inks; fingers navigate (palm rejection by tool type). */
    val stylusOnlyInk: Boolean = true,
    val fingerAction: FingerAction = FingerAction.SCROLL,
    val sideButton: SideButtonMode = SideButtonMode.HOLD_TO_ERASE,
    val longPress: LongPressAction = LongPressAction.CONTEXT_MENU,
    val twoFingerTap: TapGestureAction = TapGestureAction.UNDO,
    val threeFingerTap: TapGestureAction = TapGestureAction.REDO,
    val scribbleToErase: Boolean = true,
    val circleToSelect: Boolean = false,
    /** Draw then hold still to snap the stroke into a straight line / clean shape. */
    val holdToStraighten: Boolean = true,
    /** Recognise shapes automatically when a stroke closes (without holding). */
    val autoShapeRecognition: Boolean = false,
    /** Front-buffered rendering for minimum latency; turn off if a device shows artifacts. */
    val lowLatencyInk: Boolean = true,
    /** Predict the next stylus samples to hide display latency. */
    val motionPrediction: Boolean = true,
    val showHoverCursor: Boolean = true,
    /** Ignore touches whose contact area is palm-sized even when finger drawing is on. */
    val rejectLargeContacts: Boolean = true,
    /** Allow zoom while inking with the other hand. */
    val zoomWhileWriting: Boolean = true,
    /** 0..1 gamma bias of the pressure curve: 0.5 = linear. */
    val pressureCurve: Float = 0.5f,
)

@Serializable
enum class ToolbarPosition { TOP, BOTTOM, LEFT, RIGHT, FLOATING }

@Serializable
data class ToolbarPrefs(
    val position: ToolbarPosition = ToolbarPosition.TOP,
    val compact: Boolean = false,
    /** Hide all chrome while writing in focus mode; edge swipe brings it back. */
    val focusModeHidesToolbar: Boolean = true,
    val floatingX: Float = 0.5f,
    val floatingY: Float = 0.08f,
)

@Serializable
enum class ScrollDirection { VERTICAL, HORIZONTAL }

@Serializable
data class ViewPrefs(
    val scrollDirection: ScrollDirection = ScrollDirection.VERTICAL,
    val keepScreenOn: Boolean = true,
    val showPageShadows: Boolean = true,
    val reopenAtLastPosition: Boolean = true,
)

@Serializable
data class NamingPrefs(
    /** Infer a topic and rename "YYYYMMDD_Untitled" files after [delayHours]. */
    val autoNameEnabled: Boolean = true,
    val delayHours: Int = 24,
    /** Use the configured AI model (if any) for topic inference; local keywords otherwise. */
    val useAi: Boolean = true,
    /** Rename imported files automatically too (default keeps their original names). */
    val renameImported: Boolean = false,
)

@Serializable
enum class LibraryView { GRID, LIST }

@Serializable
data class LibraryPrefs(
    val view: LibraryView = LibraryView.GRID,
    val sort: LibrarySort = LibrarySort.MODIFIED_DESC,
    val showArchived: Boolean = false,
)

@Serializable
enum class DockSide { LEFT, RIGHT }

@Serializable
data class RecordingPrefs(
    val dockSide: DockSide = DockSide.RIGHT,
    /** Remember the collapsed state between recordings. */
    val startCollapsed: Boolean = false,
    val autoTranscribe: Boolean = false,
    /** Highlight notes written at the current playback position. */
    val highlightDuringPlayback: Boolean = true,
    /** Seconds per crash-safe audio segment. */
    val segmentSeconds: Int = 120,
    val sampleRate: Int = 44100,
    val bitrate: Int = 96_000,
)

@Serializable
data class OcrPrefs(
    val autoOcrImports: Boolean = true,
    val recognizeHandwriting: Boolean = true,
    /** BCP-47 tags for handwriting recognition models. */
    val handwritingLanguages: List<String> = listOf("en-US"),
    /** Send low-confidence pages to the OCR_ASSIST model when AI is configured. */
    val aiAssistLowConfidence: Boolean = false,
    val lowConfidenceThreshold: Float = 0.6f,
)

/** Where generated outputs are copied in addition to this device (always local-first). */
@Serializable
enum class OutputTarget {
    /** A folder chosen with the system picker (Files, SD card, a provider that offers folders). */
    FOLDER,
    /** The user's Google Drive, in an "Inkwell" folder Inkwell creates (drive.file access only). */
    DRIVE,
    /**
     * The private notes vault on yourpitbox.com (docs/VAULT_API.md): a page readable on any
     * computer, behind its private link and password ([DestinationPrefs.vaultUrl], SecretStore).
     */
    VAULT,
}

/**
 * Reads a set of [OutputTarget] names and skips any it doesn't know, so settings written by
 * another version (one with a target this one lacks) never fail to load. A settings file that
 * fails to load is replaced with defaults, which would silently reset every preference.
 */
object OutputTargetSetSerializer : KSerializer<Set<OutputTarget>> {
    private val names = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = names.descriptor

    override fun serialize(encoder: Encoder, value: Set<OutputTarget>) =
        encoder.encodeSerializableValue(names, value.sortedBy { it.ordinal }.map { it.name })

    override fun deserialize(decoder: Decoder): Set<OutputTarget> =
        decoder.decodeSerializableValue(names).mapNotNullTo(LinkedHashSet()) { n -> OutputTarget.entries.firstOrNull { it.name == n } }
}

@Serializable
enum class NotesExportFormat { PDF, MARKDOWN, PDF_AND_MARKDOWN }

@Serializable
data class DestinationPrefs(
    /** Targets for notebooks/notes. Empty = this device only. */
    @Serializable(with = OutputTargetSetSerializer::class)
    val notes: Set<OutputTarget> = emptySet(),
    /** Targets for meeting/lecture transcripts. */
    @Serializable(with = OutputTargetSetSerializer::class)
    val transcripts: Set<OutputTarget> = emptySet(),
    /** Targets for raw audio recordings. */
    @Serializable(with = OutputTargetSetSerializer::class)
    val audio: Set<OutputTarget> = emptySet(),
    val notesFormat: NotesExportFormat = NotesExportFormat.PDF_AND_MARKDOWN,
    /** SAF tree URI chosen by the user (Files, SD card...). */
    val folderUri: String? = null,
    val folderLabel: String? = null,
    /**
     * The Google account whose Drive receives copies (its email address), or null when Drive is
     * not connected. Access itself is held by Google Play services, never by Inkwell.
     */
    val driveAccount: String? = null,
    /**
     * Opt-in: the "Inkwell" folder in Drive can be opened by anyone who has its link, without
     * signing in to Google (for a work computer that can't use a personal Google account). Off
     * by default; turning it off withdraws the link.
     */
    val driveShareLink: Boolean = false,
    /**
     * Private link of the notes vault, `https://yourpitbox.com/notes/<link-id>`; empty when not set.
     * Its password and session live only in SecretStore, never here.
     */
    val vaultUrl: String = "",
    /**
     * The user's own work email address for "Email to work" (notes and transcripts go there from
     * the tablet's mail app, for a work computer that can't open Google). Blank = not set yet.
     */
    val workEmail: String = "",
    /** Push automatically after edits settle; otherwise only on "Sync now". */
    val autoSync: Boolean = true,
    val audioOnUnmeteredOnly: Boolean = true,
)

@Serializable
data class PrivacyPrefs(
    val appLock: Boolean = false,
    val lockAfterSeconds: Int = 60,
    val blockScreenshots: Boolean = false,
    /** Confirm before any document content is sent to an external AI provider. */
    val confirmAiTransmission: Boolean = true,
)

@Serializable
data class NotebookDefaults(
    val template: PageTemplate = PageTemplate(),
)

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val input: InputPrefs = InputPrefs(),
    val toolbar: ToolbarPrefs = ToolbarPrefs(),
    val view: ViewPrefs = ViewPrefs(),
    val penPresets: List<PenPreset> = PenPreset.Defaults,
    val lastTools: ToolState = ToolState(),
    val naming: NamingPrefs = NamingPrefs(),
    val library: LibraryPrefs = LibraryPrefs(),
    val notebookDefaults: NotebookDefaults = NotebookDefaults(),
    val recording: RecordingPrefs = RecordingPrefs(),
    val speakers: SpeakerPrefs = SpeakerPrefs(),
    val ocr: OcrPrefs = OcrPrefs(),
    val ai: AiPrefs = AiPrefs(),
    val destinations: DestinationPrefs = DestinationPrefs(),
    val privacy: PrivacyPrefs = PrivacyPrefs(),
    val onboardingDone: Boolean = false,
)
