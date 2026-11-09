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
   - **Branding**: app name "Inkwell", your email as the support and developer contact. Leave the
     logo and the whole "App domain" section (home page, privacy policy, authorized domains)
     empty: they are only needed for apps that are published or verified.
   - **Audience**: user type **External**, stay in **Testing**, and add the Gmail address your
     tablet uses under **Test users**. Google may ask you to sign in again about once a week:
     tap **Reconnect** in Inkwell's settings.
   - Use a project of its own: branding is per project, so renaming another project's app (a
     payment app, say) would change what its users see.
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

## A work computer that can't sign in to your Google account

If the work computer blocks your personal Google account (Gmail doesn't open there, say), turn on
**Open on any computer without signing in** in Inkwell's Google Drive settings. Inkwell then shares
the Inkwell folder as "anyone with the link can view" and shows the link with Copy and Send
buttons. Send it to yourself through something the work computer can open, bookmark it there,
and the folder, PDFs, transcript Docs and recordings open without any sign-in.

Anyone who has the link can view (not change) everything in the folder, and work computers
usually log the addresses they visit, so it is off by default. Turning it off withdraws the link
straight away. Try signing in at drive.google.com first; you only need the link if that fails.

Some companies block every Google site, signed in or not. Then use **Email to work** (below).

## Email to work (no Google needed on the work computer)

Send a note or a transcript to your own work email address and read it in your work inbox.

1. On the tablet, tap **Email to work**:
   - in the library: a note's three-dot menu;
   - in the editor: the **More** menu;
   - in the recordings panel: a recording's menu (**Email transcript to work**, once it is
     transcribed).
2. The first time, Inkwell asks for your work address and remembers it (change it later in
   **Settings › Storage, sync & backup › Email to work**).
3. Your mail app (Gmail, Samsung Email, Outlook...) opens with the email ready: addressed to you,
   subject filled in. Tap **Send**. Nothing is sent before that.

What arrives:

- **A note**: the recognised text (typed text, handwriting, OCR) and every transcript of the note
  in the email itself, plus the notes as a PDF (handwriting and drawings as you see them) and a
  Word file (text, pages and transcripts) attached.
- **A recording**: its transcript in the email, with times and speakers, plus a Word file.

The text is in the email body so it can be read even where a mail filter removes attachments.
Very long text (over about 100,000 characters) stops with a note that the rest is in the Word
file. Inkwell itself sends nothing over the network for this: the tablet's mail app sends the
email from the account you use there.

## Without Google Drive

- **Email to work**: see above; no Google account is involved on the work computer.
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

"Email to work" is `export/email/`: `WorkEmail` (pure text building, unit-tested),
`WorkEmailSender` (exports the PDF, Word and plain text, builds an `ACTION_SEND` intent with a
`mailto:` selector so only mail apps answer, attachments granted read-only through the
FileProvider) and `WorkEmailUi` (`rememberWorkEmail()`, the address prompt). The address is
`DestinationPrefs.workEmail`.
