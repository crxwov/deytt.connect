package space.deytt.connect

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class ReleaseInfo(
    val tag: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSize: Long? = null,
    val apkDigest: String? = null,
)

internal object ReleaseUrlPolicy {
    private const val GITHUB_RELEASE_PATH = "/crxwov/deytt.connect/releases/"
    private const val SERVER_RELEASE_PATH = "/connect/releases/"

    fun isOfficialPage(raw: String): Boolean = runCatching {
        val url = URL(raw)
        if (!url.protocol.equals("https", ignoreCase = true) || url.userInfo != null ||
            (url.port != -1 && url.port != 443) || url.query != null || url.ref != null) return false
        val host = url.host.lowercase()
        val path = url.path
        if (host == "deytt.space" || host == "ru.deytt.space") {
            return path == "/download/" || path == "/download" || path == "/connect/" || path.startsWith(SERVER_RELEASE_PATH)
        }
        if (host == "github.com") {
            return path.matches(Regex("${Regex.escape(GITHUB_RELEASE_PATH)}tag/[A-Za-z0-9._-]+"))
        }
        false
    }.getOrDefault(false)

    fun isOfficialAsset(raw: String): Boolean = runCatching {
        val url = URL(raw)
        if (!url.protocol.equals("https", ignoreCase = true) || url.userInfo != null ||
            (url.port != -1 && url.port != 443) || url.query != null || url.ref != null) return false
        val host = url.host.lowercase()
        val path = url.path
        if (host == "deytt.space" || host == "ru.deytt.space") {
            return path.matches(Regex("${Regex.escape(SERVER_RELEASE_PATH)}[A-Za-z0-9._-]+\\.apk")) ||
                path.matches(Regex("/connect/[A-Za-z0-9._-]+\\.apk"))
        }
        if (host == "github.com" || host.endsWith(".githubusercontent.com")) {
            return path.matches(Regex("${Regex.escape(GITHUB_RELEASE_PATH)}download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.apk")) ||
                path.matches(Regex("/github-production-release-asset-[A-Za-z0-9]+/.*\\.apk"))
        }
        false
    }.getOrDefault(false)
}

internal object ReleaseAssetPolicy {
    fun isStableRelease(draft: Boolean, prerelease: Boolean): Boolean = !draft

    fun preferredName(names: List<String>, supportedAbis: List<String>): String? {
        for (abi in supportedAbis + "universal") {
            val name = "app-$abi-release.apk"
            if (name in names) return name
        }
        return null
    }
}

internal object UpdateSignaturePolicy {
    fun accepts(
        installedSigners: Set<String>,
        candidateSigners: Set<String>,
        candidateHistory: List<String>,
    ): Boolean {
        if (installedSigners.isEmpty() || candidateSigners.isEmpty()) return false
        if (installedSigners == candidateSigners) return true
        if (installedSigners.size != 1 || candidateSigners.size != 1) return false
        return candidateHistory.lastOrNull() == candidateSigners.single() &&
            installedSigners.single() in candidateHistory.dropLast(1)
    }
}

object UpdateChecker {
    private const val SERVER_UPDATE_URL = "https://deytt.space/connect/releases/update.json"
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/crxwov/deytt.connect/releases?per_page=20"
    private const val MAX_RELEASE_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_APK_BYTES = 250L * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    fun latest(): ReleaseInfo {
        // 1. Try dedicated fast server mirror first (no rate-limits, no DPI drops)
        val fromServer = runCatching { fetchServerRelease() }.getOrNull()
        if (fromServer != null) return fromServer

        // 2. Fall back to GitHub releases
        return fetchGitHubRelease()
    }

    private fun fetchServerRelease(): ReleaseInfo {
        val connection = (URL(SERVER_UPDATE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "deytt-connect/${BuildConfig.VERSION_NAME}")
        }
        return try {
            check(connection.responseCode in 200..299) { "Server release check returned HTTP ${connection.responseCode}" }
            val raw = connection.inputStream.use { readLimited(it, MAX_RELEASE_RESPONSE_BYTES) }.toString(Charsets.UTF_8)
            val json = JSONObject(raw)
            val tag = json.optString("tag").ifBlank { json.optString("version") }.ifBlank { "unknown" }
            val pageUrl = json.optString("page_url").ifBlank { "https://deytt.space/download/" }
            val apkUrl = json.optString("apk_url").takeIf(ReleaseUrlPolicy::isOfficialAsset)
                ?: error("Server returned untrusted or missing APK URL")
            val size = json.optLong("apk_size", 0L).takeIf { it in 1..MAX_APK_BYTES }
            val digest = json.optString("apk_sha256").takeIf(String::isNotBlank)
                ?: json.optString("apk_digest").takeIf(String::isNotBlank)
            ReleaseInfo(
                tag = tag,
                pageUrl = pageUrl,
                apkUrl = apkUrl,
                apkSize = size,
                apkDigest = digest,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchGitHubRelease(): ReleaseInfo {
        val connection = (URL(GITHUB_RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "deytt-connect/${BuildConfig.VERSION_NAME}")
        }
        return try {
            check(connection.responseCode in 200..299) { "GitHub release check failed" }
            val releases = JSONArray(connection.inputStream.use { readLimited(it, MAX_RELEASE_RESPONSE_BYTES) }.toString(Charsets.UTF_8))
            var selectedRelease: ReleaseInfo? = null
            for (index in 0 until releases.length()) {
                val json = releases.optJSONObject(index) ?: continue
                if (!ReleaseAssetPolicy.isStableRelease(
                        draft = json.optBoolean("draft", true),
                        prerelease = json.optBoolean("prerelease", true),
                    )) continue

                val assets = json.optJSONArray("assets")
                val candidates = (0 until (assets?.length() ?: 0))
                    .mapNotNull { assets?.optJSONObject(it) }
                    .filter { ReleaseUrlPolicy.isOfficialAsset(it.optString("browser_download_url")) }
                val preferredName = ReleaseAssetPolicy.preferredName(
                    candidates.map { it.optString("name") }, Build.SUPPORTED_ABIS.toList(),
                ) ?: continue
                val apkAsset = candidates.firstOrNull { it.optString("name") == preferredName } ?: continue
                val pageUrl = json.optString("html_url")
                check(ReleaseUrlPolicy.isOfficialPage(pageUrl)) { "Untrusted release page" }
                val apkUrl = apkAsset.optString("browser_download_url")
                    .takeIf(ReleaseUrlPolicy::isOfficialAsset) ?: continue
                val assetSize = apkAsset.optLong("size", 0L).takeIf { it in 1..MAX_APK_BYTES }
                val assetDigest = apkAsset.optString("digest").takeIf(String::isNotBlank)
                selectedRelease = ReleaseInfo(
                    tag = json.optString("tag_name").ifBlank { "unknown" },
                    pageUrl = pageUrl,
                    apkUrl = apkUrl,
                    apkSize = assetSize,
                    apkDigest = assetDigest,
                )
                break
            }
            selectedRelease ?: error("GitHub returned no published release with a compatible APK")
        } finally {
            connection.disconnect()
        }
    }

    fun downloadAndVerify(context: Context, release: ReleaseInfo, onProgress: (Int) -> Unit = {}): File {
        val asset = release.apkUrl ?: error("Release has no APK asset")
        check(ReleaseUrlPolicy.isOfficialAsset(asset)) { "APK URL is not an official asset" }
        val cache = File(context.cacheDir, "updates")
        check(cache.exists() || cache.mkdirs()) { "Cannot create update cache" }
        val safeTag = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "release" }
        val partial = File(cache, "deytt-connect-$safeTag.apk.part")
        val output = File(cache, "deytt-connect-$safeTag.apk")

        var received = if (partial.exists()) partial.length() else 0L
        val totalLength = release.apkSize ?: -1L
        if (totalLength > 0 && received >= totalLength) {
            partial.delete()
            received = 0L
        }

        var attempts = 0
        val maxDownloadAttempts = 4
        var reportedProgress = -1

        while (attempts < maxDownloadAttempts) {
            attempts++
            var connection: HttpURLConnection? = null
            try {
                connection = openAssetConnection(URL(asset), received)
                val status = connection.responseCode
                val isRangeResponse = (status == 206)
                if (received > 0L && !isRangeResponse && status == 200) {
                    partial.delete()
                    received = 0L
                }
                val streamLength = connection.getHeaderFieldLong("Content-Length", -1L)
                val expectedTotal = if (isRangeResponse && streamLength > 0) received + streamLength
                    else if (streamLength > 0) streamLength else totalLength

                val deadline = android.os.SystemClock.elapsedRealtime() + 15 * 60_000L
                val outputStream = FileOutputStream(partial, received > 0L)
                outputStream.use { file ->
                    connection.inputStream.use { raw ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!Thread.currentThread().isInterrupted) { "APK download cancelled" }
                            check(android.os.SystemClock.elapsedRealtime() < deadline) { "APK download timed out" }
                            val count = raw.read(buffer)
                            if (count < 0) break
                            file.write(buffer, 0, count)
                            received += count
                            check(received <= MAX_APK_BYTES) { "APK exceeds the download limit" }
                            if (expectedTotal > 0) {
                                val progress = ((received * 100L) / expectedTotal).toInt().coerceIn(0, 100)
                                if (progress != reportedProgress) {
                                    reportedProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }

                if (totalLength > 0 && received < totalLength) {
                    continue
                }
                break
            } catch (failure: Throwable) {
                if (Thread.currentThread().isInterrupted || attempts >= maxDownloadAttempts) {
                    partial.delete()
                    throw failure
                }
                received = if (partial.exists()) partial.length() else 0L
                try {
                    Thread.sleep(1000L * attempts)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    partial.delete()
                    throw failure
                }
            } finally {
                connection?.disconnect()
            }
        }

        check(received > 0 && (totalLength < 0 || received == totalLength)) {
            partial.delete()
            "APK download is incomplete (received $received bytes, expected $totalLength)"
        }

        // Verify SHA-256 digest
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val fileHex = hex(digest.digest())
        release.apkDigest?.let { expected ->
            val expectedHex = expected.removePrefix("sha256:").lowercase()
            check(Regex("[0-9a-f]{64}").matches(expectedHex)) { "Invalid APK digest format" }
            check(fileHex == expectedHex) {
                partial.delete()
                "APK digest does not match metadata"
            }
        }

        verifyInstalledPackageSignature(context, partial)
        if (output.exists()) check(output.delete()) { "Cannot replace cached APK" }
        check(partial.renameTo(output)) { "Cannot finalize APK download" }
        onProgress(100)
        return output
    }

    fun launchInstaller(context: Context, apk: File) {
        require(apk.isFile && apk.extension.equals("apk", ignoreCase = true))
        verifyInstalledPackageSignature(context, apk)
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.updateprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun openAssetConnection(initial: URL, startByte: Long = 0L): HttpURLConnection {
        var url = initial
        repeat(MAX_REDIRECTS + 1) { attempt ->
            check(isAllowedAssetHost(url)) { "APK redirected to an untrusted host" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "deytt-connect/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/octet-stream")
                if (startByte > 0L) {
                    setRequestProperty("Range", "bytes=$startByte-")
                }
            }
            val status = connection.responseCode
            if (status in 200..299) return connection
            if (status in 300..399 && attempt < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                check(!location.isNullOrBlank()) { "APK redirect is missing its location" }
                url = URL(url, location)
            } else {
                connection.disconnect()
                error("APK download failed with HTTP $status")
            }
        }
        error("Too many APK redirects")
    }

    private fun isAllowedAssetHost(url: URL): Boolean {
        val host = url.host.lowercase()
        return url.protocol.equals("https", ignoreCase = true) && url.userInfo == null &&
            (url.port == -1 || url.port == 443) &&
            (host == "deytt.space" || host == "ru.deytt.space" || host == "github.com" || host.endsWith(".githubusercontent.com"))
    }

    internal fun verifyInstalledPackageSignature(
        context: Context,
        apk: File,
        allowDebuggable: Boolean = BuildConfig.DEBUG,
    ) {
        val packageManager = context.packageManager
        val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, signingCertificateQueryFlags())
            ?: error("Downloaded file is not a valid Android package")
        check(archive.packageName == BuildConfig.APPLICATION_ID) { "Downloaded APK belongs to another app" }
        val application = archive.applicationInfo ?: error("Downloaded APK has no application information")
        check(allowDebuggable || application.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "Downloaded APK is a debug build"
        }
        val installed = installedPackageInfo(context)
        check(packageVersion(archive) > packageVersion(installed)) { "Downloaded APK is not a newer version" }
        val expected = signingCertificateDigests(installed)
        val actual = signingCertificateDigests(archive)
        val history = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.signingCertificateHistory.orEmpty().map {
                hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
            }
        } else emptyList()
        check(UpdateSignaturePolicy.accepts(expected, actual, history)) {
            "Downloaded APK signing certificate does not match"
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateQueryFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(context: Context): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(signingCertificateQueryFlags().toLong()),
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, signingCertificateQueryFlags())
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            signingInfo.apkContentsSigners.orEmpty()
        } else info.signatures.orEmpty()
        return signatures.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun packageVersion(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun readLimited(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= maximum) { "Release response exceeds the size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
