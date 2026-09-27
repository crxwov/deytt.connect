package space.deytt.connect

import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.io.File
import java.security.MessageDigest

/** Exercises platform APK verification with synthetic, externally supplied QA fixtures. */
class ReleaseSecurityInstrumentation : Instrumentation() {
    private var fixtureArguments = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fixtureArguments = arguments ?: Bundle()
        start()
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        val results = Bundle()
        val passed = arrayListOf<String>()
        val fixtures = File(targetContext.cacheDir, "release-security-fixtures")
        try {
            check(targetContext.packageName.endsWith(".qa")) { "Isolated QA application required" }
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "Rotation suite requires Android 9 or newer" }
            if (fixtureArguments.getString("mode") == "migrated") {
                verifyMigratedInstallation(passed)
                results.putString("result", "PASS")
                results.putStringArrayList("passed", passed)
                finish(-1, results)
                return
            }
            check(fixtures.exists() || fixtures.mkdirs()) { "Cannot create fixture directory" }
            fun fixture(argument: String): File {
                val path = fixtureArguments.getString(argument)
                check(!path.isNullOrBlank()) { "Missing fixture argument: $argument" }
                val source = File(path)
                check(source.isFile && source.canRead()) { "Unreadable fixture: $argument" }
                return source.copyTo(File(fixtures, "$argument.apk"), overwrite = true)
            }
            fun rejects(argument: String, expectedMessage: String) {
                val failure = runCatching {
                    UpdateChecker.verifyInstalledPackageSignature(targetContext, fixture(argument), allowDebuggable = false)
                }.exceptionOrNull()
                check(failure is IllegalStateException && failure.message == expectedMessage) {
                    "$argument did not fail the expected validation: ${failure?.message}"
                }
                passed += argument
            }

            val rotated = fixture("rotatedApk")
            val pm = targetContext.packageManager
            val installed = pm.getPackageInfo(targetContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo ?: error("Installed signing info missing")
            val candidate = pm.getPackageArchiveInfo(rotated.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?.signingInfo ?: error("Rotated signing info missing")
            check(!installed.hasMultipleSigners() && !candidate.hasMultipleSigners()) { "Expected single signer rotation" }
            val installedSigner = installed.apkContentsSigners.single()
            val candidateSigner = candidate.apkContentsSigners.single()
            check(installedSigner != candidateSigner) { "Fixture must rotate to a different key" }
            val history = candidate.signingCertificateHistory.toList()
            check(history.last() == candidateSigner && installedSigner in history.dropLast(1)) {
                "Platform-verified rotation history does not include installed signer"
            }
            UpdateChecker.verifyInstalledPackageSignature(targetContext, rotated, allowDebuggable = false)
            passed += "rotatedApk"
            rejects("unrelatedApk", "Downloaded APK signing certificate does not match")
            rejects("tamperedApk", "Downloaded file is not a valid Android package")
            rejects("debugApk", "Downloaded APK is a debug build")
            rejects("sameVersionApk", "Downloaded APK is not a newer version")
            results.putString("result", "PASS")
            results.putStringArrayList("passed", passed)
            finish(-1, results)
        } catch (failure: Throwable) {
            results.putString("result", "FAIL:${failure.javaClass.simpleName}")
            results.putString("reason", failure.message)
            results.putStringArrayList("passed", passed)
            finish(0, results)
        } finally {
            fixtures.deleteRecursively()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyMigratedInstallation(passed: ArrayList<String>) {
        val info = targetContext.packageManager.getPackageInfo(
            targetContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val application = info.applicationInfo ?: error("Application info missing")
        check(application.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { "Migrated application remains debuggable" }
        passed += "release_not_debuggable"
        val signing = info.signingInfo ?: error("Migrated signing info missing")
        check(!signing.hasMultipleSigners()) { "Expected single signer rotation" }
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun normalized(value: String) = value.replace(":", "").lowercase()
        val current = digest(signing.apkContentsSigners.single().toByteArray())
        fixtureArguments.getString("expectedSigner")?.let {
            check(current == normalized(it)) { "Migrated current signer differs from expected signer" }
        }
        val old = fixtureArguments.getString("expectedOldSigner")
        check(!old.isNullOrBlank()) { "Missing expectedOldSigner argument" }
        val history = signing.signingCertificateHistory.map { digest(it.toByteArray()) }
        check(history.lastOrNull() == current && current != normalized(old) && normalized(old) in history.dropLast(1)) {
            "Migrated platform-verified history does not include the old signer"
        }
        passed += "rotated_installed_signer_and_history"
        val marker = File(targetContext.filesDir, "release-migration-marker")
        check(marker.isFile && marker.readText().trim() == "deytt-release-migration-check") {
            "Private migration marker did not survive the system update"
        }
        passed += "private_data_retained"
    }
}
