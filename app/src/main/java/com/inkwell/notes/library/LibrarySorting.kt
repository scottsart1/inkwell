package com.inkwell.notes.library

import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.LibrarySort
import com.inkwell.notes.core.storage.NaturalOrder as TitleOrder
import java.util.Locale

/** Sort options as shown in the sort menu, and folder ordering that matches document ordering. */
object LibrarySorting {

    /** Menu order. */
    val menuOrder: List<LibrarySort> = listOf(
        LibrarySort.NAME_ASC,
        LibrarySort.NAME_DESC,
        LibrarySort.CREATED_DESC,
        LibrarySort.CREATED_ASC,
        LibrarySort.MODIFIED_DESC,
        LibrarySort.MODIFIED_ASC,
    )

    enum class SortKey { NAME, CREATED, MODIFIED }

    fun keyOf(sort: LibrarySort): SortKey = when (sort) {
        LibrarySort.NAME_ASC, LibrarySort.NAME_DESC -> SortKey.NAME
        LibrarySort.CREATED_ASC, LibrarySort.CREATED_DESC -> SortKey.CREATED
        LibrarySort.MODIFIED_ASC, LibrarySort.MODIFIED_DESC -> SortKey.MODIFIED
    }

    /** True when the first item in the list is the "smallest" (A, oldest). */
    fun isAscending(sort: LibrarySort): Boolean =
        sort == LibrarySort.NAME_ASC || sort == LibrarySort.CREATED_ASC || sort == LibrarySort.MODIFIED_ASC

    /**
     * Folders follow the same sort as documents; names compare naturally ("Term 2" before
     * "Term 10"), ties fall back to the name and then the id so the order is stable.
     */
    fun folderComparator(sort: LibrarySort, locale: Locale = Locale.getDefault()): Comparator<Folder> {
        val natural = NaturalOrder(locale)
        val byName = Comparator<Folder> { a, b -> natural.compare(a.name, b.name) }
        val key: Comparator<Folder> = when (sort) {
            LibrarySort.NAME_ASC -> byName
            LibrarySort.NAME_DESC -> byName.reversed()
            LibrarySort.CREATED_DESC -> compareByDescending<Folder> { it.createdAt }.then(byName)
            LibrarySort.CREATED_ASC -> compareBy<Folder> { it.createdAt }.then(byName)
            LibrarySort.MODIFIED_DESC -> compareByDescending<Folder> { it.modifiedAt }.then(byName)
            LibrarySort.MODIFIED_ASC -> compareBy<Folder> { it.modifiedAt }.then(byName)
        }
        return compareBy<Folder> { it.sortOrder }.then(key).thenBy { it.id }
    }
}

/**
 * Case-insensitive, locale-aware comparison that orders digit runs by value:
 * "Lecture 2" < "Lecture 10", "20261015_A" < "20261016_A". The same order as document titles
 * ([TitleOrder]), so folders and documents on one screen sort alike.
 */
class NaturalOrder(locale: Locale = Locale.getDefault()) : Comparator<String> {
    private val titles = TitleOrder(locale)

    override fun compare(a: String, b: String): Int = titles.compare(a, b)
}
