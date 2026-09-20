package net.kernelpanicsoft.blockbenchidea.editor

import net.kernelpanicsoft.blockbenchidea.fileTypes.BlockbenchFileType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element

/**
 * Provides the embedded Blockbench editor for files of type [BlockbenchFileType].
 *
 * The embedded editor is the sole editor for `.bbmodel` files.
 */
class BlockbenchFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.fileType == BlockbenchFileType

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        BlockbenchFileEditor(project, file)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "blockbench"
    }
}