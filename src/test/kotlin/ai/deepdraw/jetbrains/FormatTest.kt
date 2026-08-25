package ai.deepdraw.jetbrains

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The file this plugin writes has to be the file DeepDraw writes: a page that
 * opens in a browser, and one its own reader can import again. That is a string
 * replacement into a published template, which is exactly the kind of thing that
 * breaks without anybody noticing — so it is checked against the real template
 * the build fetched, never against a fixture.
 */
class FormatTest {

    private val template: () -> String = {
        val candidates = listOf(
            File("build/deepdraw-lib/template.html"),
            File("build/resources/main/deepdraw-web/lib/template.html"),
        )
        candidates.firstOrNull { it.isFile }?.readText()
            ?: error("template.html is not built yet — run `gradle fetchDeepDrawLib` first")
    }

    private val drawing = """
        {"version":1,"id":"doc1","title":"Payment <flow>","rootId":"root",
         "nodes":{"root":{"kind":"shape","type":"root","parentId":null},
                  "api":{"kind":"shape","type":"rect","parentId":"root","markdown":"talks to <b>auth</b>"}}}
    """.trimIndent()

    @Test
    fun Should_ReadBackWhatItWrote_When_ADrawingIsSavedAsHtml() {
        val html = Formats.serialize(drawing, DeepDrawFormat.HTML, template)
        val read = Formats.parseFile(html, DeepDrawFormat.HTML)
        assertEquals(JsonParser.parseString(drawing), JsonParser.parseString(read.json))
    }

    @Test
    fun Should_LeaveNoRawAngleBracket_When_TheDocumentHoldsMarkup() {
        val html = Formats.serialize(drawing, DeepDrawFormat.HTML, template)
        val payload = Regex("""<script id="dd-document" type="application/json">([\s\S]*?)</script>""")
            .find(html)!!.groupValues[1]
        // A literal `<` in a label would close the script tag early and break the page.
        assertFalse("a raw < survived into the payload", payload.contains("<"))
        assertEquals(
            "talks to <b>auth</b>",
            JsonParser.parseString(payload).asJsonObject
                .getAsJsonObject("nodes").getAsJsonObject("api").get("markdown").asString,
        )
    }

    @Test
    fun Should_EscapeTheTitle_When_ItIsWrittenIntoTheHead() {
        val html = Formats.serialize(drawing, DeepDrawFormat.HTML, template)
        assertTrue(html.contains("<title>Payment &lt;flow&gt;</title>"))
    }

    @Test
    fun Should_KeepTheCredit_When_AGeneratedFileIsSavedAgain() {
        val credit =
            """ &middot; <a href="https://example.com" target="_blank" rel="noreferrer noopener">a generator</a>"""
        val first = Formats.serialize(drawing, DeepDrawFormat.HTML, template, credit)
        val read = Formats.parseFile(first, DeepDrawFormat.HTML)
        assertEquals(credit, read.credit)
        assertTrue(
            Formats.serialize(read.json, DeepDrawFormat.HTML, template, read.credit).contains("a generator"),
        )
    }

    @Test
    fun Should_LeaveNoMarkBehind_When_ThePageIsWritten() {
        val html = Formats.serialize(drawing, DeepDrawFormat.HTML, template)
        for (mark in listOf("__DEEPDRAW_TITLE__", "__DEEPDRAW_DOCUMENT_JSON__", "__DEEPDRAW_CREDIT__")) {
            assertFalse("$mark was left in the page", html.contains(mark))
        }
    }

    @Test
    fun Should_WriteJsonAPersonCanRead_When_TheFileIsJson() {
        val text = Formats.serialize(drawing, DeepDrawFormat.JSON, template)
        assertTrue("JSON is written out, not minified", text.startsWith("{\n  \"version\": 1"))
        assertEquals(JsonParser.parseString(drawing), JsonParser.parseString(text))
    }

    /**
     * The root node's `parentId` is null, and a JSON writer that drops null
     * members deletes it on every save. Nothing about the page would look wrong;
     * the document would simply be missing a field it states.
     */
    @Test
    fun Should_KeepANullMember_When_TheDrawingIsWrittenBack() {
        for (format in listOf(DeepDrawFormat.HTML, DeepDrawFormat.JSON)) {
            val written = Formats.serialize(drawing, format, template)
            val read = Formats.parseFile(written, format).json
            val root = JsonParser.parseString(read).asJsonObject
                .getAsJsonObject("nodes").getAsJsonObject("root")
            assertTrue("$format dropped parentId", root.has("parentId"))
            assertTrue("$format changed parentId", root.get("parentId").isJsonNull)
        }
    }

    @Test
    fun Should_OpenInDeepDraw_When_TheDocumentIsBlank() {
        val blank = JsonParser.parseString(Formats.blankDocument("Untitled")).asJsonObject
        // An empty `nodes` is not a drawing to the library's reader — it returns
        // null and the canvas comes up empty.
        assertTrue(blank.getAsJsonObject("nodes").size() > 0)
        val page = Formats.serialize(Formats.blankDocument("Untitled"), DeepDrawFormat.HTML, template)
        assertEquals(blank, JsonParser.parseString(Formats.parseFile(page, DeepDrawFormat.HTML).json))
    }

    @Test
    fun Should_RefuseAPageWithNoDrawingInIt() {
        val error = runCatching {
            Formats.parseFile("<!doctype html><p>hello</p>", DeepDrawFormat.HTML)
        }.exceptionOrNull()
        assertTrue(error is DeepDrawFormatException)
        assertTrue(error!!.message!!.contains("no DeepDraw drawing"))
    }

    @Test
    fun Should_NameExportsAfterTheFile_When_TheSuffixIsAlreadyThere() {
        assertEquals("MyDrawing", Formats.baseName("MyDrawing.deepdraw.html"))
        assertEquals("MyDrawing", Formats.baseName("MyDrawing.deepdraw.json"))
        assertEquals("MyDrawing", Formats.baseName("MyDrawing.html"))
        assertEquals("My.Drawing", Formats.baseName("My.Drawing.deepdraw.html"))
    }

    @Test
    fun Should_ReadTheFormatFromTheName() {
        assertEquals(DeepDrawFormat.HTML, Formats.formatFor("/tmp/a.deepdraw.html"))
        assertEquals(DeepDrawFormat.JSON, Formats.formatFor("/tmp/a.deepdraw.json"))
    }

    /** Which files the editor binds itself to, which is the whole of `accept`. */
    @Test
    fun Should_ClaimOnlyDrawings_When_TheEditorIsOfferedAFile() {
        assertTrue(Formats.isDrawing("Checkout.deepdraw.html"))
        assertTrue(Formats.isDrawing("Checkout.deepdraw.json"))
        assertFalse(Formats.isDrawing("Checkout.html"))
        assertFalse(Formats.isDrawing("package.json"))
        assertFalse(Formats.isDrawing("deepdraw.json"))
    }

    /** The page must not be reachable outside the plugin's own resources. */
    @Test
    fun Should_RefuseAPathThatEscapesTheRoot_When_ThePageAsksForAFile() {
        assertTrue(DeepDrawResources.resource("/index.html") != null)
        assertTrue(DeepDrawResources.resource("/../../META-INF/plugin.xml") == null)
        assertTrue(DeepDrawResources.resource("/lib/../../META-INF/plugin.xml") == null)
    }
}
