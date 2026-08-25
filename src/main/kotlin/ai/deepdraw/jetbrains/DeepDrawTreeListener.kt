package ai.deepdraw.jetbrains

import com.intellij.util.messages.Topic

/**
 * A drawing has a new hierarchy to show. The tool window follows whichever
 * drawing is in front, so it listens rather than being handed an editor: an
 * editor that is not the selected one still reports, and the window ignores it.
 */
interface DeepDrawTreeListener {
    fun treeChanged(editor: DeepDrawFileEditor)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<DeepDrawTreeListener> =
            Topic.create("DeepDraw hierarchy", DeepDrawTreeListener::class.java)
    }
}
