package net.kernelpanicsoft.blockbenchidea.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import net.kernelpanicsoft.blockbenchidea.editor.BlockbenchVersionManager
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.JOptionPane

class BlockbenchSettingsConfigurable(private val project: Project) : Configurable {

    private var panel: JBPanel<JBPanel<*>>? = null
    private var installedVersions: JBList<String>? = null

    override fun getDisplayName(): String = "Blockbench"

    override fun createComponent(): JComponent {
        val manager = BlockbenchVersionManager.getInstance()
        val installedModel = DefaultListModel<String>()
        manager.installedVersions().forEach(installedModel::addElement)
        val installedBox = JBList(installedModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            manager.selectedVersion()?.let { setSelectedValue(it, true) }
        }
        installedVersions = installedBox
        val releaseBox = ComboBox<String>()
        releaseBox.model = DefaultComboBoxModel(arrayOf("Loading releases..."))

        val install = JButton("Install selected release")
        install.addActionListener {
            val selected = releaseBox.selectedItem as? String ?: return@addActionListener
            if (!selected.matches(Regex("v?\\d+\\.\\d+\\.\\d+"))) return@addActionListener
            manager.install(project, selected) {
                installedModel.clear()
                manager.installedVersions().forEach(installedModel::addElement)
                manager.selectedVersion()?.let { installedBox.setSelectedValue(it, true) }
            }
        }
        val delete = JButton("Delete selected")
        delete.addActionListener {
            val selected = installedBox.selectedValue ?: return@addActionListener
            val answer = JOptionPane.showConfirmDialog(
                panel ?: installedBox,
                "Delete installed Blockbench $selected?",
                "Delete Blockbench version",
                JOptionPane.YES_NO_OPTION,
            )
            if (answer != JOptionPane.YES_OPTION) return@addActionListener
            manager.delete(selected)
            installedModel.removeElement(selected)
            installedBox.clearSelection()
        }
        val refresh = JButton("Refresh")
        refresh.addActionListener { loadReleaseTags(manager, releaseBox) }

        return JBPanel<JBPanel<*>>(BorderLayout()).also {
            panel = it
            it.add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                add(JBLabel("Installed version:"))
                add(JScrollPane(installedBox).apply { preferredSize = java.awt.Dimension(180, 90) })
                add(delete)
            }, BorderLayout.NORTH)
            it.add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                add(JBLabel("Install release:"))
                add(releaseBox)
                add(refresh)
                add(install)
            }, BorderLayout.CENTER)
            loadReleaseTags(manager, releaseBox)
        }
    }

    override fun isModified(): Boolean =
        installedVersions?.selectedValue != BlockbenchVersionManager.getInstance().selectedVersion()

    override fun apply() {
        BlockbenchVersionManager.getInstance().select(installedVersions?.selectedValue)
    }

    override fun reset() {
        BlockbenchVersionManager.getInstance().selectedVersion()?.let {
            installedVersions?.setSelectedValue(it, true)
        }
    }

    override fun disposeUIResources() {
        panel = null
        installedVersions = null
    }

    private fun loadReleaseTags(
        manager: BlockbenchVersionManager,
        versionBox: ComboBox<String>,
    ) {
        versionBox.isEnabled = false
        manager.fetchReleaseTags(
            project,
            onLoaded = { tags ->
                versionBox.model = DefaultComboBoxModel(tags.toTypedArray())
                manager.selectedVersion()?.let { versionBox.selectedItem = it }
                versionBox.isEnabled = true
            },
            onFailed = {
                versionBox.model = DefaultComboBoxModel(arrayOf("Failed to load releases"))
                versionBox.isEnabled = true
            },
        )
    }
}
