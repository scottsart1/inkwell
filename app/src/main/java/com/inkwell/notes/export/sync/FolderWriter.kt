package com.inkwell.notes.export.sync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A file or directory in the user's chosen folder (SAF document tree in the app, java.io in tests). */
interface FolderNode {
    val name: String
    val isDirectory: Boolean
    fun children(): List<FolderNode>
    fun createDirectory(name: String): FolderNode?
    /** Creates an empty file; the provider may adjust [name] when it already exists. */
    fun createFile(mimeType: String, name: String): FolderNode?
    fun openOutput(): OutputStream
    fun openInput(): InputStream
    fun delete(): Boolean
    /** False when the provider cannot rename; the node keeps its old name then. */
    fun renameTo(name: String): Boolean
}

/** The chosen folder is no longer accessible because the permission was revoked. */
class FolderRevokedException(cause: Throwable? = null) :
    IOException("Inkwell no longer has access to the sync folder. Choose the folder again in Settings › Storage, sync & backup.", cause)

/** The folder exists in settings but is not reachable right now (SD card removed, provider offline). */
class FolderUnavailableException(cause: Throwable? = null) :
    IOException("The sync folder isn't available right now. Inkwell will try again.", cause)

/** Opens the root of a chosen folder tree. */
fun interface FolderAccess {
    /** @throws FolderRevokedException, FolderUnavailableException */
    fun root(treeUri: String): FolderNode
}

/**
 * Folder operations used by sync. Updates never leave a half-written file under the final
 * name: the new content goes to a temporary file first, then the old file is deleted and the
 * new one takes its name.
 */
object FolderWriter {
    private const val PARTIAL = ".partial"

    fun findChild(dir: FolderNode, name: String): FolderNode? = dir.children().firstOrNull { it.name == name }

    /** Walks/creates [segments] below [root]. */
    fun ensureDirs(root: FolderNode, segments: List<String>): FolderNode {
        var dir = root
        for (segment in segments) {
            val children = dir.children()
            dir = children.firstOrNull { it.isDirectory && it.name == segment }
                ?: dir.createDirectory(segment)
                ?: throw IOException("Couldn't create the folder \"$segment\".")
        }
        return dir
    }

    /** Follows [segments] below [root] without creating anything. */
    fun findDir(root: FolderNode, segments: List<String>): FolderNode? {
        var dir = root
        for (segment in segments) {
            dir = dir.children().firstOrNull { it.isDirectory && it.name == segment } ?: return null
        }
        return dir
    }

    /** Temporary name keeping the real extension so providers infer the right type ("notes.partial.pdf"). */
    fun partialName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name + PARTIAL else name.substring(0, dot) + PARTIAL + name.substring(dot)
    }

    /**
     * Writes [name] in [dir]: new content first (as a temporary file), then the old file is
     * deleted, then the new file is renamed. Falls back to copying when the provider can't rename.
     */
    fun writeFile(dir: FolderNode, name: String, mimeType: String, write: (OutputStream) -> Unit) {
        val tmpName = partialName(name)
        dir.children().filter { it.name == tmpName }.forEach { it.delete() }
        val tmp = dir.createFile(mimeType, tmpName) ?: throw IOException("Couldn't create \"$name\" in the sync folder.")
        try {
            tmp.openOutput().use { write(it) }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        dir.children().filter { it.name == name && !it.isDirectory }.forEach { old ->
            if (!old.delete()) {
                tmp.delete()
                throw IOException("Couldn't replace \"$name\" in the sync folder.")
            }
        }
        if (tmp.renameTo(name) && tmp.name == name) return
        // Provider without rename support (or it picked another name): copy into a fresh file.
        try {
            val final = dir.createFile(mimeType, name) ?: throw IOException("Couldn't create \"$name\" in the sync folder.")
            try {
                tmp.openInput().use { input -> final.openOutput().use { out -> input.copyTo(out) } }
            } catch (e: Throwable) {
                final.delete()
                throw e
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Moves the files this app owns from [oldDir] to [newDir] (rename of a document or a move to
     * another folder): renames the directory in place when only its name changed, otherwise
     * leaves it to the caller to rewrite the files and deletes the old ones. Returns true when the
     * old directory now *is* the new one. A directory with sub-directories is never renamed: a
     * library folder of the same name shares it, and its documents' copies must stay.
     */
    fun renameDirInPlace(oldDir: FolderNode, newName: String, parent: FolderNode): Boolean {
        if (parent.children().any { it.name == newName }) return false
        if (oldDir.children().any { it.isDirectory }) return false
        return oldDir.renameTo(newName) && oldDir.name == newName
    }

    /** Deletes our own files from [dir], then [dir] itself when nothing else is left in it. */
    fun deleteOwnFiles(dir: FolderNode) {
        dir.children().filter { !it.isDirectory && (FolderNaming.isOwnFile(it.name) || it.name.contains(PARTIAL)) }.forEach { it.delete() }
        if (dir.children().isEmpty()) dir.delete()
    }

    /** Creates, writes and deletes a probe file to prove the folder is writable. */
    fun probe(root: FolderNode, now: Long) {
        val name = "inkwell-write-test-$now.txt"
        val file = root.createFile("text/plain", name) ?: throw IOException("Inkwell can't create files in this folder.")
        try {
            file.openOutput().use { it.write("Inkwell write test. Safe to delete.".encodeToByteArray()) }
        } finally {
            if (!file.delete()) throw IOException("Inkwell could write to this folder but couldn't delete its test file.")
        }
    }
}
