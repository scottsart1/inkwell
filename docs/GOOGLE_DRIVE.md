# Reading your notes on another computer (Google Drive)

Inkwell can copy your notes, meeting transcripts and recordings into your Google Drive. On any
computer, including a work laptop, open <https://drive.google.com> in a browser and look in
**My Drive › Inkwell**. Nothing needs to be installed on the laptop.

```
My Drive / Inkwell / <folder> / <document title> / notes.pdf             handwriting, typed text, PDF pages (searchable)
                                                    notes.md              recognised text, for copy and paste
                                                    transcript_<name>     a Google Doc: opens in the browser, prints, exports to Word
                                                    audio_<name>.m4a      the recording (plays in the browser)
```

- The tablet always keeps the original. Copies are updated about 30 seconds after you stop
  writing, and as soon as a transcript or recording is ready.
- Renaming or moving a note in Inkwell renames or moves its Drive folder. Deleting a note in
  Inkwell never deletes its copy in Drive.
- Inkwell asks Google only for the `drive.file` permission: it can see and change the files it
  created, nothing else in your Drive. It never sees your Google password and stores no token.
- Recordings wait for Wi-Fi unless you turn off "Upload audio on Wi-Fi only".

## One-time setup (about 10 minutes, on a computer)

Google only lets an app reach Drive once the app is registered in a Google Cloud project. Inkwell
is installed outside the Play Store, so you register it yourself, once, with the Google account
whose Drive you want to use. It is free.

1. Open <https://console.cloud.google.com> and create a project (any name, for example
   "Inkwell").
2. **APIs & Services › Library**: search for **Google Drive API** and click **Enable**.
3. **Google Auth Platform** (OAuth consent screen):
   - **Branding**: app name "Inkwell", your email as the support and developer contact.
   - **Audience**: user type **External**. Then either click **Publish app** (recommended; the
     `drive.file` permission needs no Google review), or keep it in "Testing" and add your Gmail
     address under **Test users** (Google may then ask you to reconnect every 7 days).
4. **Google Auth Platform › Clients › Create client**, type **Android**:
   - Package name: `com.inkwell.notes`
   - SHA-1 certificate fingerprint: `6D:0E:CF:04:54:6C:F2:60:FA:A9:97:0B:82:43:3B:BE:B7:A6:D9:A9`

   Both values are also shown, with copy buttons, in Inkwell under **Settings › Storage, sync &
   backup › Google Drive › One-time Google setup**. (A debug build has the package name
   `com.inkwell.notes.debug` and needs its own Android client with the same SHA-1.)
5. On the tablet: **Settings › Storage, sync & backup**, tap **Connect Google Drive**, pick your
   account and allow access. Then, under "Where outputs are saved", choose **Google Drive** for
   notes, transcripts and/or audio recordings.

If Google answers "isn't set up" when you connect, one of the values in step 4 doesn't match, or
the Drive API in step 2 isn't enabled yet (it can take a few minutes after enabling).

## Without Google Drive

- **Folder**: copies the same files into a folder on the tablet or an SD card. Any app that syncs
  a folder to a cloud service can pick them up from there.
- **Export / Share**: any note or transcript can be exported (PDF, Word, Markdown, text) from the
  editor and shared to email or another app.

## For developers

`export/sync/DriveClient.kt` talks to Drive v3 over OkHttp (resumable uploads, app properties
to recognise Inkwell's own files); `DriveLayout.kt` maps documents to folders;
`DriveAuth.kt` (`PlayServicesDriveAuth`) gets access tokens from Google Play services
(`AuthorizationClient`). `SyncEngine` treats `OutputTarget.DRIVE` like any destination: queued in
`sync_queue`, retried with backoff, blocked (not failed) while the account needs attention.
The app signing key is `app/inkwell.keystore` (alias `inkwell`); the SHA-1 above is its
certificate.
