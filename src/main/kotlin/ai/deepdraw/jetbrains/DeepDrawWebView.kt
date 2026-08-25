package ai.deepdraw.jetbrains

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent

/**
 * The embedded browser running the DeepDraw library, and the conversation with it.
 *
 * This is transport and nothing else: it does not know what a drawing is, only
 * how to carry a message each way and how to match an answer to the question it
 * answers. Requests are numbered because a bridge only has "send" in each
 * direction.
 *
 * **The bridge is injected, then the page is told to start.** The page's own
 * script cannot post anything until `window.__deepdrawPost` exists, and that
 * function is built from a [JBCefJSQuery] which only exists on this side. So the
 * page defines its entry point and waits; when the load finishes, this side
 * writes the bridge onto `window` and calls that entry point. No polling, and no
 * message that had nowhere to go.
 */
class DeepDrawWebView(parent: Disposable) : Disposable {

    private val browser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(false)
        .build()

    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val sequence = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val booted = AtomicBoolean(false)

    /** Unsolicited messages — everything that is not an answer to a request. */
    var onMessage: (JsonObject) -> Unit = {}

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, this)
        Disposer.register(this, browser)
        Disposer.register(this, query)

        query.addHandler { payload ->
            // Runs off the EDT, on a CEF thread. Nothing here touches Swing, so
            // a save blocking on a request cannot deadlock against it.
            runCatching { receive(JsonParser.parseString(payload).asJsonObject) }
                .onFailure { LOG.warn("DeepDraw: unreadable message from the drawing", it) }
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cef: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true) return
                // A reload re-runs this, and the page is a fresh one each time,
                // so the bridge is written again rather than assumed to survive.
                booted.set(true)
                install()
            }
        }, browser.cefBrowser)

        ensureSchemeRegistered()
        browser.loadURL(DeepDrawResources.INDEX)
    }

    /** Whether the page has loaded and can be spoken to. */
    fun isReady(): Boolean = booted.get()

    /** Writes the bridge onto the page, then hands control to its entry point. */
    private fun install() {
        val bridge = "${Protocol.WEBVIEW_TO_HOST} = function(payload) { ${query.inject("payload")} };"
        execute("$bridge if (window.__deepdrawBoot) window.__deepdrawBoot();")
    }

    /** Tells the page something, expecting no answer. */
    fun post(type: String, build: JsonObject.() -> Unit = {}) {
        val message = JsonObject().apply { addProperty("type", type) }.apply(build)
        send(message)
    }

    /**
     * Asks the page something. The future completes with the whole answer, or
     * fails — on a `failed` message, or when the drawing does not answer at all.
     */
    fun request(
        type: String,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
        build: JsonObject.() -> Unit = {},
    ): CompletableFuture<JsonObject> {
        val id = sequence.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        val timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            pending.remove(id)?.completeExceptionally(
                IllegalStateException("the drawing did not answer in time"),
            )
        }, timeoutMs, TimeUnit.MILLISECONDS)
        future.whenComplete { _, _ -> timeout.cancel(false) }

        val message = JsonObject().apply {
            addProperty("type", type)
            addProperty("id", id)
        }.apply(build)
        send(message)
        return future
    }

    private fun send(message: JsonObject) {
        // `toJson` on the serialised message gives a JavaScript string literal
        // with everything in it escaped, which is what makes an apostrophe in a
        // shape's label harmless here.
        execute("${Protocol.HOST_TO_WEBVIEW}(${gson.toJson(gson.toJson(message))});")
    }

    private fun execute(code: String) {
        browser.cefBrowser.executeJavaScript(code, browser.cefBrowser.url ?: DeepDrawResources.INDEX, 0)
    }

    private fun receive(message: JsonObject) {
        val id = message.get("id")?.takeIf { it.isJsonPrimitive }?.asInt
        if (id != null) {
            val future = pending.remove(id)
            if (future != null) {
                val failed = message.get("type")?.asString == Protocol.FAILED
                val why = message.get("message")?.takeIf { failed }?.asString
                if (why != null) future.completeExceptionally(IllegalStateException(why))
                else future.complete(message)
                return
            }
        }
        onMessage(message)
    }

    override fun dispose() {
        for ((id, future) in pending) {
            pending.remove(id)
            future.completeExceptionally(IllegalStateException("the editor was closed"))
        }
    }

    companion object {
        private val LOG = logger<DeepDrawWebView>()

        /** Long enough for a large drawing to serialise, short enough to give up on. */
        private const val REQUEST_TIMEOUT_MS = 15_000L

        private val schemeRegistered = AtomicBoolean(false)

        /** Whether this IDE has a browser to embed at all. */
        fun isSupported(): Boolean = JBCefApp.isSupported()

        /**
         * CEF keeps one registry of these per application, so the factory is
         * registered on the first drawing opened and never again.
         */
        private fun ensureSchemeRegistered() {
            if (!schemeRegistered.compareAndSet(false, true)) return
            // Touching JBCefApp first is what guarantees CEF is initialised;
            // registering into an application that does not exist yet is lost.
            JBCefApp.getInstance()
            CefApp.getInstance().registerSchemeHandlerFactory(
                DeepDrawResources.SCHEME,
                DeepDrawResources.DOMAIN,
                DeepDrawSchemeHandlerFactory(),
            )
        }
    }
}
