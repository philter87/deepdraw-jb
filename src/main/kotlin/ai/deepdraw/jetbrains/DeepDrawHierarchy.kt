package ai.deepdraw.jetbrains

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The drawing's hierarchy, as a native IDE tree.
 *
 * The library draws one of these itself and is told not to (`hideTree`): a
 * second tree inside the canvas would be a panel of the editor competing with
 * the panel the window already has, in the place a reader looks for exactly
 * this. Hiding it also gives the canvas the width back.
 *
 * The window follows whichever drawing is in front, and empties when the file in
 * front is not a drawing at all.
 */
class DeepDrawHierarchyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DeepDrawHierarchyPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class DeepDrawHierarchyPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    /** The editor the window is about, or none when the file in front is not one. */
    private var source: DeepDrawFileEditor? = null

    /** A rebuild moves the selection about; clicks are only the reader's. */
    private var rebuilding = false

    private val selection = TreeSelectionListener {
        if (rebuilding) return@TreeSelectionListener
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@TreeSelectionListener
        val row = node.userObject as? TreeNode ?: return@TreeSelectionListener
        // A click opens a drawing or reveals a shape; which of the two it is, is
        // the webview's decision, since it is the one holding the document.
        source?.navigate(row.id)
    }

    init {
        tree.isRootVisible = true
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = Renderer()
        tree.addTreeSelectionListener(selection)
        tree.emptyText.text = "Open a DeepDraw drawing to see its hierarchy."
        tree.emptyText.appendSecondaryText(
            "New Drawing",
            SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
        ) { NewDrawingAction.create(project) }
        border = JBUI.Borders.empty()
        add(JBScrollPane(tree), BorderLayout.CENTER)

        val bus = project.messageBus.connect(this)
        bus.subscribe(DeepDrawTreeListener.TOPIC, object : DeepDrawTreeListener {
            override fun treeChanged(editor: DeepDrawFileEditor) {
                // An editor that is not the one in front still reports; the
                // window is about the drawing the reader is looking at.
                if (editor === source) render()
            }
        })
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                follow()
            }
        })
        follow()
    }

    private fun follow() {
        source = FileEditorManager.getInstance(project).selectedEditor as? DeepDrawFileEditor
        render()
    }

    private fun render() {
        val snapshot = source?.treeSnapshot
        rebuilding = true
        try {
            root.removeAllChildren()
            root.userObject = null
            val rows = snapshot?.nodes ?: emptyList()
            val top = rows.firstOrNull { it.id == snapshot?.rootId }
            if (top != null) {
                root.userObject = top
                attach(root, top.id, rows)
            }
            model.reload()
            if (top != null) expandToCurrent(rows)
        } finally {
            rebuilding = false
        }
        tree.emptyText.text =
            if (source == null) "Open a DeepDraw drawing to see its hierarchy."
            else "This drawing has nothing in it yet."
    }

    private fun attach(parent: DefaultMutableTreeNode, id: String, rows: List<TreeNode>) {
        for (row in rows) {
            if (row.parentId != id) continue
            val child = DefaultMutableTreeNode(row)
            parent.add(child)
            attach(child, row.id, rows)
        }
    }

    /** The way to the open drawing is unfolded, and the open one is selected. */
    private fun expandToCurrent(rows: List<TreeNode>) {
        val current = rows.firstOrNull { it.isCurrent } ?: return
        val path = pathTo(root, current.id) ?: return
        tree.expandPath(path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun pathTo(node: DefaultMutableTreeNode, id: String): TreePath? {
        if ((node.userObject as? TreeNode)?.id == id) return TreePath(node.path)
        for (i in 0 until node.childCount) {
            val found = pathTo(node.getChildAt(i) as DefaultMutableTreeNode, id)
            if (found != null) return found
        }
        return null
    }

    override fun dispose() {
        tree.removeTreeSelectionListener(selection)
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: javax.swing.JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? TreeNode ?: return
            append(
                node.label,
                if (node.isCurrent) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                else SimpleTextAttributes.REGULAR_ATTRIBUTES,
            )
            if (node.isCurrent) append("  open", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            icon = when {
                node.hasChildren -> AllIcons.Nodes.Folder
                else -> AllIcons.Nodes.EmptyNode
            }
            toolTipText = if (node.hasChildren) "Open this drawing" else "Show this shape"
        }
    }
}
