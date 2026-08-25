package ai.deepdraw.jetbrains

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The two files this plugin edits, in both directions.
 *
 * A DeepDraw drawing is one JSON document either way (deepdraw CLAUDE.md §4).
 * The difference between the formats is only what is wrapped around it:
 *
 * - `.deepdraw.json` — the document, and nothing else. Small, and it diffs.
 * - `.deepdraw.html` — the same document inside DeepDraw's standalone page,
 *   with the library inlined, so the file opens in a browser on its own.
 *
 * The HTML side is a string replacement into `template.html`, which the library
 * publishes beside its bundle for exactly this. Rebuilding that page here
 * instead would be a second copy of it in a second repository, and two copies
 * of a page drift in the direction nobody is looking.
 */
enum class DeepDrawFormat { HTML, JSON }

/** A file's two halves: the document, and the chrome that has to survive a save. */
data class DeepDrawFile(
    /** The document, as JSON text. */
    val json: String,
    /** The credit fragment out of an HTML file, ready to be written back. */
    val credit: String,
)

object Formats {
    /** Where the document sits in a standalone page — a tag, not a string literal. */
    private val DOCUMENT_SCRIPT =
        Regex("""<script id="dd-document" type="application/json">([\s\S]*?)</script>""")

    /**
     * The corner's second name, when a generator produced the file. It is chrome
     * and never enters the document, so saving has to carry it across by hand or
     * re-saving a drawing would quietly un-credit whatever drew it.
     */
    private val CREDIT_BLOCK =
        Regex("""<div class="dd-credit">[\s\S]*?deepdraw\.ai</a>([\s\S]*?)</div>""")

    private const val MARK_TITLE = "__DEEPDRAW_TITLE__"
    private const val MARK_DOCUMENT = "__DEEPDRAW_DOCUMENT_JSON__"
    private const val MARK_CREDIT = "__DEEPDRAW_CREDIT__"

    // `serializeNulls` is not a preference: Gson drops null members by default,
    // and the root node's `parentId` is null. Without it, every save quietly
    // deletes a field the document states — which is the whole difference
    // between writing a drawing back and rewriting it slightly wrong.
    private val compact: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private val pretty: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create()

    /** Whether a file name is one of ours, which is what binds the editor to it. */
    fun isDrawing(fileName: String): Boolean =
        Regex("""\.deepdraw\.(html?|json)$""", RegexOption.IGNORE_CASE).containsMatchIn(fileName)

    /** Which of the two a path is. Everything that is not `.html` is read as JSON. */
    fun formatFor(path: String): DeepDrawFormat =
        if (Regex("""\.html?$""", RegexOption.IGNORE_CASE).containsMatchIn(path)) DeepDrawFormat.HTML
        else DeepDrawFormat.JSON

    /** The document out of a file's bytes. Throws with a sentence a person can act on. */
    fun parseFile(text: String, format: DeepDrawFormat): DeepDrawFile {
        if (format == DeepDrawFormat.JSON) {
            // Fail here, with the position, rather than in the webview.
            JsonParser.parseString(text)
            return DeepDrawFile(text, "")
        }
        val match = DOCUMENT_SCRIPT.find(text)
            ?: throw DeepDrawFormatException(
                "This HTML file holds no DeepDraw drawing: there is no <script id=\"dd-document\"> in it.",
            )
        val json = match.groupValues[1]
        JsonParser.parseString(json)
        return DeepDrawFile(json, CREDIT_BLOCK.find(text)?.groupValues?.get(1) ?: "")
    }

    /** The text to write for a document. `template` is only read for HTML. */
    fun serialize(
        json: String,
        format: DeepDrawFormat,
        template: () -> String,
        credit: String = "",
    ): String {
        val doc = JsonParser.parseString(json).asJsonObject
        // Written out, not minified: a file in a repository is read and reviewed
        // by people, and the library fills its own defaults back in either way.
        if (format == DeepDrawFormat.JSON) return pretty.toJson(doc) + "\n"

        val titled = doc.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        return template()
            .replace(MARK_TITLE, escapeHtml(titled ?: "DeepDraw"))
            .replace(MARK_DOCUMENT, embed(doc))
            .replace(MARK_CREDIT, credit)
    }

    /**
     * The document as the `<script>` payload. A literal `<` anywhere in a label
     * or in an icon's inline SVG would close the tag early, so it is written the
     * JSON way — `<` is the same character to a parser and a different one to a
     * browser looking for the end of a script.
     */
    private fun embed(doc: JsonObject): String = compact.toJson(doc).replace("<", "\\u003c")

    private fun escapeHtml(value: String): String = buildString {
        for (c in value) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /**
     * A drawing with nothing in it yet. It needs its root node stated: a document
     * whose `nodes` is empty is not a document DeepDraw will open — the reader
     * treats it as a file that describes no drawing at all.
     */
    fun blankDocument(title: String): String {
        val root = JsonObject().apply {
            addProperty("kind", "shape")
            addProperty("type", "root")
            add("parentId", com.google.gson.JsonNull.INSTANCE)
            addProperty("x", 0)
            addProperty("y", 0)
            addProperty("w", 0)
            addProperty("h", 0)
            addProperty("index", 0)
        }
        val doc = JsonObject().apply {
            addProperty("version", 1)
            addProperty("title", title)
            addProperty("rootId", "root")
            add("nodes", JsonObject().apply { add("root", root) })
        }
        return compact.toJson(doc)
    }

    /**
     * The name a drawing's exports are offered under: the file's own name with
     * every extension taken off, so `MyDrawing.deepdraw.html` exports as
     * `MyDrawing.deepdraw.png` rather than `MyDrawing.deepdraw.deepdraw.png`.
     */
    fun baseName(fileName: String): String = fileName
        .replace(Regex("""\.deepdraw\.(html?|json|png)$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\.(html?|json|png)$""", RegexOption.IGNORE_CASE), "")

    /** The title a new drawing takes from the file it is being saved as. */
    fun titleFrom(fileName: String): String =
        baseName(fileName).ifBlank { "Untitled drawing" }
}

/** A file that is not a drawing, said in a sentence the reader can act on. */
class DeepDrawFormatException(message: String) : RuntimeException(message)
