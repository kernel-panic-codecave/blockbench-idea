package net.kernelpanicsoft.blockbenchidea.actions

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiDirectory
import net.kernelpanicsoft.blockbenchidea.BlockbenchBundle

class NewBlockbenchProjectAction : CreateFileFromTemplateAction(
    BlockbenchBundle.message("action.newProject"),
    BlockbenchBundle.message("action.newProject.description"),
    IconLoader.getIcon("/icons/blockbench.svg", NewBlockbenchProjectAction::class.java),
) {
    override fun buildDialog(
        project: Project,
        directory: PsiDirectory,
        builder: CreateFileFromTemplateDialog.Builder,
    ) {
        builder.setTitle(BlockbenchBundle.message("action.newProject"))
            .addKind(
                BlockbenchBundle.message("editor.fileTypeName"),
                IconLoader.getIcon("/icons/blockbench.svg", NewBlockbenchProjectAction::class.java),
                "Blockbench Model",
            )
    }

    override fun getActionName(
        directory: PsiDirectory,
        newName: String,
        templateName: String,
    ): String = BlockbenchBundle.message("action.newProject")
}
