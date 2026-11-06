package com.inkwell.notes.export.backup

import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.Folder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * library.json of a backup: the folder tree, where each document lives, and the user's
 * preferences without anything device-specific or secret (API keys
 * live only in the keystore and are never read here).
 */
@Serializable
data class LibraryManifest(
    val format: String = FORMAT_ID,
    val formatVersion: Int = 1,
    val createdAt: Long = 0,
    val appVersion: String = "",
    val folders: List<BackupFolder> = emptyList(),
    val documents: List<BackupEntry> = emptyList(),
    /** AppSettings as JSON (kept as a tree so newer/older apps can read it leniently). */
    val settings: JsonObject? = null,
) {
    companion object {
        const val FORMAT_ID = "inkwell-backup"
        const val FILE = "library.json"
        const val DOCS_DIR = "documents/"
    }
}

@Serializable
data class BackupFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val color: Int = 0,
    val icon: String = "folder",
    val sortOrder: Int = 0,
    /** Names from the root, e.g. ["Economics", "Term 1"]. */
    val path: List<String> = emptyList(),
)

@Serializable
data class BackupEntry(
    val documentId: String,
    val title: String,
    /** Entry name of the document's package inside the backup. */
    val entry: String,
    /** Folder path from the root; empty = library root. */
    val folderPath: List<String> = emptyList(),
)

object BackupNaming {
    private val NAME = Regex("""^Inkwell-backup-(\d{8})-(\d{4}).*\.zip$""")

    /** "Inkwell-backup-YYYYMMDD-HHmm.zip" in local time. */
    fun fileName(now: Long, locale: Locale = Locale.ROOT): String =
        "Inkwell-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", locale).format(Date(now)) + ".zip"

    fun isBackupName(name: String): Boolean = NAME.matches(name)

    /** "Inkwell-backup-partial-YYYYMMDD-HHmm.zip": a backup still being written, renamed when complete. */
    fun partialFileName(now: Long, locale: Locale = Locale.ROOT): String =
        PARTIAL_PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", locale).format(Date(now)) + ".zip"

    fun isPartialName(name: String): Boolean = name.startsWith(PARTIAL_PREFIX) && name.endsWith(".zip")

    /** Partial backups not written to for [maxAgeMs]: left by a backup whose process was killed. */
    fun <T> stalePartials(files: List<T>, name: (T) -> String, lastModified: (T) -> Long, now: Long, maxAgeMs: Long): List<T> =
        files.filter { isPartialName(name(it)) && lastModified(it) > 0 && now - lastModified(it) > maxAgeMs }

    private const val PARTIAL_PREFIX = "Inkwell-backup-partial-"

    /**
     * Backups to delete so only the [keep] newest remain. Newest is decided by the
     * timestamp in the name, then by last-modified time (several backups in one minute).
     */
    fun <T> toDelete(files: List<T>, name: (T) -> String, lastModified: (T) -> Long, keep: Int = 5): List<T> {
        val backups = files.filter { isBackupName(name(it)) }
        if (backups.size <= keep) return emptyList()
        val sorted = backups.sortedWith(
            compareByDescending<T> { stamp(name(it)) }.thenByDescending { lastModified(it) }.thenByDescending { name(it) },
        )
        return sorted.drop(keep.coerceAtLeast(0))
    }

    private fun stamp(name: String): String = NAME.find(name)?.let { it.groupValues[1] + it.groupValues[2] } ?: ""

    /** Package entry name for a document: readable title plus id so names never collide. */
    fun entryName(title: String, documentId: String): String {
        val base = com.inkwell.notes.export.format.ExportNaming.truncate(com.inkwell.notes.export.format.ExportNaming.sanitize(title), 80)
        return LibraryManifest.DOCS_DIR + base + "__" + PackageManifest.safe(documentId).take(40) + ".inkwell"
    }

    /** Folder paths by id ("Economics" / "Term 1"), robust to cycles and missing parents. */
    fun folderPaths(folders: List<Folder>): Map<String, List<String>> {
        val byId = folders.associateBy { it.id }
        val out = HashMap<String, List<String>>()
        for (f in folders) {
            val names = ArrayList<String>()
            val seen = HashSet<String>()
            var cur: Folder? = f
            while (cur != null && seen.add(cur.id)) {
                names += cur.name
                cur = cur.parentId?.let { byId[it] }
            }
            out[f.id] = names.reversed()
        }
        return out
    }
}

/** Removes what must never leave the device or does not carry over to another one. */
object SettingsSanitizer {
    fun forBackup(s: AppSettings): AppSettings = s.copy(
        destinations = s.destinations.copy(folderUri = null, folderLabel = null, driveAccount = null),
        ai = s.ai.copy(providers = s.ai.providers.map { it.copy(validatedAt = 0) }),
    )

    /** Settings to apply on restore: the backup's preferences, keeping this device's destinations and privacy. */
    fun merge(current: AppSettings, restored: AppSettings): AppSettings = restored.copy(
        destinations = current.destinations,
        privacy = current.privacy,
        onboardingDone = current.onboardingDone || restored.onboardingDone,
        ai = restored.ai.copy(
            providers = restored.ai.providers.map { p ->
                p.copy(validatedAt = current.ai.providers.firstOrNull { it.provider == p.provider }?.validatedAt ?: 0)
            },
        ),
    )
}
