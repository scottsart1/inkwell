# Inkwell architecture

Inkwell is a single-module Android app (`:app`, Kotlin, Jetpack Compose, minSdk 29,
target 35) optimised for the Samsung Galaxy Tab S11 Ultra and S Pen. Package root:
`com.inkwell.notes`. Everything is local-first: Room database + files under
`filesDir/docs/` (see `core/storage/FileStore.kt`). AI is optional and bring-your-own-key.

## Build & verify

```bash
source /etc/profile.d/android-sdk.sh        # ANDROID_HOME=/opt/android-sdk
./gradlew :app:compileDebugKotlin            # fast compile check
./gradlew :app:testDebugUnitTest             # JVM unit tests (app/src/test)
./gradlew :app:assembleDebug                 # full APK
```

There is no emulator in CI (no KVM). Verify with compilation and JVM unit tests; keep pure
logic (geometry, parsing, naming, serialization, request building) in plain Kotlin classes
so it is unit-testable without Android.

## Layers and contracts

| Package | What | Contract file(s) |
|---|---|---|
| `core/model` | Serializable data model shared by everything | `Documents.kt`, `Ink.kt`, `Tools.kt`, `Settings.kt`, `Ai.kt`, `Media.kt`, `Typed.kt` |
| `core/storage` | Room schema (`db/`), `FileStore`, `PageContentStore` (atomic ProtoBuf), `LibraryRepository` | `LibraryRepository.kt` |
| `core/naming` | `DateNaming` ("YYYYMMDD_Topic"), `AutoNamingService` | `AutoNamingService.kt` |
| `core/settings` | `SettingsRepository` (typed `AppSettings` JSON), `SecretStore` (Keystore AES-GCM) | — |
| `app` | `AppGraph` (service locator), navigation, `AppNavigator` | `AppGraph.kt`, `Navigation.kt` |
| `ink` | `InkCanvasView` (canvas engine), `PageRenderer` | `InkContracts.kt`, `InkCanvasView.kt`, `render/PageRenderer.kt` |
| `editor` | Notebook/PDF editor screen, toolbar, page management, split view | `EditorScreen.kt` |
| `library` | Home library, folders, trash | `LibraryScreen.kt` |
| `ai` | Providers, routing, usage/cost, chat panel, AI settings | `AiService.kt`, `ui/AiUi.kt` |
| `audio` | Recording service, playback, edge-docked recorder, transcripts | `AudioContracts.kt`, `ui/AudioUi.kt`, `RecordingService.kt` |
| `speakers` | Who spoke when, people, voiceprints, naming after a meeting | `SpeakerContracts.kt` |
| `ocr` + `search` | ML Kit OCR + handwriting recognition, FTS index, indexing pipeline, search screen | `ocr/OcrContracts.kt`, `search/SearchScreen.kt` |
| `pdf` | Import, render, text extraction, merge/split/extract, scanning | `PdfService.kt` |
| `export` | Export formats, thumbnails, backup, sync to a folder / Google Drive, storage settings UI | `ExportContracts.kt`, `ui/ExportUi.kt` |
| `whiteboard`, `typed` | Infinite canvas screen, typed document editor | `WhiteboardScreen.kt`, `TypedDocumentScreen.kt` |
| `settings` | Settings screens, app lock | `SettingsScreen.kt`, `AppLock.kt` |
| `ui/theme` | Design tokens (`Inkwell.colors`, `Dimens`, Figtree type) | `Theme.kt` |

Rules for contracts:

1. **Public signatures in contract files are fixed.** Implement them; do not rename or remove.
   Adding new members (with defaults) is allowed when a feature needs it; mention it in your summary.
2. Implementations replace the `SCAFFOLD STUB` classes **in place, keeping the class name and
   constructor** (`XxxImpl(graph: AppGraph)`), so `AppGraph.kt` never needs editing.
3. Get collaborators lazily from the graph (`graph.library`, `graph.ai`, ...). Never construct
   another feature's implementation yourself.
4. `AppDatabase` (entities + DAOs) is shared. You may add queries to the DAO of your own
   feature; adding columns/entities is allowed only if essential. A schema change bumps
   `version` with a non-destructive migration in `db/Migrations.kt` (see `MigrationSchemaTest`).
5. `app/build.gradle.kts` already declares every dependency. Do not add new libraries unless
   impossible otherwise.
6. `AndroidManifest.xml` already declares permissions, the recording service and the
   FileProvider (`${applicationId}.files`, paths in `res/xml/file_paths.xml`).

## UX principles (from the product brief)

- Handwriting first: minimal chrome, content over controls, small icons with ≥44dp targets,
  restrained light/dark themes from `ui/theme/Theme.kt`. No gradients, no emoji, Figtree type.
- The core experience must work offline and without any AI key.
- AI output never silently overwrites notes; insertion is always an explicit user action.
- Long work (OCR, AI, export, sync, transcription) runs off the main thread / in WorkManager
  and never blocks writing.
- API keys live only in `SecretStore`; never log them, never put them in settings JSON,
  exports, or shared files. Google Drive access tokens come from Google Play services on demand
  and are never stored by Inkwell (see `docs/GOOGLE_DRIVE.md`).
- Voiceprints (speaker embeddings, reference clips) are biometric: encrypted, under
  `noBackupFilesDir`, never in the database, backups, exports or sync. Only names travel.

## Speaker identification

Runs after a recording, never live. `SpeakerPrefs.engine` picks the engine:

- **DEVICE** (default): `RecordingEngine` calls `SpeakerService.onRecordingFinished` and
  `speakers/work/DiarizationWorker` runs sherpa-onnx (pyannote segmentation 3.0 + WeSpeaker
  ResNet34-LM embeddings) over windows of up to 5 minutes, checkpointing after each. The two
  models (~32 MB) are downloaded once by `speakers/models/ModelDownloadWorker` from pinned
  Hugging Face revisions and verified by size and SHA-256. Window voices are linked across
  windows by embedding similarity (`ClusterLinker`), then matched to known people
  (`PersonMatcher`, calibrated scores in `VoiceScores`).
- **OPENAI**: `TranscriptionWorker` transcribes each 120 s segment with
  gpt-4o-transcribe-diarize, sending up to four 2-10 s reference clips (voices already heard in
  the recording, then known people from `SpeakerService.referencesFor`) so labels stay
  consistent across segments (`speakers/cloud`).
- **OFF**.

Storage: `recording_speakers` (one row per voice and recording, linked to `people`),
`speaker_turns` (who spoke when) and `transcript_segments.speakerLabel` + `speaker` (display
name, the field exports, search, Google Drive and AI prompts read). `TurnAligner` labels transcript
blocks by time overlap and splits long blocks at sentence boundaries. Naming, confirming,
moving a block, merging voices and renaming people all go through `SpeakerService`, which
rewrites names, re-indexes search and hands the transcript to sync. Voiceprints live in
`speakers/voices/VoiceprintStore` (AES-256-GCM, Keystore key `inkwell.voices.v1`).

## Google Drive

`OutputTarget.DRIVE` copies notes (PDF/Markdown), transcripts (converted to Google Docs) and
audio into `My Drive / Inkwell / <folders> / <title>/`, so they can be read on any computer.
Access is the `drive.file` scope through Google Play services (`PlayServicesDriveAuth`): no token
or password is stored. `DriveClient` speaks Drive v3 over OkHttp; `DriveLayout` finds Inkwell's
folders and files by private app properties, so renames and moves follow the document. The
one-time Google Cloud registration is described in `docs/GOOGLE_DRIVE.md`.

## Threading

- UI on Main. Disk and network on `Dispatchers.IO`. CPU work on `Dispatchers.Default`.
- Background jobs use WorkManager (`androidx.work`) with unique work names per document.
- `AppGraph.appScope` for fire-and-forget work that must outlive a screen.
