package ai.deepdraw.jetbrains

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.NavigatableFileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The DeepDraw editor: a browser running the library, and a file underneath it.
 *
 * **There is no reducer on this side.** The library already owns a document and
 * every change to it, so mirroring its actions here would be a second copy of
 * the model kept in step by hand. The webview says only *that* something
 * changed; this side asks what the drawing now is and writes that into the
 * IDE's [Document].
 *
 * **The `Document` is the store.** That is the one real departure from the VS
 * Code extension, and it is not a choice: in IntelliJ, unsaved state, Ctrl+S,
 * Local History, VCS and "the file changed on disk" all hang off `Document`. An
 * editor that kept the drawing beside it instead would have to reimplement
 * every one of them.
 *
 * **Undo, redo and history stay inside the drawing.** They are already
 * implemented there, per-user and per-action. So the writes this side makes are
 * undo-*transparent*: they leave no entry on the IDE's undo stack, and Ctrl+Z
 * with the canvas focused is the library's undo, reaching it through the
 * browser. Two undo stacks over one document would otherwise fight.
 */
class DeepDrawFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor, NavigatableFileEditor {

    private val panel = JPanel(BorderLayout())
    private val changes = PropertyChangeSupport(this)
    private val gson = Gson()
    private val format = Formats.formatFor(file.name)

    private val document: Document? = FileDocumentManager.getInstance().getDocument(file)

    /** The corner credit of an HTML file, carried across saves. */
    private var credit: String = ""

    /** The text this editor last wrote, so an edit from elsewhere is recognisable. */
    private var lastWritten: String? = null

    /** The document as the webview last reported it — what a save falls back to. */
    private var lastKnownJson: String = ""

    /** Edits the webview has reported but this side has not pulled across yet. */
    private val unflushed = AtomicBoolean(false)

    private var webView: DeepDrawWebView? = null
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** The hierarchy as the webview last drew it, for the tool window to render. */
    @Volatile
    var treeSnapshot: TreeSnapshot? = null
        private set

    init {
        val failure = start()
        if (failure != null) {
            panel.add(
                JBLabel("<html><body style='width:420px'>$failure</body></html>")
                    .apply { border = JBUI.Borders.empty(24) },
                BorderLayout.CENTER,
            )
        }
    }

    /** Brings the drawing up, or returns the sentence explaining why it cannot. */
    private fun start(): String? {
        if (!DeepDrawWebView.isSupported()) {
            return "DeepDraw needs the IDE's embedded browser (JCEF), which this IDE was started without. " +
                "Choose a JetBrains Runtime with JCEF in <b>Help → Find Action → Choose Boot Java Runtime for the IDE</b>, then restart."
        }
        val doc = document
            ?: return "This file has no text content the IDE can edit, so the drawing in it cannot be saved."

        val parsed = try {
            Formats.parseFile(doc.text, format)
        } catch (e: Exception) {
            return "This file is not a DeepDraw drawing: ${e.message}"
        }
        credit = parsed.credit
        lastKnownJson = parsed.json
        lastWritten = doc.text

        val view = DeepDrawWebView(this)
        view.onMessage = ::receive
        webView = view
        panel.add(view.component, BorderLayout.CENTER)

        // A save has to write what is on the canvas, not what was on it a second
        // ago. `beforeDocumentSaving` is where the IDE lets an editor put that
        // right — the same hook that strips trailing whitespace on save.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(saving: Document) {
                    if (saving === doc) flushNow()
                }
            })

        // The canvas is white in either theme, but the panes around it are chrome
        // and should look like the rest of the window — and the reader can change
        // the theme with the drawing open.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener {
                webView?.post(Protocol.THEME) { addProperty("theme", currentTheme()) }
            })

        return null
    }

    // --- the conversation with the drawing ----------------------------------

    private fun receive(message: JsonObject) {
        when (message.get("type")?.asString) {
            Protocol.READY -> webView?.post(Protocol.INIT) {
                addProperty("json", lastKnownJson)
                addProperty("iconifyApi", DeepDrawSettings.getInstance().iconifyApiOrEmpty())
                addProperty("theme", currentTheme())
            }

            // Everything a save needs to know: something is different now. The
            // document itself is pulled across once the drawing goes quiet — a
            // drag reports an edit per frame, and each one would otherwise
            // rewrite the whole file.
            Protocol.EDIT -> {
                unflushed.set(true)
                scheduleRefresh()
            }

            Protocol.TREE -> {
                treeSnapshot = gson.fromJson(message.get("tree"), TreeSnapshot::class.java)
                project.messageBus.syncPublisher(DeepDrawTreeListener.TOPIC).treeChanged(this)
            }

            Protocol.ERROR -> LOG.warn("DeepDraw: ${message.get("message")?.asString}")
        }
    }

    private fun scheduleRefresh() {
        alarm.cancelAllRequests()
        alarm.addRequest({ pull() }, QUIET_MS)
    }

    /** Asks the drawing what it now is, and writes that into the document. */
    private fun pull() {
        val view = webView ?: return
        if (!view.isReady()) return
        view.request(Protocol.GET_CONTENT)
            .thenAccept { answer -> apply(answer.get("json").asString) }
            .exceptionally { error ->
                // Of no interest on its own: the next edit schedules another
                // pull, and a save does one it waits for.
                LOG.debug("DeepDraw: the drawing did not hand over its document", error)
                null
            }
    }

    /**
     * Pulls the document across and waits for it, because the file is about to
     * be written. Bounded, and it keeps the last known document if the drawing
     * does not answer: a save that cannot reach the canvas should write the
     * drawing as it was last known, never an empty file.
     */
    private fun flushNow() {
        if (!unflushed.get()) return
        val view = webView ?: return
        if (!view.isReady()) return
        alarm.cancelAllRequests()
        try {
            val answer = view.request(Protocol.GET_CONTENT).get(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            apply(answer.get("json").asString)
        } catch (e: Exception) {
            LOG.warn("DeepDraw: saving the drawing as last known — the canvas did not answer", e)
        }
    }

    /** Writes a document into the file's text, in that file's own format. */
    private fun apply(json: String) {
        lastKnownJson = json
        val doc = document ?: return
        val text = try {
            Formats.serialize(json, format, DeepDrawResources::template, credit)
        } catch (e: Exception) {
            LOG.warn("DeepDraw: the drawing could not be written as ${format.name.lowercase()}", e)
            return
        }
        write(doc, text)
    }

    /**
     * The comparison and the write are one task on the EDT: a `Document` is not
     * to be read off it without a read action, and doing both in one place means
     * the text cannot move between the two.
     *
     * **Never `invokeAndWait`.** A pull answers on a browser thread, and a save
     * is on the EDT waiting for a browser thread — a background write that
     * blocked for the EDT would hold the browser up until that wait timed out.
     */
    private fun write(doc: Document, text: String) {
        val task = Runnable {
            if (text == doc.text) {
                unflushed.set(false)
                return@Runnable
            }
            // Undo-transparent: the drawing owns undo, and an entry here would
            // put a second stack over the same document.
            CommandProcessor.getInstance().runUndoTransparentAction {
                ApplicationManager.getApplication().runWriteAction {
                    doc.setText(text)
                    lastWritten = text
                    unflushed.set(false)
                }
            }
        }
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) task.run() else app.invokeLater(task)
    }

    // --- what the tool window and the actions need --------------------------

    /** A row in the hierarchy tool window was clicked. */
    fun navigate(nodeId: String) {
        webView?.post(Protocol.NAVIGATE) { addProperty("nodeId", nodeId) }
    }

    /**
     * The drawing as it stands, for an export. It does not write the document:
     * exporting a drawing is not editing it, and a "Save as PNG" that quietly
     * dirtied the file would be a surprise. Call it off the EDT — it waits on
     * the canvas, which answers on a browser thread.
     */
    fun requestJson(): String {
        val view = webView ?: return lastKnownJson
        if (!view.isReady()) return lastKnownJson
        return try {
            view.request(Protocol.GET_CONTENT).get(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .get("json").asString
        } catch (e: Exception) {
            LOG.warn("DeepDraw: exporting the drawing as last known — the canvas did not answer", e)
            lastKnownJson
        }
    }

    /**
     * A raster of the drawing on the canvas, which only the webview can produce.
     * Null when there is nothing drawn yet. Call it off the EDT.
     */
    fun renderPng(): ByteArray? {
        val view = webView ?: return null
        val answer = view.request(Protocol.RENDER, RENDER_TIMEOUT_MS) { addProperty("kind", "png") }
            .get(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val base64 = answer.get("base64")?.takeIf { !it.isJsonNull }?.asString ?: return null
        return java.util.Base64.getDecoder().decode(base64)
    }

    fun creditFragment(): String = credit

    fun drawingFormat(): DeepDrawFormat = format

    // --- FileEditor ---------------------------------------------------------

    override fun getComponent(): JComponent = panel

    /** The canvas, so that typing goes to the drawing rather than to the panel. */
    override fun getPreferredFocusedComponent(): JComponent? = webView?.component ?: panel

    override fun getName(): String = "Drawing"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {
        // The reader's place in the drawing lives in the document's own state,
        // not in anything the IDE would restore.
    }

    /**
     * The document carries the unsaved state, so this reports only the moment
     * between an edit on the canvas and the pull that writes it — which is what
     * keeps the tab's marker honest while the drawing is still moving.
     */
    override fun isModified(): Boolean = unflushed.get()

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        changes.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        changes.removePropertyChangeListener(listener)
    }

    override fun getCurrentLocation(): FileEditorLocation? = null

    /**
     * Being first among the file's editors puts the canvas in the first tab; it
     * does not make it the tab that opens. Clicking a file in the project view —
     * like Go to File, and like anything else that reaches a file through a
     * [Navigatable] — opens it and then hands the file to the first editor that
     * can *navigate*, which selects that editor. An editor that cannot navigate
     * is passed over, and the drawing would open showing its own JSON.
     *
     * So this editor can be navigated to, for the one destination it has: the
     * file itself. A [Navigatable] carrying a real position is left to the text
     * editor — a stack trace, a Find in Files hit and Go to Line all mean a
     * line, and a canvas has no lines to show them at.
     */
    override fun canNavigateTo(navigatable: Navigatable): Boolean =
        navigatable is OpenFileDescriptor &&
            navigatable.file == file &&
            navigatable.line <= 0 &&
            navigatable.offset <= 0

    /** Selecting this editor is the whole of the journey; there is nowhere further to go. */
    override fun navigateTo(navigatable: Navigatable) = Unit

    /**
     * The drawing comes forward. If the text changed while it was away — the
     * other tab, a revert, a branch switch — the canvas is reloaded from it.
     */
    override fun selectNotify() {
        val doc = document ?: return
        val view = webView ?: return
        if (doc.text == lastWritten) return
        val parsed = try {
            Formats.parseFile(doc.text, format)
        } catch (e: Exception) {
            LOG.warn("DeepDraw: the file changed into something that is not a drawing", e)
            return
        }
        credit = parsed.credit
        lastKnownJson = parsed.json
        lastWritten = doc.text
        unflushed.set(false)
        view.post(Protocol.LOAD) { addProperty("json", parsed.json) }
    }

    /** The drawing goes away. Whatever it holds is written down before it does. */
    override fun deselectNotify() {
        flushNow()
    }

    /**
     * The canvas is about to be torn down, and with it the only live copy of
     * anything drawn since the last pull. The browser and the alarm are children
     * of this editor, so the platform disposes them straight after.
     */
    override fun dispose() {
        flushNow()
        webView = null
    }

    companion object {
        private val LOG = logger<DeepDrawFileEditor>()

        /** How still the drawing has to be before its document is pulled across. */
        private const val QUIET_MS = 1_000

        private const val FLUSH_TIMEOUT_MS = 4_000L
        private const val RENDER_TIMEOUT_MS = 30_000L

        fun currentTheme(): String = if (JBColor.isBright()) "light" else "dark"
    }
}
