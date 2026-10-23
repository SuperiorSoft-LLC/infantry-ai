package com.superiorsoftllc.agenthq.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** UPDATE-1: one release asset, as GitHub Releases lists it. */
@Serializable
data class ReleaseAsset(
    val name: String,
    val size: Long = 0,
    /** The API url (`/repos/o/r/releases/assets/{id}`): with `Accept: application/octet-stream` it serves the bytes, private repos included. */
    val url: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
)

/** UPDATE-1: the fields of `GET /repos/{owner}/{repo}/releases/latest` the updater reads. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

/** UPDATE-1: why an update check or download failed, with the short message the window shows. Never carries the token. */
sealed class UpdateFailure(val message: String) {
    class Offline(detail: String) : UpdateFailure("Could not reach GitHub ($detail). You can keep working; try again later.")
    class NotFound(repo: String) : UpdateFailure("No release found for $repo (a private repo needs a token).")
    class Unauthorized(code: Int) : UpdateFailure("GitHub refused the update check (HTTP $code). Check the update token.")
    class RateLimited(resetAt: Instant?) : UpdateFailure(
        "GitHub's rate limit was reached" + (resetAt?.let { " until " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(it) } ?: "") + ". Try again later.",
    )
    class BadResponse(detail: String) : UpdateFailure("Unexpected answer from GitHub: $detail.")
    class NoWindowsAsset(tag: String) : UpdateFailure("Release $tag has no Windows installer (AgentHQ-<version>.msi).")
    class BadAsset(detail: String) : UpdateFailure("The downloaded installer was refused: $detail.")
    class InstallFailed(detail: String) : UpdateFailure("Could not start the installer: $detail.")

    override fun toString(): String = "${this::class.simpleName}($message)"
}

/** UPDATE-1: the outcome of an HTTP call made by the updater. */
sealed interface Fetched<out T> {
    data class Ok<T>(val value: T) : Fetched<T>
    data class Failed(val failure: UpdateFailure) : Fetched<Nothing>
}

/**
 * UPDATE-1: a minimal GitHub Releases client on the JDK's [HttpClient] (no new dependency).
 *
 * Redirects are followed by hand: an asset download redirects to a storage host, and the `Authorization` header must
 * only ever go to the API's own host, never to that one.
 */
class GitHubReleases(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(20),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** `GET {api}/repos/{repo}/releases/latest`. */
    fun latest(config: UpdateConfig): Fetched<GitHubRelease> {
        val uri = URI.create("${config.apiBase}/repos/${config.repo}/releases/latest")
        val notFound = UpdateFailure.NotFound(config.repo)
        return when (val got = send(uri, config, "application/vnd.github+json", HttpResponse.BodyHandlers.ofString(), requestTimeout, notFound)) {
            is Fetched.Failed -> got
            is Fetched.Ok -> runCatching { Fetched.Ok(json.decodeFromString(GitHubRelease.serializer(), got.value.body())) }
                .getOrElse { Fetched.Failed(UpdateFailure.BadResponse("the release JSON could not be read")) }
        }
    }

    /** Open [asset]'s bytes: its API url as `application/octet-stream` (private repos), else its browser url. */
    fun openAsset(asset: ReleaseAsset, config: UpdateConfig, timeout: Duration = Duration.ofMinutes(10)): Fetched<HttpResponse<InputStream>> {
        val target = asset.url ?: asset.browserDownloadUrl ?: return Fetched.Failed(UpdateFailure.BadAsset("${asset.name} has no download url"))
        val accept = if (asset.url != null) "application/octet-stream" else "*/*"
        return send(URI.create(target), config, accept, HttpResponse.BodyHandlers.ofInputStream(), timeout, UpdateFailure.BadAsset("${asset.name} is gone from the release (HTTP 404)"))
    }

    private fun <T> send(start: URI, config: UpdateConfig, accept: String, body: HttpResponse.BodyHandler<T>, timeout: Duration, notFound: UpdateFailure): Fetched<HttpResponse<T>> {
        val apiHost = URI.create(config.apiBase).let { "${it.scheme}://${it.authority}" }
        var uri = start
        repeat(MAX_REDIRECTS + 1) {
            val sameHost = "${uri.scheme}://${uri.authority}".equals(apiHost, ignoreCase = true)
            val request = HttpRequest.newBuilder(uri).timeout(timeout).GET()
                .header("Accept", accept)
                .header("User-Agent", "AgentHQ-updater")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (sameHost) config.token?.let { header("Authorization", "Bearer $it") } }
                .build()
            val response = try {
                http.send(request, body)
            } catch (timedOut: HttpTimeoutException) {
                return Fetched.Failed(UpdateFailure.Offline("timed out"))
            } catch (io: IOException) {
                return Fetched.Failed(UpdateFailure.Offline(io::class.simpleName ?: "network error"))
            }
            val code = response.statusCode()
            when {
                code in 200..299 -> return Fetched.Ok(response)
                code in 300..399 -> {
                    closeQuietly(response)
                    val location = response.headers().firstValue("Location").orElse(null)
                        ?: return Fetched.Failed(UpdateFailure.BadResponse("HTTP $code without a Location"))
                    uri = uri.resolve(location)
                }
                else -> {
                    closeQuietly(response)
                    return Fetched.Failed(if (code == 404) notFound else classify(code, response.headers()))
                }
            }
        }
        return Fetched.Failed(UpdateFailure.BadResponse("too many redirects"))
    }

    private fun classify(code: Int, headers: java.net.http.HttpHeaders): UpdateFailure {
        val remaining = headers.firstValue("x-ratelimit-remaining").orElse(null)?.trim()
        val reset = headers.firstValue("x-ratelimit-reset").orElse(null)?.trim()?.toLongOrNull()?.let(Instant::ofEpochSecond)
        return when {
            code == 429 || (code == 403 && remaining == "0") -> UpdateFailure.RateLimited(reset)
            code == 401 || code == 403 -> UpdateFailure.Unauthorized(code)
            else -> UpdateFailure.BadResponse("HTTP $code")
        }
    }

    private fun closeQuietly(response: HttpResponse<*>) {
        (response.body() as? InputStream)?.let { runCatching { it.close() } }
    }

    companion object {
        const val MAX_REDIRECTS = 5
    }
}
