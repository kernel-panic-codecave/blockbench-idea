package net.kernelpanicsoft.blockbenchidea.editor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.util.Key
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.util.PropertiesComponent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import java.net.HttpURLConnection
import java.net.URI
import java.util.Comparator

@Service(Service.Level.APP)
internal class BlockbenchVersionManager {

    fun selectedVersion(): String? =
        PropertiesComponent.getInstance().getValue(SELECTED_VERSION_KEY)

    fun installedVersions(): List<String> =
        if (!Files.isDirectory(versionsRoot)) {
            emptyList()
        } else
        Files.list(versionsRoot).use { paths ->
            paths.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }

    fun install(project: Project, version: String, onFinished: () -> Unit) {
        val normalized = version.trim().removePrefix("v")
        if (!normalized.matches(Regex("\\d+\\.\\d+\\.\\d+"))) return
        object : Task.Backgroundable(project, "Installing Blockbench $normalized", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Cloning Blockbench $normalized"
                Files.createDirectories(versionsRoot)
                val destination = versionsRoot.resolve(normalized)
                if (!Files.exists(destination.resolve("package.json"))) {
                    runCommand(
                        project,
                        listOf("git", "clone", "--depth", "1", "--branch", "v$normalized", REPOSITORY_URL, destination.toString()),
                        indicator,
                    )
                }
                indicator.text = "Installing Blockbench dependencies"
                runCommand(
                    project,
                    listOf("npm", "install", "--include=dev", "--no-audit", "--no-fund"),
                    indicator,
                    destination,
                )
                if (!Files.isRegularFile(destination.resolve("node_modules/esbuild/package.json"))) {
                    error("npm install completed without installing the Blockbench build dependencies")
                }
                indicator.text = "Building Blockbench web distribution"
                buildWebDistribution(destination, indicator)
                if (!Files.isRegularFile(destination.resolve("dist/bundle.js"))) {
                    error("Blockbench web build completed without producing dist/bundle.js")
                }
                PropertiesComponent.getInstance().setValue(SELECTED_VERSION_KEY, normalized)
            }

            override fun onSuccess() {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Blockbench")
                    .createNotification(
                        "Blockbench $normalized is ready",
                        "The web distribution was built and selected for new Blockbench editors.",
                        NotificationType.INFORMATION,
                    )
                    .notify(project)
                onFinished()
            }

            override fun onThrowable(error: Throwable) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Blockbench")
                    .createNotification(
                        "Blockbench $normalized installation failed",
                        error.message ?: error.javaClass.simpleName,
                        NotificationType.ERROR,
                    )
                    .notify(project)
            }
        }.queue()
    }

    fun fetchReleaseTags(
        project: Project,
        onLoaded: (List<String>) -> Unit,
        onFailed: (Throwable) -> Unit,
    ) {
        object : Task.Backgroundable(project, "Loading Blockbench releases", true) {
            private var tags: List<String> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                tags = buildList {
                    var page = 1
                    while (true) {
                        indicator.checkCanceled()
                        indicator.text = "Loading Blockbench releases (page $page)"
                        val response = readUrl("$RELEASES_URL?per_page=100&page=$page")
                        val pageTags = TAG_PATTERN.findAll(response)
                            .map { it.groupValues[1] }
                            .filter { it.matches(RELEASE_TAG_PATTERN) }
                            .toList()
                        if (pageTags.isEmpty()) break
                        addAll(pageTags)
                        page++
                    }
                }.distinct()
                    .sortedWith { left, right ->
                        compareVersion(right, left)
                    }
            }

            override fun onSuccess() = onLoaded(tags)

            override fun onThrowable(error: Throwable) = onFailed(error)
        }.queue()
    }

    fun select(version: String?) {
        PropertiesComponent.getInstance().setValue(SELECTED_VERSION_KEY, version)
    }

    fun delete(version: String) {
        val normalized = version.removePrefix("v")
        if (!normalized.matches(RELEASE_TAG_PATTERN)) return
        if (selectedVersion() == normalized) {
            select(null)
        }
        val root = versionsRoot.resolve(normalized)
        BlockbenchRuntime.stopRoot(root)
        if (Files.exists(root)) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    fun selectedUrl(): String? {
        val version = selectedVersion() ?: return null
        val root = versionsRoot.resolve(version)
        if (!Files.isRegularFile(root.resolve("package.json")) ||
            !Files.isRegularFile(root.resolve("index.html")) ||
            !Files.isRegularFile(root.resolve("dist/bundle.js"))
        ) return null
        injectUserData(root)
        return BlockbenchRuntime.serveBuiltRoot(root)
    }

    fun syncWebSettings(settings: String) {
        val dataRoot = standaloneDataRoot() ?: return
        if (!settings.trimStart().startsWith("{")) return
        runCatching {
            Files.writeString(dataRoot.resolve("blockbench-idea-settings.json"), settings, StandardCharsets.UTF_8)
        }
    }

    fun syncWebPlugin(name: String, content: String) {
        val dataRoot = standaloneDataRoot() ?: return
        val safeName = name.substringAfterLast('/').substringAfterLast('\\')
        if (!safeName.matches(Regex("[A-Za-z0-9._-]+\\.js"))) return
        runCatching {
            val pluginsRoot = dataRoot.resolve("plugins")
            Files.createDirectories(pluginsRoot)
            Files.writeString(pluginsRoot.resolve(safeName), content, StandardCharsets.UTF_8)
        }
    }

    private fun buildWebDistribution(root: Path, indicator: ProgressIndicator) {
        runCommand(
            null,
            listOf("npm", "run", "build-web"),
            indicator,
            root,
        )
        injectUserData(root)
    }

    private fun injectUserData(root: Path) {
        val dataRoot = standaloneDataRoot() ?: return
        val pluginsRoot = dataRoot.resolve("plugins")
        val bundledPlugins = root.resolve("blockbench-idea-user-plugins")
        if (Files.isDirectory(pluginsRoot)) {
            Files.createDirectories(bundledPlugins)
            Files.list(pluginsRoot).use { files ->
                files.filter { Files.isRegularFile(it) }
                    .forEach {
                        Files.copy(
                            it,
                            bundledPlugins.resolve(it.fileName),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
            }
        }

        val settingsJson = readStandaloneSettings(dataRoot)
        val pluginNames = if (Files.isDirectory(bundledPlugins)) {
            Files.list(bundledPlugins).use { files ->
                files.filter { it.fileName.toString().endsWith(".js") }
                    .map { it.fileName.toString() }
                    .toList()
            }
        } else {
            emptyList()
        }
        val bootstrap = root.resolve("blockbench-idea-bootstrap.js")
        Files.writeString(
            bootstrap,
            buildString {
                append("(function(){var settings=")
                append(settingsJson)
                append(";if(settings&&typeof settings==='object'){Object.keys(settings).forEach(function(k){var v=settings[k];localStorage.setItem(k,typeof v==='string'?v:JSON.stringify(v));});}")
                append("var loaded=false;function loadPlugins(){")
                append("if(loaded||!window.Plugins||!window.Blockbench)return;")
                append("loaded=true;")
                pluginNames.forEach { name ->
                    val pluginId = name.removeSuffix(".js")
                    append("(function(id,url){")
                    append("var plugin=new window.Plugin(id);")
                    append("window.Plugins.registered[id]=plugin;")
                    append("fetch(url).then(function(response){")
                    append("if(!response.ok)throw new Error('HTTP '+response.status);")
                    append("return response.text();")
                    append("}).then(function(source){")
                    append("if(/variant\\s*:\\s*['\"]desktop['\"]/.test(source)){")
                    append("console.warn('blockbench-idea skipped desktop-only plugin '+id);return;}")
                    append("(new Function('requireNativeModule','require',source))(")
                    append("function(name){throw new Error('Native module unavailable in web runtime: '+name);},")
                    append("function(name){throw new Error('Node module unavailable in web runtime: '+name);});")
                    append("plugin.installed=true;plugin.source='url';plugin.path=url;")
                    append("if(window.Plugins.source_cache)window.Plugins.source_cache.save(id,source);")
                    append("})")
                    append(".catch(function(error){console.error('blockbench-idea failed to load plugin '+url,error);});")
                    append("})(")
                    append(jsString(pluginId))
                    append(",'/blockbench-idea-user-plugins/")
                    append(jsPathSegment(name))
                    append("');")
                }
                append("}var timer=setInterval(function(){try{loadPlugins();if(loaded)clearInterval(timer);}catch(e){console.error('blockbench-idea plugin load:',e);}},250);})();")
            },
            StandardCharsets.UTF_8,
        )
        val index = root.resolve("index.html")
        val content = Files.readString(index, StandardCharsets.UTF_8)
        val marker = "<!-- blockbench-idea-bootstrap -->"
        if (!content.contains(marker)) {
            Files.writeString(
                index,
                content.replace(
                    "<script type=\"module\" src=\"dist/bundle.js\"></script>",
                    "$marker\n\t<script src=\"blockbench-idea-bootstrap.js\"></script>\n\t<script type=\"module\" src=\"dist/bundle.js\"></script>",
                ),
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun readStandaloneSettings(dataRoot: Path): String {
        val synchronizedSettings = dataRoot.resolve("blockbench-idea-settings.json")
        if (Files.isRegularFile(synchronizedSettings)) {
            return Files.readString(synchronizedSettings, StandardCharsets.UTF_8)
        }
        val exportedSettings = dataRoot.resolve("settings.json")
        if (Files.isRegularFile(exportedSettings)) {
            return Files.readString(exportedSettings, StandardCharsets.UTF_8)
        }

        val levelDb = dataRoot.resolve("Local Storage").resolve("leveldb")
        if (!Files.isDirectory(levelDb)) return "{}"
        val files = Files.list(levelDb).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .sorted { left, right ->
                    Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left))
                }
                .toList()
        }
        for (file in files) {
            val content = Files.readAllBytes(file)
            val settings = extractJsonObject(content, "settings")
            if (settings != null) return settings
        }
        return "{}"
    }

    private fun extractJsonObject(content: ByteArray, key: String): String? {
        val marker = key.toByteArray(StandardCharsets.UTF_8)
        var offset = 0
        while (offset <= content.size - marker.size) {
            val match = content.copyOfRange(offset, offset + marker.size).contentEquals(marker)
            if (!match) {
                offset++
                continue
            }
            var start = offset + marker.size
            if (start < content.size && content[start].toInt().toChar().isLetterOrDigit()) {
                offset++
                continue
            }
            while (start < content.size && content[start].toInt().toChar().isWhitespace()) start++
            while (start < content.size && content[start].toInt().toChar() != '{') start++
            if (start >= content.size) return null

            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until content.size) {
                val character = content[index].toInt().toChar()
                if (quoted) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') quoted = false
                    continue
                }
                when (character) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return content.copyOfRange(start, index + 1)
                                .toString(StandardCharsets.UTF_8)
                        }
                    }
                }
            }
            offset = start + 1
        }
        return null
    }

    private fun standaloneDataRoot(): Path? {
        val home = Paths.get(System.getProperty("user.home"))
        val candidates = when (System.getProperty("os.name").lowercase()) {
            "windows" -> listOf(
                Paths.get(System.getenv("APPDATA") ?: "").resolve("Blockbench"),
            )
            "mac os x", "macos" -> listOf(home.resolve("Library/Application Support/Blockbench"))
            else -> listOf(
                Paths.get(System.getenv("XDG_CONFIG_HOME") ?: home.resolve(".config").toString()).resolve("Blockbench"),
                home.resolve(".config/blockbench"),
            )
        }
        return candidates.firstOrNull { Files.isDirectory(it) }
    }

    private fun jsString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun jsPathSegment(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    private fun runCommand(
        project: Project?,
        command: List<String>,
        indicator: ProgressIndicator,
        workDirectory: Path? = null,
    ) {
        val executable = if (command.first() == "npm" && System.getProperty("os.name").startsWith("Windows")) {
            "npm.cmd"
        } else {
            command.first()
        }
        val line = GeneralCommandLine(listOf(executable) + command.drop(1))
            .withWorkDirectory(workDirectory?.toFile())
            .withEnvironment(System.getenv())
        val handler = OSProcessHandler(line)
        val output = StringBuilder()
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
            }
        })
        handler.startNotify()
        while (!handler.waitFor(200)) {
            indicator.checkCanceled()
            indicator.text2 = command.joinToString(" ")
        }
        if (handler.exitCode != 0) {
            val details = output.toString().trim()
            error(
                buildString {
                    append(command.joinToString(" "))
                    append(" failed with exit code ${handler.exitCode}")
                    if (details.isNotEmpty()) {
                        append(":\n")
                        append(details.takeLast(MAX_PROCESS_OUTPUT_CHARS))
                    }
                },
            )
        }
    }

    private fun readUrl(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "blockbench-idea")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun compareVersion(left: String, right: String): Int {
        val leftParts = left.removePrefix("v").split(".").map(String::toInt)
        val rightParts = right.removePrefix("v").split(".").map(String::toInt)
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        private const val REPOSITORY_URL = "https://github.com/JannisX11/blockbench.git"
        private const val RELEASES_URL = "https://api.github.com/repos/JannisX11/blockbench/releases"
        private const val NETWORK_TIMEOUT_MS = 30_000
        private const val MAX_PROCESS_OUTPUT_CHARS = 8_000
        private val TAG_PATTERN = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
        private val RELEASE_TAG_PATTERN = Regex("v?\\d+\\.\\d+\\.\\d+")
        private const val SELECTED_VERSION_KEY = "blockbench.idea.selected.version"
        private val versionsRoot: Path =
            Paths.get(PathManager.getSystemPath(), "blockbench-idea", "versions")

        fun getInstance(): BlockbenchVersionManager = service()
    }
}
