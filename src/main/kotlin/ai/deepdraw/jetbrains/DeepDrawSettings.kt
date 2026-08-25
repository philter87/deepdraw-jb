package ai.deepdraw.jetbrains

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * The two things about DeepDraw a reader may want to change, and nothing else.
 * Both are application-level: they are about how this person works, not about
 * one project.
 */
@Service(Service.Level.APP)
@State(name = "DeepDrawSettings", storages = [Storage("deepdraw.xml")])
class DeepDrawSettings : PersistentStateComponent<DeepDrawSettings> {

    /**
     * Base URL of the Iconify-compatible API the icon picker searches. Empty
     * works fully offline — the picker then only offers the icons bundled with
     * the library. It is also the only thing in the webview that talks to the
     * network at all.
     */
    @JvmField
    var iconifyApi: String = "https://api.iconify.design"

    /**
     * What `New Drawing` creates: a self-contained `.deepdraw.html` that opens
     * in any browser, or a small `.deepdraw.json` that diffs cleanly in git.
     */
    @JvmField
    var newFileFormat: String = "html"

    override fun getState(): DeepDrawSettings = this

    override fun loadState(state: DeepDrawSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    /** The configured API, or empty when the reader has asked to stay offline. */
    fun iconifyApiOrEmpty(): String = iconifyApi.trim()

    fun defaultFormat(): DeepDrawFormat =
        if (newFileFormat.equals("json", ignoreCase = true)) DeepDrawFormat.JSON else DeepDrawFormat.HTML

    companion object {
        fun getInstance(): DeepDrawSettings =
            ApplicationManager.getApplication().getService(DeepDrawSettings::class.java)
    }
}
