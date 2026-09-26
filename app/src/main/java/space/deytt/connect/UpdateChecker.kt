package space.deytt.connect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import org.json.JSONArray

data class ReleaseInfo(
    val tag: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSize: Long? = null,
    val apkDigest: String? = null,
)

internal object ReleaseUrlPolicy {
    fun isOfficialPage(raw: String): Boolean = runCatching {
        val url = URL(raw)
        url.protocol.equals("https", ignoreCase = true) &&
            url.host.equals("github.com", ignoreCase = true) &&
            url.userInfo == null
    }.getOrDefault(false)

    fun isOfficialAsset(raw: String): Boolean = runCatching {
        val url = URL(raw)
        url.protocol.equals("https", ignoreCase = true) &&
            url.host.equals("github.com", ignoreCase = true) &&
            url.userInfo == null
    }.getOrDefault(false)
}

object UpdateChecker {
    // GitHub's /releases/latest deliberately excludes prereleases. The public
    // Android channel is currently prerelease based, so inspect ordered releases.
    private const val RELEASES_URL = "https://api.github.com/repos/crxwov/deytt.connect/releases?per_page=20"
    private const val MAX_RELEASE_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_APK_BYTES = 120L * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    fun latest(): ReleaseInfo {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "deytt-connect/${BuildConfig.VERSION_NAME}")
        }
        return try {
            check(connection.responseCode in 200..299) { "GitHub release check failed" }
            val releases = JSONArray(connection.inputStream.use { readLimited(it, MAX_RELEASE_RESPONSE_BYTES) }.toString(Charsets.UTF_8))
            val json = (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .firstOrNull { !it.optBoolean("draft", true) }
                ?: error("GitHub returned no published releases")
            val pageUrl = json.optString("html_url")
            check(ReleaseUrlPolicy.isOfficialPage(pageUrl)) { "Untrusted release page" }
            val assets = json.optJSONArray("assets")
            val apkAsset = (0 until (assets?.length() ?: 0))
                .mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            val apkUrl = apkAsset?.optString("browser_download_url")
                ?.takeIf(ReleaseUrlPolicy::isOfficialAsset)
            val assetSize = apkAsset?.optLong("size", 0L)?.takeIf { it in 1..MAX_APK_BYTES }
            val assetDigest = apkAsset?.optString("digest")?.takeIf(String::isNotBlank)
            ReleaseInfo(
                tag = json.optString("tag_name").ifBlank { "unknown" },
                pageUrl = pageUrl,
                apkUrl = apkUrl,
                apkSize = assetSize,
                apkDigest = assetDigest,
            )
        } finally {
            connection.disconnect()
        }
    }

    fun downloadAndVerify(context: Context, release: ReleaseInfo, onProgress: (Int) -> Unit = {}): File {
        val asset = release.apkUrl ?: error("Release has no APK asset")
        check(ReleaseUrlPolicy.isOfficialAsset(asset)) { "APK URL is not an official GitHub asset" }
        val cache = File(context.cacheDir, "updates")
        check(cache.exists() || cache.mkdirs()) { "Cannot create update cache" }
        val safeTag = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "release" }
        val partial = File(cache, "deytt-connect-$safeTag.apk.part")
        val output = File(cache, "deytt-connect-$safeTag.apk")
        partial.delete()

        val connection = openAssetConnection(URL(asset))
        try {
            val length = connection.getHeaderFieldLong("Content-Length", -1L)
            check(length <= MAX_APK_BYTES) { "APK exceeds the download limit" }
            if (length > 0 && release.apkSize != null) check(length == release.apkSize) { "APK size does not match GitHub metadata" }

            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            connection.inputStream.use { raw ->
                DigestInputStream(raw, digest).use { input ->
                    FileOutputStream(partial).use { file ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            check(received <= MAX_APK_BYTES) { "APK exceeds the download limit" }
                            file.write(buffer, 0, count)
                            if (length > 0) onProgress(((received * 100L) / length).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            check(received > 0 && (length < 0 || received == length)) { "APK download is incomplete" }
            release.apkSize?.let { check(received == it) { "APK size does not match GitHub metadata" } }
            release.apkDigest?.let { expected ->
                val expectedHex = expected.removePrefix("sha256:").lowercase()
                check(Regex("[0-9a-f]{64}").matches(expectedHex)) { "Invalid GitHub APK digest" }
                check(hex(digest.digest()) == expectedHex) { "APK digest does not match GitHub metadata" }
            }
            verifyInstalledPackageSignature(context, partial)
            if (output.exists()) check(output.delete()) { "Cannot replace cached APK" }
            check(partial.renameTo(output)) { "Cannot finalize APK download" }
            onProgress(100)
            return output
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    fun launchInstaller(context: Context, apk: File) {
        require(apk.isFile && apk.extension.equals("apk", ignoreCase = true))
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

    private fun openAssetConnection(initial: URL): HttpURLConnection {
        var url = initial
        repeat(MAX_REDIRECTS + 1) { attempt ->
            check(isAllowedAssetHost(url)) { "GitHub redirected the APK to an untrusted host" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "deytt-connect/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val status = connection.responseCode
            if (status in 200..299) return connection
            if (status in 300..399 && attempt < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                check(!location.isNullOrBlank()) { "GitHub APK redirect is missing its location" }
                url = URL(url, location)
            } else {
                connection.disconnect()
                error("GitHub APK download failed")
            }
        }
        error("Too many GitHub APK redirects")
    }

    private fun isAllowedAssetHost(url: URL): Boolean {
        val host = url.host.lowercase()
        return url.protocol.equals("https", ignoreCase = true) && url.userInfo == null &&
            (url.port == -1 || url.port == 443) &&
            (host == "github.com" || host.endsWith(".githubusercontent.com"))
    }

    private fun verifyInstalledPackageSignature(context: Context, apk: File) {
        val packageManager = context.packageManager
        val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("Downloaded file is not a valid Android package")
        check(archive.packageName == BuildConfig.APPLICATION_ID) { "Downloaded APK belongs to another app" }
        val installed = installedPackageInfo(context)
        val expected = signingCertificateDigests(installed)
        val actual = signingCertificateDigests(archive)
        check(expected.isNotEmpty() && actual == expected) { "Downloaded APK signing certificate does not match" }
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(context: Context): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else info.signatures.orEmpty()
        return signatures.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun readLimited(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= maximum) { "GitHub release response exceeds the size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
