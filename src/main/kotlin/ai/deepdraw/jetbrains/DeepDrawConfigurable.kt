package ai.deepdraw.jetbrains

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

/** Settings → Tools → DeepDraw. */
class DeepDrawConfigurable : BoundSearchableConfigurable("DeepDraw", "ai.deepdraw.settings") {

    override fun createPanel(): DialogPanel {
        val settings = DeepDrawSettings.getInstance()
        return panel {
            row("Iconify API:") {
                textField()
                    .bindText(settings::iconifyApi)
                    .columns(40)
            }.rowComment(
                "Base URL of the Iconify-compatible API the icon picker searches. Leave it empty to " +
                    "work fully offline — the picker then only offers the icons bundled with the library. " +
                    "It is the only thing in a drawing that reaches the network.",
            )

            group("New drawing") {
                row {
                    // Two formats, so one checkbox says it: a radio pair would
                    // be the same question asked with more furniture.
                    checkBox("Create self-contained .deepdraw.html")
                        .bindSelected(
                            { settings.newFileFormat.equals("html", ignoreCase = true) },
                            { settings.newFileFormat = if (it) "html" else "json" },
                        )
                }.rowComment(
                    "A <code>.deepdraw.html</code> opens in any browser on its own. Unchecked, " +
                        "<b>New Drawing</b> creates a small <code>.deepdraw.json</code> that diffs cleanly in git. " +
                        "Either one opens in this editor, and Save As between them converts.",
                )
            }
        }
    }
}
