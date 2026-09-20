package net.kernelpanicsoft.blockbenchidea.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import net.kernelpanicsoft.blockbenchidea.editor.BlockbenchVersionManager
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.BorderFactory

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
        val installedScroll = JScrollPane(installedBox).apply {
            preferredSize = JBUI.size(260, 92)
            border = null
        }
        val releaseBox = ComboBox<String>()
        releaseBox.model = DefaultComboBoxModel(arrayOf("Loading releases..."))

        val install = JButton("Install")
        install.addActionListener {
            val selected = releaseBox.selectedItem as? String ?: return@addActionListener
            if (!selected.matches(Regex("v?\\d+\\.\\d+\\.\\d+"))) return@addActionListener
            manager.install(project, selected) {
                installedModel.clear()
                manager.installedVersions().forEach(installedModel::addElement)
                manager.selectedVersion()?.let { installedBox.setSelectedValue(it, true) }
            }
        }
        val delete = JButton("Remove")
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
        val refresh = JButton("Reload releases")
        refresh.addActionListener { loadReleaseTags(manager, releaseBox) }

        val deleteEnabled = {
            delete.isEnabled = installedBox.selectedIndex >= 0
        }
        installedBox.addListSelectionListener { deleteEnabled() }
        deleteEnabled()

        val content = JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(12))).apply {
            border = JBUI.Borders.empty(8, 0)
            add(header("Blockbench runtime"))
            add(JBLabel("<html><body width='520'>Choose which local Blockbench web runtime the embedded editor should use. Installed runtimes are kept in the IDE system directory.</body></html>").apply {
                foreground = com.intellij.ui.JBColor.GRAY
            })
            add(runtimeCard(JBLabel("Installed runtimes"), installedScroll, delete))
            add(runtimeCard(JBLabel("Available releases"), releaseBox, refresh, install))
            add(JBLabel("<html><body width='520'>The selected runtime is used for newly opened model editors. Changes apply when you close and reopen an editor tab.</body></html>").apply {
                foreground = com.intellij.ui.JBColor.GRAY
                border = JBUI.Borders.emptyTop(2)
            })
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).also {
            panel = it
            it.add(content, BorderLayout.NORTH)
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

    private fun header(text: String): JBLabel = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 2f)
        border = JBUI.Borders.emptyBottom(2)
    }

    private fun runtimeCard(vararg components: JComponent): JPanel =
        JPanel(GridBagLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(com.intellij.ui.JBColor.border()),
                JBUI.Borders.empty(10),
            )
            background = null
            val constraints = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 0, 0, JBUI.scale(8))
                weightx = 0.0
            }
            components.forEachIndexed { index, component ->
                constraints.gridx = index
                constraints.weightx = if (component is JScrollPane || component is ComboBox<*>) 1.0 else 0.0
                add(component, constraints)
            }
        }
}
