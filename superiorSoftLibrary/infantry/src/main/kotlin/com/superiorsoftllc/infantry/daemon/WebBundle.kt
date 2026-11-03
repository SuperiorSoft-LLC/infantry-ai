package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.session.InfantryEnv
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

/**
 * WEB-1 (contract 1.122.0): the browser client's static bundle (`:infantryWeb:assembleWeb`), served at `/` on the
 * daemon's own port, so the page and its WebSocket share one origin.
 *
 * The bundle is listed ONCE, at startup: every regular file under [root] becomes one exact route. A request names one
 * of those paths or it is not a bundle request, so there is no path to resolve and nothing to traverse: `..`, encoded
 * slashes and symlinks out of the folder never reach the file system. The files hold no data, so [AccessPolicy] serves
 * them without the token (it still checks Host and Origin).
 */
class WebBundle private constructor(val root: File?, private val files: Map<String, File>) {
    /** True when the bundle is installed (an `index.html` was found). */
    val installed: Boolean get() = INDEX in files

    /** The paths served, sorted. */
    val paths: List<String> get() = files.keys.sorted()

    /** Whether [path] is a bundle request: `/`, always (it answers a note when no bundle is installed), or one of its files. */
    fun isAsset(path: String): Boolean = path == "/" || path in files

    /** The file [path] names, or null. `/` names `index.html`. */
    fun file(path: String): File? = files[if (path == "/") INDEX else path]

    /** One line for the startup banner. */
    fun describe(): String = if (installed) {
        "browser client at / (${files.size} files from ${root?.path})"
    } else {
        "no browser client bundle (build :superiorSoftLibrary:infantryWeb:assembleWeb and set $WEB_DIR_ENV)"
    }

    companion object {
        /** A folder holding the bundle; else `<INFANTRY_APP_HOME>/web`, else the desktop app's resources `web/`. */
        const val WEB_DIR_ENV = "INFANTRY_WEB_DIR"
        private const val INDEX = "/index.html"

        val EMPTY = WebBundle(null, emptyMap())

        /** List [dir]'s files. A missing folder is an empty bundle; a file that resolves outside [dir] is skipped. */
        fun load(dir: File?): WebBundle {
            if (dir == null || !dir.isDirectory) return WebBundle(dir, emptyMap())
            val base = dir.canonicalFile
            val files = base.walkTopDown()
                .onEnter { !it.isHidden || it == base }
                .filter { it.isFile && !it.isHidden }
                .mapNotNull { f ->
                    val canon = f.canonicalFile
                    if (!canon.path.startsWith(base.path + File.separator)) return@mapNotNull null
                    "/" + canon.relativeTo(base).invariantSeparatorsPath to canon
                }
                .filter { (path, _) -> path.none { it in "{}*?#%\\" } }
                .toMap()
            return WebBundle(base, files)
        }

        /** The bundle folder this process should serve, or null. */
        fun locate(
            getenv: (String) -> String? = InfantryEnv::get,
            property: (String) -> String? = System::getProperty,
        ): File? = listOfNotNull(
            getenv(WEB_DIR_ENV)?.takeIf { it.isNotBlank() }?.let(::File),
            getenv("INFANTRY_APP_HOME")?.takeIf { it.isNotBlank() }?.let { File(it, "web") },
            property("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let { File(it, "web") },
        ).firstOrNull { File(it, "index.html").isFile }

        /** The content type of a bundle file. `.wasm` must be `application/wasm` for streaming compilation. */
        fun contentType(name: String): ContentType = when (name.substringAfterLast('.', "").lowercase()) {
            "html" -> ContentType.Text.Html.withParameter("charset", "utf-8")
            "js", "mjs" -> ContentType("text", "javascript").withParameter("charset", "utf-8")
            "wasm" -> ContentType("application", "wasm")
            "json", "map" -> ContentType.Application.Json
            "png" -> ContentType.Image.PNG
            "svg" -> ContentType.Image.SVG
            "txt" -> ContentType.Text.Plain.withParameter("charset", "utf-8")
            "css" -> ContentType.Text.CSS
            "ttf" -> ContentType("font", "ttf")
            "otf" -> ContentType("font", "otf")
            "woff2" -> ContentType("font", "woff2")
            else -> ContentType.Application.OctetStream
        }
    }
}

/** WEB-1: one exact GET route per bundle file, and `/`. Behind [installAccessPolicy] like every route. */
fun Route.webBundleRoutes(bundle: WebBundle) {
    get("/") {
        val index = bundle.file("/")
        if (index == null) {
            call.respondText(
                "This Infantry AI daemon has no browser client installed. Build it with " +
                    "./gradlew :superiorSoftLibrary:infantryWeb:assembleWeb and point ${WebBundle.WEB_DIR_ENV} at build/assembleWeb.\n",
                ContentType.Text.Plain,
                HttpStatusCode.NotFound,
            )
        } else {
            call.respondBundleFile(index)
        }
    }
    for (path in bundle.paths) {
        val file = bundle.file(path) ?: continue
        get(path) { call.respondBundleFile(file) }
    }
}

/**
 * `no-cache` keeps a redeployed bundle from going stale, and the validator makes it cheap: a browser that already holds
 * this exact file revalidates with If-None-Match and gets an empty 304 instead of the ~33 MB of wasm again.
 */
private suspend fun ApplicationCall.respondBundleFile(file: File) {
    val etag = "\"${file.length().toString(16)}-${file.lastModified().toString(16)}\""
    response.cacheControl(CacheControl.NoCache(null))
    response.header(HttpHeaders.ETag, etag)
    val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
    if (ifNoneMatch != null && ifNoneMatch.split(',').any { it.trim().removePrefix("W/") == etag }) {
        respond(HttpStatusCode.NotModified)
    } else {
        respond(LocalFileContent(file, WebBundle.contentType(file.name)))
    }
}
