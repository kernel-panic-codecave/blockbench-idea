package net.kernelpanicsoft.blockbenchidea.editor

import com.intellij.openapi.application.PathManager
import com.sun.net.httpserver.HttpServer
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipInputStream
import java.util.concurrent.ConcurrentHashMap

internal object BlockbenchRuntime {

    private val servers = ConcurrentHashMap<Path, HttpServer>()

    fun resolveUrl(): String? {
        BlockbenchVersionManager.getInstance().selectedUrl()?.let { return it }
        val configuredUrl = System.getProperty(URL_PROPERTY)
            ?: System.getenv(URL_ENVIRONMENT)
        if (!configuredUrl.isNullOrBlank()) {
            return configuredUrl.trimEnd('/') + "/"
        }

        val configuredRoot = System.getProperty(ROOT_PROPERTY)
            ?: System.getenv(ROOT_ENVIRONMENT)
        if (!configuredRoot.isNullOrBlank()) {
            return serveRoot(Paths.get(configuredRoot))
        }

        val installed = discoveredRoots()
            .asSequence()
            .mapNotNull(::serveRoot)
            .firstOrNull()
        if (installed != null) return installed

        return downloadLatestRelease()
    }

    private fun discoveredRoots(): List<Path> {
        val home = Paths.get(System.getProperty("user.home"))
        return when (System.getProperty("os.name").lowercase()) {
            in listOf("mac os x", "macos") -> listOf(
                Paths.get("/Applications/Blockbench.app/Contents/Resources/app"),
                home.resolve("Applications/Blockbench.app/Contents/Resources/app"),
            )
            else -> listOf(
                home.resolve(".local/share/blockbench/app"),
                home.resolve(".local/share/blockbench"),
                home.resolve(".config/blockbench/app"),
                Paths.get("/opt/blockbench/app"),
            )
        }
    }

    private fun rootUrl(root: Path): String? {
        val index = root.resolve("index.html")
        if (!Files.isRegularFile(index) || !Files.isRegularFile(root.resolve("dist/bundle.js"))) return null
        return index.toUri().toString()
    }

    internal fun serveBuiltRoot(root: Path): String? {
        if (rootUrl(root) == null) return null
        synchronized(this) {
            servers[root]?.let { return "http://127.0.0.1:${it.address.port}/" }
            val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            created.createContext("/") { exchange ->
                val relative = exchange.requestURI.path
                    .removePrefix("/")
                    .ifEmpty { "index.html" }
                val target = root.resolve(relative).normalize()
                if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                    return@createContext
                }

                val content = Files.readAllBytes(target)
                exchange.responseHeaders.set("Content-Type", contentType(target))
                exchange.sendResponseHeaders(200, content.size.toLong())
                exchange.responseBody.use { it.write(content) }
            }
            created.start()
            servers[root] = created
            return "http://127.0.0.1:${created.address.port}/"
        }
    }

    internal fun stopRoot(root: Path) {
        servers.remove(root)?.stop(0)
    }

    private fun serveRoot(root: Path): String? = serveBuiltRoot(root)

    private fun contentType(path: Path): String =
        when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "woff", "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }

    private fun downloadLatestRelease(): String? {
        synchronized(this) {
            val cached = serveRoot(webRoot)
            if (cached != null) return cached

            return runCatching {
                Files.createDirectories(cacheRoot)
                val archiveUrl = "https://github.com/JannisX11/blockbench/archive/refs/heads/gh-pages.zip"
                val archive = cacheRoot.resolve("gh-pages.zip")
                if (!Files.exists(archive)) download(archiveUrl, archive)
                unpack(archive, cacheRoot.resolve("web"))
                serveRoot(webRoot)
            }.getOrElse {
                Files.deleteIfExists(cacheRoot.resolve("web"))
                Files.deleteIfExists(cacheRoot.resolve("gh-pages.zip"))
                null
            }
        }
    }

    private fun download(url: String, destination: Path) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.inputStream.use { input ->
            Files.newOutputStream(destination).use { output -> input.copyTo(output) }
        }
    }

    private fun unpack(archive: Path, destination: Path) {
        Files.createDirectories(destination)
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = destination.resolve(entry.name).normalize()
                if (!target.startsWith(destination)) error("Invalid Blockbench archive entry: ${entry.name}")
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { output -> zip.copyTo(output) }
                }
            }
        }
    }

    private const val NETWORK_TIMEOUT_MS = 30_000
    private val cacheRoot: Path =
        Paths.get(PathManager.getSystemPath(), "blockbench-idea", "runtime")
    private val webRoot: Path = cacheRoot.resolve("web/blockbench-gh-pages")

    private const val URL_PROPERTY = "blockbench.url"
    private const val URL_ENVIRONMENT = "BLOCKBENCH_URL"
    private const val ROOT_PROPERTY = "blockbench.webRoot"
    private const val ROOT_ENVIRONMENT = "BLOCKBENCH_WEB_ROOT"
}
