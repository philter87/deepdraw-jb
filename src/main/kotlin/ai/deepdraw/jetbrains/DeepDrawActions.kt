package ai.deepdraw.jetbrains

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWrapper

/** The drawing in the tab somebody is looking at, if it is one of ours. */
private fun activeDrawing(project: Project?): DeepDrawFileEditor? {
    if (project == null) return null
    return FileEditorManager.getInstance(project).selectedEditor as? DeepDrawFileEditor
}

/**
 * A new drawing is a file first and a canvas second — the editor is file-backed,
 * so there is nowhere to put an unsaved one. The name is asked for once, the
 * `.deepdraw` extension is added if it is missing, and the file opens in the
 * editor already saved.
 */
class NewDrawingAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        create(project, event.getData(CommonDataKeys.VIRTUAL_FILE))
    }

    companion object {
        fun create(project: Project, near: VirtualFile? = null) {
            val folder = when {
                near == null -> project.guessProjectDir()
                near.isDirectory -> near
                else -> near.parent
            } ?: project.guessProjectDir() ?: run {
                Messages.showInfoMessage(
                    project,
                    "Open a folder first — a new drawing is saved as a file in it.",
                    "New DeepDraw Drawing",
                )
                return
            }

            val format = DeepDrawSettings.getInstance().defaultFormat()
            val suffix = if (format == DeepDrawFormat.HTML) "html" else "json"
            val typed = Messages.showInputDialog(
                project,
                "Name for the new drawing",
                "New DeepDraw Drawing",
                null,
                "Untitled.deepdraw.$suffix",
                object : com.intellij.openapi.ui.InputValidator {
                    override fun checkInput(value: String?): Boolean = !value.isNullOrBlank()
                    override fun canClose(value: String?): Boolean = checkInput(value)
                },
            )?.trim().orEmpty()
            if (typed.isEmpty()) return

            val name = if (Formats.isDrawing(typed)) typed else {
                "${typed.replace(Regex("""\.(html?|json)$""", RegexOption.IGNORE_CASE), "")}.deepdraw.$suffix"
            }
            val chosen = Formats.formatFor(name)
            val text = Formats.serialize(
                Formats.blankDocument(Formats.titleFrom(name)),
                chosen,
                DeepDrawResources::template,
            )

            var created: VirtualFile? = null
            WriteCommandAction.runWriteCommandAction(project, "New DeepDraw Drawing", null, {
                val existing = folder.findChild(name)
                if (existing != null) {
                    created = existing
                    return@runWriteCommandAction
                }
                val file = folder.createChildData(this, name)
                VfsUtil.saveText(file, text)
                created = file
            })

            val file = created ?: return
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }
}

/** Writes one of the three export formats beside the drawing. */
sealed class ExportAction(private val kind: String) : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = activeDrawing(event.project) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = activeDrawing(project) ?: return

        // Exports are named `.deepdraw.<ext>`, from the file's own name rather
        // than the drawing's title: in an editor the file is the thing that has
        // a name.
        val name = "${Formats.baseName(editor.file.name)}.deepdraw.$kind"
        // The newer platform deprecates this constructor for one that takes the
        // extension singly, and 2023.3 — which `sinceBuild` promises — has no
        // other. Compiled here it is a call the newer IDEs still answer and only
        // grumble about; written the newer way it would be a `NoSuchMethodError`
        // on the oldest IDE this plugin claims. It changes the day `sinceBuild`
        // does, not before.
        val descriptor = FileSaverDescriptor("Export Drawing", "Where to write $name", kind)
        val chosen: VirtualFileWrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(editor.file.parent, name) ?: return

        var written: String? = null
        var empty = false
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            // Off the EDT: both of these wait on the canvas, which answers on a
            // browser thread.
            val bytes = if (kind == "png") {
                editor.renderPng() ?: run { empty = true; null }
            } else {
                val json = editor.requestJson()
                val format = if (kind == "html") DeepDrawFormat.HTML else DeepDrawFormat.JSON
                Formats.serialize(json, format, DeepDrawResources::template, editor.creditFragment())
                    .toByteArray(Charsets.UTF_8)
            }
            if (bytes != null) {
                chosen.file.writeBytes(bytes)
                written = chosen.file.name
            }
        }, "Exporting $name", true, project)

        VfsUtil.markDirtyAndRefresh(true, false, false, chosen.file)
        ApplicationManager.getApplication().invokeLater {
            if (empty) {
                Messages.showInfoMessage(
                    project,
                    "Nothing is drawn yet, so there is no picture to export.",
                    "Export Drawing",
                )
            } else {
                written?.let {
                    com.intellij.notification.NotificationGroupManager.getInstance()
                        .getNotificationGroup("DeepDraw")
                        .createNotification("Wrote $it", com.intellij.notification.NotificationType.INFORMATION)
                        .notify(project)
                }
            }
        }
    }
}

class ExportHtmlAction : ExportAction("html")

class ExportJsonAction : ExportAction("json")

class ExportPngAction : ExportAction("png")
