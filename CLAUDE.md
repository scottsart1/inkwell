# Inkwell — guide for contributors and agents (see docs/ARCHITECTURE.md)

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
| `speakers` | Who spoke when (on-device sherpa-onnx or OpenAI diarize), people, voiceprints, naming | `SpeakerContracts.kt` |
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
   feature; adding columns/entities is allowed only if essential. Installed builds hold user
   data: a schema change bumps `version` and adds a non-destructive `Migration` in
   `db/Migrations.kt` whose SQL matches `app/schemas/` (checked by `MigrationSchemaTest`).
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

## Threading

- UI on Main. Disk and network on `Dispatchers.IO`. CPU work on `Dispatchers.Default`.
- Background jobs use WorkManager (`androidx.work`) with unique work names per document.
- `AppGraph.appScope` for fire-and-forget work that must outlive a screen.
