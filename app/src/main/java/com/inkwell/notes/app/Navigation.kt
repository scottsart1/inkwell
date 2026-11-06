package com.inkwell.notes.app

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.editor.EditorScreen
import com.inkwell.notes.editor.SplitScreen
import com.inkwell.notes.library.LibraryScreen
import com.inkwell.notes.library.TrashScreen
import com.inkwell.notes.search.SearchScreen
import com.inkwell.notes.settings.SettingsScreen
import com.inkwell.notes.typed.TypedDocumentScreen
import com.inkwell.notes.whiteboard.WhiteboardScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class SettingsSection(val title: String) {
    GENERAL("General"),
    INPUT("Pen & input"),
    TOOLBAR("Toolbar & view"),
    NAMING("File naming"),
    PAPER("Paper & templates"),
    RECORDING("Recording"),
    SPEAKERS("Voices & speakers"),
    OCR("Recognition & OCR"),
    AI("AI providers"),
    USAGE("AI usage & cost"),
    STORAGE("Storage, sync & backup"),
    PRIVACY("Privacy & lock"),
    ABOUT("About"),
}

/** App-wide navigation used by every screen. */
interface AppNavigator {
    fun openLibrary(folderId: String? = null)
    /** Opens a document in the screen matching its kind. */
    fun openDocument(doc: DocumentMeta, pageIndex: Int? = null)
    fun openDocumentById(docId: String, pageIndex: Int? = null)
    /** Two documents side by side (e.g. a PDF and a notebook). */
    fun openSplit(leftDocId: String, rightDocId: String)
    fun openSearch(query: String = "")
    fun openSettings(section: SettingsSection = SettingsSection.GENERAL)
    fun openTrash()
    fun back()
}

object Routes {
    const val LIBRARY = "library?folder={folder}"
    const val NOTEBOOK = "notebook/{docId}?page={page}"
    const val WHITEBOARD = "whiteboard/{docId}"
    const val TYPED = "typed/{docId}"
    const val SPLIT = "split/{left}/{right}"
    const val SEARCH = "search?q={q}"
    const val SETTINGS = "settings?section={section}"
    const val TRASH = "trash"

    fun library(folderId: String?) = "library?folder=${Uri.encode(folderId ?: "")}"
    fun notebook(docId: String, page: Int?) = "notebook/${Uri.encode(docId)}?page=${page ?: -1}"
    fun whiteboard(docId: String) = "whiteboard/${Uri.encode(docId)}"
    fun typed(docId: String) = "typed/${Uri.encode(docId)}"
    fun split(l: String, r: String) = "split/${Uri.encode(l)}/${Uri.encode(r)}"
    fun search(q: String) = "search?q=${Uri.encode(q)}"
    fun settings(s: SettingsSection) = "settings?section=${s.name}"
}

private class NavControllerNavigator(
    private val nav: NavHostController,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) : AppNavigator {
    override fun openLibrary(folderId: String?) {
        nav.navigate(Routes.library(folderId)) { launchSingleTop = true }
    }

    override fun openDocument(doc: DocumentMeta, pageIndex: Int?) {
        val route = when (doc.kind) {
            DocKind.NOTEBOOK, DocKind.PDF -> Routes.notebook(doc.id, pageIndex)
            DocKind.WHITEBOARD -> Routes.whiteboard(doc.id)
            DocKind.TYPED -> Routes.typed(doc.id)
        }
        nav.navigate(route) { launchSingleTop = true }
    }

    override fun openDocumentById(docId: String, pageIndex: Int?) {
        // Links and notifications can outlive a document's move to the trash: open only live ones.
        scope.launch { graph.library.getDocument(docId)?.takeIf { it.trashedAt == null }?.let { openDocument(it, pageIndex) } }
    }

    override fun openSplit(leftDocId: String, rightDocId: String) {
        nav.navigate(Routes.split(leftDocId, rightDocId)) { launchSingleTop = true }
    }

    override fun openSearch(query: String) {
        nav.navigate(Routes.search(query)) { launchSingleTop = true }
    }

    override fun openSettings(section: SettingsSection) {
        nav.navigate(Routes.settings(section)) { launchSingleTop = true }
    }

    override fun openTrash() {
        nav.navigate(Routes.TRASH) { launchSingleTop = true }
    }

    override fun back() {
        if (nav.previousBackStackEntry != null) {
            nav.popBackStack()
            return
        }
        // Nothing underneath (opened from a notification or a shared file): the library replaces
        // it. The library itself is never popped, which would leave nothing to show.
        if (nav.currentDestination?.route == Routes.LIBRARY) return
        nav.navigate(Routes.library(null)) {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

/**
 * The navigator one screen gets. Only the screen on top may navigate: a screen still fading out
 * (a second tap on its back arrow, a tap during the transition) must not pop or push on behalf of
 * the screen now showing, which can leave that screen visible but paused.
 */
private class EntryNavigator(
    private val base: AppNavigator,
    private val nav: NavHostController,
    private val entry: NavBackStackEntry,
) : AppNavigator {
    private val onTop: Boolean get() = nav.currentBackStackEntry?.id == entry.id

    /** When this screen went back itself; it may still open what replaces it (closing a tab or pane). */
    private var leftAt = 0L

    private val mayOpen: Boolean
        get() = onTop || (leftAt != 0L && android.os.SystemClock.uptimeMillis() - leftAt < REPLACE_WINDOW_MS)

    override fun openLibrary(folderId: String?) { if (mayOpen) base.openLibrary(folderId) }
    override fun openDocument(doc: DocumentMeta, pageIndex: Int?) { if (mayOpen) base.openDocument(doc, pageIndex) }
    override fun openDocumentById(docId: String, pageIndex: Int?) { if (mayOpen) base.openDocumentById(docId, pageIndex) }
    override fun openSplit(leftDocId: String, rightDocId: String) { if (mayOpen) base.openSplit(leftDocId, rightDocId) }
    override fun openSearch(query: String) { if (mayOpen) base.openSearch(query) }
    override fun openSettings(section: SettingsSection) { if (mayOpen) base.openSettings(section) }
    override fun openTrash() { if (mayOpen) base.openTrash() }

    /** Only once and only from the top: a second tap on a fading back arrow does nothing. */
    override fun back() {
        if (!onTop) return
        leftAt = android.os.SystemClock.uptimeMillis()
        base.back()
    }

    private companion object {
        const val REPLACE_WINDOW_MS = 1_000L
    }
}

@Composable
fun InkwellNavHost(graph: AppGraph, pendingNavigation: PendingNavigation) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val navigator: AppNavigator = remember(nav) { NavControllerNavigator(nav, graph, scope) }
    // Attached only while this nav host is composed (the app lock disposes it), so
    // navigation requested while locked waits for the unlocked host instead of being lost.
    DisposableEffect(navigator) {
        pendingNavigation.attach(navigator)
        onDispose { pendingNavigation.detach(navigator) }
    }

    @Composable
    fun navFor(entry: NavBackStackEntry): AppNavigator = remember(entry.id) { EntryNavigator(navigator, nav, entry) }

    // Short cross-fades: while a screen fades out it can still be touched, so the shorter the
    // fade the less room for a tap meant for the screen coming in.
    NavHost(
        navController = nav,
        startDestination = Routes.LIBRARY,
        enterTransition = { fadeIn(tween(TRANSITION_MS)) },
        exitTransition = { fadeOut(tween(TRANSITION_MS)) },
        popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
        popExitTransition = { fadeOut(tween(TRANSITION_MS)) },
    ) {
        composable(Routes.LIBRARY, arguments = listOf(navArgument("folder") { type = NavType.StringType; defaultValue = "" })) {
            LibraryScreen(navFor(it), it.arguments?.getString("folder")?.ifEmpty { null })
        }
        composable(
            Routes.NOTEBOOK,
            arguments = listOf(
                navArgument("docId") { type = NavType.StringType },
                navArgument("page") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) {
            val page = it.arguments?.getInt("page") ?: -1
            EditorScreen(it.arguments?.getString("docId").orEmpty(), page.takeIf { p -> p >= 0 }, navFor(it))
        }
        composable(Routes.WHITEBOARD, arguments = listOf(navArgument("docId") { type = NavType.StringType })) {
            WhiteboardScreen(it.arguments?.getString("docId").orEmpty(), navFor(it))
        }
        composable(Routes.TYPED, arguments = listOf(navArgument("docId") { type = NavType.StringType })) {
            TypedDocumentScreen(it.arguments?.getString("docId").orEmpty(), navFor(it))
        }
        composable(
            Routes.SPLIT,
            arguments = listOf(navArgument("left") { type = NavType.StringType }, navArgument("right") { type = NavType.StringType }),
        ) {
            SplitScreen(it.arguments?.getString("left").orEmpty(), it.arguments?.getString("right").orEmpty(), navFor(it))
        }
        composable(Routes.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
            SearchScreen(it.arguments?.getString("q").orEmpty(), navFor(it))
        }
        composable(Routes.SETTINGS, arguments = listOf(navArgument("section") { type = NavType.StringType; defaultValue = SettingsSection.GENERAL.name })) {
            val s = runCatching { SettingsSection.valueOf(it.arguments?.getString("section").orEmpty()) }.getOrDefault(SettingsSection.GENERAL)
            SettingsScreen(s, navFor(it))
        }
        composable(Routes.TRASH) { TrashScreen(navFor(it)) }
    }
}

/** Navigation requested before the nav host exists (intents, notifications). */
class PendingNavigation {
    private var navigator: AppNavigator? = null
    private var pending: ((AppNavigator) -> Unit)? = null

    fun attach(n: AppNavigator) {
        navigator = n
        pending?.let { pending = null; it(n) }
    }

    fun detach(n: AppNavigator) {
        if (navigator === n) navigator = null
    }

    fun post(action: (AppNavigator) -> Unit) {
        val n = navigator
        if (n != null) action(n) else pending = action
    }
}

private const val TRANSITION_MS = 150
