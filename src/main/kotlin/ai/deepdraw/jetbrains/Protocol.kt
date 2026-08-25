package ai.deepdraw.jetbrains

/**
 * What the plugin and the webview say to each other, and nothing else.
 *
 * Both directions are JSON over the JCEF bridge: the host calls a function on
 * `window`, and the webview answers through a [com.intellij.ui.jcef.JBCefJSQuery].
 * Requests carry an `id` because a bridge only has "send" in each direction —
 * an answer has to say which question it is answering.
 */
object Protocol {
    /** Host → webview. The name of the function the page exposes. */
    const val HOST_TO_WEBVIEW = "window.__deepdrawHostMessage"

    /** Webview → host. The name of the function this side injects for the page. */
    const val WEBVIEW_TO_HOST = "window.__deepdrawPost"

    // Host → webview
    const val INIT = "init"

    /** The file changed underneath the editor (a revert, an edit in the text tab). */
    const val LOAD = "load"

    /** "What does the drawing look like now?" — answered with [CONTENT]. */
    const val GET_CONTENT = "getContent"

    /** A raster of the drawing, which only the webview can produce. */
    const val RENDER = "render"

    /** Somebody clicked a row in the hierarchy tool window. */
    const val NAVIGATE = "navigate"

    /** The IDE's theme changed while the drawing was open. */
    const val THEME = "theme"

    // Webview → host
    const val READY = "ready"

    /** Something was drawn, moved, typed or deleted. Carries no payload: the
     *  host asks for the document when it needs one, which is when it saves. */
    const val EDIT = "edit"

    const val CONTENT = "content"
    const val RENDERED = "rendered"
    const val FAILED = "failed"
    const val ERROR = "error"

    /** The hierarchy, whenever it or the reader's place in it changes. */
    const val TREE = "tree"
}

/**
 * The hierarchy as the tool window needs it: flat, already labelled, and already
 * ordered — the webview walks the document with the library's own helpers, so a
 * row here says what the same row said in the pane it replaces.
 */
data class TreeSnapshot(
    val rootId: String = "",
    val nodes: List<TreeNode> = emptyList(),
)

data class TreeNode(
    val id: String = "",
    val parentId: String? = null,
    val label: String = "",
    /** Whether it has a drawing inside it to open. */
    val hasChildren: Boolean = false,
    /** The drawing on the canvas right now. */
    val isCurrent: Boolean = false,
    val isSelected: Boolean = false,
)
