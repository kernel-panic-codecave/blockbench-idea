package net.kernelpanicsoft.blockbenchidea.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import net.kernelpanicsoft.blockbenchidea.BlockbenchBundle
import org.cef.browser.CefBrowser
import org.cef.CefSettings
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefDownloadHandlerAdapter
import java.awt.BorderLayout
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * A [FileEditor] that embeds the Blockbench web app in a JCEF browser and
 * keeps the associated model file in sync:
 *
 *  - on load, the file content is pushed into Blockbench through the bridge,
 *  - the IDE's native Save action asks Blockbench to serialize the current
 *    project with the project's own codec and writes it back to [file],
 *  - the unsaved-modified state is reported back periodically so the editor
 *    tab shows a proper modified indicator.
 */
class BlockbenchFileEditor internal constructor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val logger = Logger.getInstance(BlockbenchFileEditor::class.java)

    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadingStateChange(
            cefBrowser: CefBrowser?,
            isLoading: Boolean,
            canGoBack: Boolean,
            canGoForward: Boolean,
        ) {
            if (isLoading) {
                bridgeInjected = false
                pageReady = false
            } else {
                onPageLoaded(cefBrowser?.url.orEmpty())
            }
        }

        override fun onLoadError(
            cefBrowser: CefBrowser?,
            frame: org.cef.browser.CefFrame?,
            errorCode: org.cef.handler.CefLoadHandler.ErrorCode?,
            errorText: String?,
            failedUrl: String?,
        ) {
            logger.warn("Blockbench page load failed ($errorCode): $errorText [$failedUrl]")
        }
    }

    private val displayHandler = object : CefDisplayHandlerAdapter() {
        override fun onConsoleMessage(
            cefBrowser: CefBrowser?,
            level: CefSettings.LogSeverity?,
            message: String?,
            source: String?,
            line: Int,
        ): Boolean {
            logger.warn("Blockbench console [$source:$line]: $message")
            return false
        }
    }

    private val downloadHandler = object : CefDownloadHandlerAdapter() {
        override fun onBeforeDownload(
            cefBrowser: CefBrowser?,
            downloadItem: CefDownloadItem?,
            suggestedName: String?,
            callback: CefBeforeDownloadCallback?,
        ) {
            val safeName = suggestedName
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.takeIf { it.isNotBlank() }
                ?: "blockbench-download"
            val parent = file.parent
            val target = parent?.toNioPath()?.resolve(safeName)?.normalize()
            if (target == null || !target.startsWith(parent.toNioPath())) {
                logger.warn("Rejected Blockbench download with invalid filename: $suggestedName")
                return
            }
            runCatching {
                callback?.Continue(target.toString(), true)
            }.onFailure {
                logger.warn("Failed to start Blockbench download: $safeName", it)
            }
        }

        override fun onDownloadUpdated(
            cefBrowser: CefBrowser?,
            downloadItem: CefDownloadItem?,
            callback: CefDownloadItemCallback?,
        ) {
            if (downloadItem?.isComplete != true) return
            val downloadedPath = downloadItem.fullPath ?: return
            ApplicationManager.getApplication().invokeLater {
                VirtualFileManager.getInstance().refreshAndFindFileByUrl(
                    VfsUtilCore.pathToUrl(downloadedPath),
                )
            }
        }
    }

    @Volatile
    private var disposed = false

    @Volatile
    private var modified = false

    @Volatile
    private var bridgeInjected = false
    private var pageReady = false
    private var pendingNewProject: Pair<String, String>? = null

    @Volatile
    private var saveInProgress = false

    private val propertyChangeListeners = ConcurrentHashMap.newKeySet<PropertyChangeListener>()

    private val saveActionListener = object : AnActionListener {
        override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
            val actionId = ActionManager.getInstance().getId(action)
            if (actionId !in NATIVE_SAVE_ACTIONS) return
            if (FileEditorManager.getInstance(project).selectedEditor !== this@BlockbenchFileEditor) return
            requestBlockbenchSave()
        }
    }

    @Volatile
    private var browser: JBCefBrowser? = null
    @Volatile
    private var bridgeQuery: JBCefJSQuery? = null
    private val component: JComponent

    init {
        val loading = buildLoadingPanel()
        val editorBackground = UIManager.getColor("Panel.background") ?: java.awt.Color(30, 33, 39)
        loading.background = editorBackground
        val loadingContainer = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = editorBackground
            add(loading, BorderLayout.CENTER)
        }
        component = loadingContainer
        if (!JBCefApp.isSupported()) {
            loadingContainer.removeAll()
            loadingContainer.add(buildUnsupportedPanel(), BorderLayout.CENTER)
        } else {
            ApplicationManager.getApplication().executeOnPooledThread {
                val blockbenchUrl = BlockbenchRuntime.resolveUrl()
                ApplicationManager.getApplication().invokeLater {
                    if (disposed) return@invokeLater
                    if (blockbenchUrl == null) {
                        loadingContainer.removeAll()
                        loadingContainer.add(buildMissingRuntimePanel(), BorderLayout.CENTER)
                        loadingContainer.revalidate()
                        loadingContainer.repaint()
                    } else {
                        initializeBrowser(blockbenchUrl, loadingContainer)
                    }
                }
            }
        }
    }

    private fun initializeBrowser(blockbenchUrl: String, loadingContainer: JComponent) {
        val created = JBCefBrowser.createBuilder()
                .setCreateImmediately(false)
                .build()
            Disposer.register(this, created)
            val query = createBridge(created)
            created.jbCefClient.addLoadHandler(loadHandler, created.cefBrowser)
            created.jbCefClient.addDisplayHandler(displayHandler, created.cefBrowser)
            created.jbCefClient.addDownloadHandler(downloadHandler, created.cefBrowser)
            created.setPageBackgroundColor(
                colorHex(UIManager.getColor("Panel.background") ?: java.awt.Color(30, 33, 39)),
            )
            browser = created
            bridgeQuery = query
            created.component.isOpaque = true
            created.component.background = loadingContainer.background
            ApplicationManager.getApplication().messageBus
                .connect(this)
                .subscribe(AnActionListener.TOPIC, saveActionListener)
            created.createImmediately()
        created.loadURL(blockbenchUrl)
    }

    private fun createBridge(browser: JBCefBrowserBase): JBCefJSQuery {
        val query = JBCefJSQuery.create(browser)
        query.addHandler { message ->
            handleBridgeMessage(message)
            null
        }
        return query
    }

    // ------------------------------------------------------------------ FileEditor

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent? = browser?.component

    override fun getName(): String = EDITOR_NAME

    override fun getFile(): VirtualFile = file

    override fun getFilesToRefresh(): List<VirtualFile> = listOf(file)

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun setState(state: FileEditorState) {
    }

    override fun isModified(): Boolean = modified

    override fun isValid(): Boolean = !disposed && file.isValid && file.exists()

    override fun selectNotify() {
        browser?.cefBrowser?.setFocus(true)
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeListeners.add(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeListeners.remove(listener)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        Disposer.dispose(this)
    }

    internal fun prepareNewProject(name: String, path: String) {
        if (disposed) return
        pendingNewProject = name to path
        if (!pageReady) return
        val current = browser?.cefBrowser ?: return
        current.executeJavaScript(
            "window.__bbIdeaPrepareNewProject(${jsStringLiteral(name)}," +
                "${jsStringLiteral(path)});",
            current.url,
            0,
        )
    }

    // ------------------------------------------------------------------ Bridge handling

    private fun handleBridgeMessage(raw: String) {
        if (disposed) return
        when {
            raw.startsWith(MESSAGE_READY) -> onPageReady()
            raw.startsWith(MESSAGE_PROJECT_LOADED) -> showLoadedBrowser()
            raw.startsWith(MESSAGE_SAVE) -> onSaveRequested(raw.substring(MESSAGE_SAVE.length))
            raw.startsWith(MESSAGE_MODIFIED) ->
                onModifiedReported(raw.substring(MESSAGE_MODIFIED.length) == "1")
            raw.startsWith(MESSAGE_SETTINGS) ->
                BlockbenchVersionManager.getInstance().syncWebSettings(raw.substring(MESSAGE_SETTINGS.length))
            raw.startsWith(MESSAGE_PLUGIN) -> {
                val payload = raw.substring(MESSAGE_PLUGIN.length)
                val separator = payload.indexOf('\n')
                if (separator > 0) {
                    BlockbenchVersionManager.getInstance().syncWebPlugin(
                        payload.substring(0, separator),
                        payload.substring(separator + 1),
                    )
                }
            }
            raw.startsWith(MESSAGE_EXPORT) -> onBlockbenchExport(raw.substring(MESSAGE_EXPORT.length))
            raw.startsWith(MESSAGE_PICKER) -> onFilePickerRequest(raw.substring(MESSAGE_PICKER.length))
            raw.startsWith(MESSAGE_ERROR) ->
                logger.warn("Blockbench page reported an error: ${raw.substring(MESSAGE_ERROR.length)}")
            else -> logger.debug("Unknown bridge message: ${raw.take(120)}")
        }
    }

    private fun onPageLoaded(url: String) {
        if (disposed || bridgeInjected) return
        if (!isConfiguredBlockbenchPage(url)) return
        bridgeInjected = true
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            injectBridgeScript()
        }
    }

    private fun injectBridgeScript() {
        val query = bridgeQuery ?: return
        val injected = query.inject("msg", "function(response){}", "function(errorCode, errorMessage){}")
        val script = BridgeScript.TEMPLATE.replace(BRIDGE_INJECT_MARKER, injected)
        val current = browser?.cefBrowser ?: return
        current.executeJavaScript(script, current.url, 0)
    }

    private fun onPageReady() {
        pageReady = true
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            val current = browser?.cefBrowser ?: return@invokeLater
            val content = readFileContent() ?: return@invokeLater
            if (content.contains("\"__bbIdeaNewProject\"")) {
                current.executeJavaScript(
                    "window.__bbIdeaPrepareNewProject(${jsStringLiteral(file.nameWithoutExtension)}," +
                        "${jsStringLiteral(file.path)});",
                    current.url,
                    0,
                )
                val colorJs = "window.__bbIdeaApplyIdeColors(" + ideColors() + ");"
                runCatching { current.executeJavaScript(colorJs, current.url, 0) }
                showLoadedBrowser()
                return@invokeLater
            }
            val js = "window.__bbIdeaSetModel(" +
                jsStringLiteral(file.name) + ", " +
                jsStringLiteral(content) + ", " +
                jsStringLiteral(file.path) + ");"
            runCatching { current.executeJavaScript(js, current.url, 0) }
                .onFailure { logger.warn("Failed to push model into Blockbench", it) }
            val colorJs = "window.__bbIdeaApplyIdeColors(" + ideColors() + ");"
            runCatching { current.executeJavaScript(colorJs, current.url, 0) }
                .onFailure { logger.warn("Failed to apply IntelliJ color scheme to Blockbench", it) }
            pendingNewProject?.let { (name, path) ->
                pendingNewProject = null
                current.executeJavaScript(
                    "window.__bbIdeaPrepareNewProject(${jsStringLiteral(name)}," +
                        "${jsStringLiteral(path)});",
                    current.url,
                    0,
                )
            }
        }

    }

    private fun showLoadedBrowser() {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) {
                val container = component as? java.awt.Container ?: return@invokeLater
                container.removeAll()
                browser?.component?.isVisible = true
                browser?.component?.let { container.add(it, BorderLayout.CENTER) }
                container.revalidate()
                container.repaint()
                browser?.cefBrowser?.setFocus(true)
            }
        }
    }

    private fun buildLoadingPanel(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = UIManager.getColor("Panel.background") ?: java.awt.Color(30, 33, 39)
        add(
            JBLabel("Loading Blockbench project...", JBLabel.CENTER).apply {
                foreground = UIManager.getColor("Label.foreground")
            },
            BorderLayout.CENTER,
        )
    }

    private fun ideColors(): String {
        val background = uiColor("Panel.background", "window") ?: java.awt.Color(30, 33, 39)
        val foreground = uiColor("Panel.foreground", "Label.foreground") ?: java.awt.Color(202, 202, 212)
        val selected = uiColor("List.selectionBackground", "Table.selectionBackground") ?: background
        val accent = visibleColor(
            background,
            uiColor("Component.accentColor", "Link.foreground") ?: selected,
        )
        val values = linkedMapOf(
            "ui" to background,
            "elevated" to (uiColor("Panel.background", "window") ?: background).brighter(),
            "bright_ui" to foreground,
            "accent" to accent,
            "text" to foreground,
            "light" to foreground,
            "accent_text" to background,
            "bright_ui_text" to background,
            "subtle_text" to foreground.darker(),
        )
        val palette = values.entries.joinToString(prefix = "{", postfix = "}") { (key, color) ->
            jsStringLiteral(key) + ":" + jsStringLiteral(color?.let(::colorHex) ?: "")
        }
        return palette
    }

    private fun uiColor(vararg keys: String): java.awt.Color? =
        keys.firstNotNullOfOrNull(UIManager::getColor)

    private fun isLightScheme(color: java.awt.Color): Boolean =
        (0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue) / 255.0 > 0.5

    private fun visibleColor(
        background: java.awt.Color,
        preferred: java.awt.Color,
        minimumContrast: Double = 1.5,
    ): java.awt.Color {
        if (contrastRatio(background, preferred) >= minimumContrast) return preferred

        val target = if (isLightScheme(background)) java.awt.Color.BLACK else java.awt.Color.WHITE
        var candidate = preferred
        repeat(20) {
            candidate = blend(candidate, target, 0.12)
            if (contrastRatio(background, candidate) >= minimumContrast) return candidate
        }
        return target
    }

    private fun blend(
        color: java.awt.Color,
        target: java.awt.Color,
        amount: Double,
    ): java.awt.Color = java.awt.Color(
        (color.red + (target.red - color.red) * amount).toInt().coerceIn(0, 255),
        (color.green + (target.green - color.green) * amount).toInt().coerceIn(0, 255),
        (color.blue + (target.blue - color.blue) * amount).toInt().coerceIn(0, 255),
    )

    private fun contrastRatio(first: java.awt.Color, second: java.awt.Color): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(color: java.awt.Color): Double =
        listOf(color.red, color.green, color.blue).map { channel ->
            val normalized = channel / 255.0
            if (normalized <= 0.03928) normalized / 12.92
            else ((normalized + 0.055) / 1.055).pow(2.4)
        }.let { (red, green, blue) ->
            0.2126 * red + 0.7152 * green + 0.0722 * blue
        }

    private fun colorHex(color: java.awt.Color): String =
        "#%02x%02x%02x".format(color.red, color.green, color.blue)

    private fun requestBlockbenchSave() {
        if (disposed) return
        val current = browser?.cefBrowser ?: return
        runCatching { current.executeJavaScript("window.__bbIdeaSave();", current.url, 0) }
            .onFailure { logger.warn("Failed to request a Blockbench save", it) }
    }

    private fun onModifiedReported(isModified: Boolean) {
        val previous = modified
        if (previous == isModified) return
        modified = isModified
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            val event = PropertyChangeEvent(this, FileEditor.getPropModified(), previous, isModified)
            propertyChangeListeners.forEach { it.propertyChange(event) }
        }
    }

    private fun onBlockbenchExport(payload: String) {
                val fields = payload.split('\n', limit = 4)
                if (fields.size != 4) {
                    logger.warn("Ignoring malformed Blockbench export message")
                    return
                }

                runCatching {
                    val name = decodeBase64(fields[0]).sanitizeFileName()
                    val startPath = decodeBase64(fields[1])
                    val content = Base64.getDecoder().decode(fields[2])
                    val binary = fields[3] == "binary"
                    val parent = file.parent ?: error("The model has no parent directory")
                    val targetDirectory = resolveExportDirectory(parent.toNioPath(), startPath)
                    Files.createDirectories(targetDirectory)
                    val target = targetDirectory.resolve(name).normalize()
                    if (!target.startsWith(parent.toNioPath())) {
                        error("Blockbench export escaped the project directory")
                    }
                    if (target == file.toNioPath()) {
                        error("Blockbench export would overwrite the open model")
                    }
                    Files.write(target, content)
                    ApplicationManager.getApplication().invokeLater {
                        VirtualFileManager.getInstance().refreshAndFindFileByUrl(VfsUtilCore.pathToUrl(target.toString()))
                    }
                    logger.info("Blockbench exported ${if (binary) "binary" else "text"} file to $target")
                }.onFailure {
                    logger.warn("Failed to write Blockbench export", it)
                }
    }

    private fun resolveExportDirectory(modelDirectory: Path, startPath: String): Path {
                if (startPath.isBlank()) return modelDirectory
                val candidate = Paths.get(startPath)
                val resolved = if (candidate.isAbsolute) candidate else modelDirectory.resolve(candidate)
                return if (Files.isDirectory(resolved)) resolved else resolved.parent ?: modelDirectory
            }

    private fun decodeBase64(value: String): String =
        String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)

    private fun String.sanitizeFileName(): String =
        substringAfterLast('/').substringAfterLast('\\').replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")

    private fun onFilePickerRequest(payload: String) {
        val fields = payload.split('\n', limit = 4)
        if (fields.size != 4) {
            logger.warn("Ignoring malformed Blockbench file picker request")
            return
        }
        val requestId = fields[0]
        val multiple = fields[1] == "1"
        val title = decodeBase64(fields[2])
        logger.info("Opening native Blockbench file picker: $title")
        val descriptor = FileChooserDescriptor(true, false, false, false, false, multiple)
            .withTitle(title.ifBlank { "Select Blockbench file" })
        ApplicationManager.getApplication().invokeLater {
            FileChooser.chooseFiles(descriptor, project, file) { selected ->
                val result = selected.joinToString("\n") { selectedFile ->
                    val name = Base64.getEncoder().encodeToString(selectedFile.name.toByteArray(StandardCharsets.UTF_8))
                    val path = Base64.getEncoder().encodeToString(selectedFile.path.toByteArray(StandardCharsets.UTF_8))
                    val content = Base64.getEncoder().encodeToString(selectedFile.contentsToByteArray())
                    "$name\t$path\t$content"
                }
                val current = browser?.getCefBrowser() ?: return@chooseFiles
                val js = "window.__bbIdeaFilePickerResult(" +
                    jsStringLiteral(requestId) + ", " +
                    jsStringLiteral(result) + ");"
                current.executeJavaScript(js, current.url, 0)
            }
        }
    }

    private fun onSaveRequested(content: String) {
        if (saveInProgress) return
        saveInProgress = true
        ApplicationManager.getApplication().invokeLater {
            if (disposed) {
                saveInProgress = false
                return@invokeLater
            }
            var ok = false
            var message = SAVED_MESSAGE
            try {
                WriteCommandAction.runWriteCommandAction(
                    project,
                    SAVE_COMMAND_NAME,
                    null,
	                {
	                    file.setBinaryContent(content.toByteArray(StandardCharsets.UTF_8))
	                },
                )
                ok = true
            } catch (t: Throwable) {
                logger.warn("Failed to write model back to file", t)
                message = t.message ?: "Unknown error"
            } finally {
                saveInProgress = false
            }
            notifySaveResult(ok, message)
        }
    }

    private fun notifySaveResult(ok: Boolean, message: String) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            val current = browser?.cefBrowser ?: return@invokeLater
            val js = "window.__bbIdeaSaveResult(" + ok + ", " + jsStringLiteral(message) + ");"
            runCatching { current.executeJavaScript(js, current.url, 0) }
                .onFailure { logger.warn("Failed to notify Blockbench about save result", it) }
        }
    }

    // ------------------------------------------------------------------ Helpers

    private fun readFileContent(): String? {
        if (!file.isValid || !file.exists()) {
            logger.warn("Cannot load Blockbench model because the file no longer exists: ${file.presentableUrl}")
            return null
        }
        return ApplicationManager.getApplication().runReadAction<String?> {
            try {
                val bytes = file.contentsToByteArray()
                String(bytes, StandardCharsets.UTF_8)
            } catch (e: IOException) {
                logger.warn("Failed to read Blockbench model from ${file.presentableUrl}", e)
                null
            }
        }
    }

    private fun buildUnsupportedPanel(): JComponent = JBPanel<JBPanel<*>>().apply {
        isOpaque = true
        background = UIManager.getColor("Panel.background") ?: java.awt.Color(30, 33, 39)
        layout = BorderLayout()
        add(
            JBLabel(BlockbenchBundle.message("editor.jcefUnsupported")).apply {
                border = javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8)
            },
            BorderLayout.NORTH,
        )
    }

    private fun buildMissingRuntimePanel(): JComponent = JBPanel<JBPanel<*>>().apply {
        isOpaque = true
        background = UIManager.getColor("Panel.background") ?: java.awt.Color(30, 33, 39)
        layout = BorderLayout()
        add(
            JBLabel(BlockbenchBundle.message("editor.runtimeMissing")).apply {
                border = javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8)
            },
            BorderLayout.NORTH,
        )
    }

    private fun isConfiguredBlockbenchPage(url: String): Boolean {
        val configuredUrl = BlockbenchRuntime.resolveUrl() ?: return false
        return if (configuredUrl.startsWith("file:")) {
            url.startsWith(configuredUrl.substringBeforeLast('/'))
        } else {
            url.startsWith(configuredUrl)
        }
    }

    private fun jsStringLiteral(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u0008' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                in '\u0000'..'\u001F' -> sb.append("\\u").append(String.format("%04x", c.code))
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {
        private const val EDITOR_NAME = "Blockbench"
        private const val SAVE_COMMAND_NAME = "Save Blockbench model"
        private const val SAVED_MESSAGE = "Saved"

        private const val MESSAGE_READY = "ready\n"
        private const val MESSAGE_PROJECT_LOADED = "project_loaded\n"
        private const val MESSAGE_SAVE = "save\n"
        private const val MESSAGE_MODIFIED = "modified\n"
        private const val MESSAGE_SETTINGS = "settings\n"
        private const val MESSAGE_PLUGIN = "plugin\n"
        private const val MESSAGE_EXPORT = "export\n"
        private const val MESSAGE_PICKER = "picker\n"
        private const val MESSAGE_PROJECT_TYPES = "project_types\n"
        private const val MESSAGE_ERROR = "error\n"

        private const val BRIDGE_INJECT_MARKER = "/*__INJECT__*/"
        private val NATIVE_SAVE_ACTIONS = setOf("SaveAll", "SaveDocument")

        private data class ProjectType(
            val kind: String,
            val id: String,
            val label: String,
        )
    }
}