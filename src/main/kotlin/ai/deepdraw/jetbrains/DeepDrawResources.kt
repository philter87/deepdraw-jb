package ai.deepdraw.jetbrains

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.net.URI
import kotlin.math.min

/**
 * The page the editor shows, served to the embedded browser.
 *
 * The alternative — one giant HTML string handed to `loadHTML` — would mean
 * inlining the library into it on every open, and an origin the browser treats
 * as opaque, which the icon picker's `fetch` then has to talk out of. Serving
 * the same files a web page would be served, over a scheme handler CEF routes
 * before it reaches the network, keeps `deepdraw.js` a separate `<script src>`
 * (so a library upgrade stays a re-fetch, deepdraw CLAUDE.md §3) and gives the
 * page a real origin.
 *
 * Nothing outside the plugin's own resources is reachable through it: the URL's
 * path is resolved against a fixed root and anything that escapes is a 404.
 */
object DeepDrawResources {
    const val SCHEME = "http"
    const val DOMAIN = "deepdraw"
    const val INDEX = "$SCHEME://$DOMAIN/index.html"

    /** Where the page's files live inside the plugin jar. */
    private const val ROOT = "/deepdraw-web"

    private val TYPES = mapOf(
        "html" to "text/html",
        "js" to "text/javascript",
        "css" to "text/css",
        "json" to "application/json",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "woff2" to "font/woff2",
    )

    fun resource(path: String): ByteArray? {
        // `normalize` turns `/lib/../../secrets` into something that no longer
        // starts with the root, which is the whole of the check.
        val clean = URI("file://$ROOT/${path.trimStart('/')}").normalize().path
        if (!clean.startsWith("$ROOT/")) return null
        return DeepDrawResources::class.java.getResourceAsStream(clean)?.use { it.readBytes() }
    }

    /**
     * The standalone page a `.deepdraw.html` is written into, read once. It is
     * the library's own file, published beside its bundle, so that writing a
     * drawing stays a string replacement rather than a second copy of DeepDraw's
     * export page living in this repository.
     */
    fun template(): String = templateText

    private val templateText: String by lazy {
        val bytes = resource("/lib/template.html")
            ?: throw IllegalStateException(
                "template.html is missing from the plugin — the build's fetchDeepDrawLib step did not run.",
            )
        String(bytes, Charsets.UTF_8)
    }

    fun mimeType(path: String): String =
        TYPES[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
}

/** Hands CEF a handler for every `http://deepdraw/...` the page asks for. */
class DeepDrawSchemeHandlerFactory : CefSchemeHandlerFactory {
    override fun create(
        browser: CefBrowser?,
        frame: CefFrame?,
        schemeName: String?,
        request: CefRequest?,
    ): CefResourceHandler = DeepDrawResourceHandler(request?.url)
}

/**
 * One file, read out of the plugin jar and handed to the browser in the chunks
 * it asks for. The whole body is read up front: the largest of them is the
 * library bundle, and holding one copy of it for the length of a request costs
 * less than the machinery for streaming it would.
 */
private class DeepDrawResourceHandler(private val url: String?) : CefResourceHandler {
    private var body: ByteArray = ByteArray(0)
    private var mime = "application/octet-stream"
    private var offset = 0
    private var found = false

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        val path = url?.let { runCatching { URI(it).path }.getOrNull() }?.ifBlank { "/index.html" } ?: "/index.html"
        val bytes = DeepDrawResources.resource(path)
        if (bytes != null) {
            body = bytes
            mime = DeepDrawResources.mimeType(path)
            found = true
        }
        // Answered either way: a 404 the page can report beats a request that
        // hangs until the browser gives up on it.
        callback?.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
        if (response == null) return
        if (!found) {
            response.status = 404
            response.mimeType = "text/plain"
            responseLength?.set(0)
            return
        }
        response.mimeType = mime
        response.status = 200
        // The page and its scripts are the plugin's own files and change only
        // when the plugin does, but a browser that cached them across an
        // upgrade would show the previous version of the editor.
        response.setHeaderByName("Cache-Control", "no-store", true)
        responseLength?.set(body.size)
    }

    override fun readResponse(
        dataOut: ByteArray?,
        bytesToRead: Int,
        bytesRead: IntRef?,
        callback: CefCallback?,
    ): Boolean {
        if (dataOut == null || offset >= body.size) {
            bytesRead?.set(0)
            return false
        }
        val count = min(bytesToRead, body.size - offset)
        System.arraycopy(body, offset, dataOut, 0, count)
        offset += count
        bytesRead?.set(count)
        return true
    }

    override fun cancel() {
        body = ByteArray(0)
        offset = 0
    }
}
