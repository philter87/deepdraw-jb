package ai.deepdraw.jetbrains

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Binds the drawing editor to the files that are drawings.
 *
 * A file name is the whole of the test, exactly as in the VS Code extension: a
 * `.deepdraw.html` is an HTML file and a `.deepdraw.json` is a JSON file, and
 * declaring a file *type* for either would take them away from the editors that
 * already understand them.
 *
 * [FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR] is what makes clicking a
 * drawing open the drawing: the canvas is the first tab, and the text stays one
 * tab away — which is the VS Code extension's "Reopen in Text Editor", except
 * that it is always there rather than being a command.
 */
class DeepDrawFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = Formats.isDrawing(file.name)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        DeepDrawFileEditor(project, file)

    override fun getEditorTypeId(): String = "deepdraw-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}
