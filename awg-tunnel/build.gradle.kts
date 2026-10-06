import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.android.library")
}

// Generated overlay keeps the upstream submodule unmodified.
val prepareDeyttAwgBackend by tasks.registering {
    val upstreamRoot = file("../third_party/amneziawg-android/tunnel/src/main/java")
    val upstream = upstreamRoot.resolve("org/amnezia/awg/backend/GoBackend.java")
    val generatedRoot = layout.buildDirectory.dir("generated/deytt-awg")
    val generated = generatedRoot.map { it.file("org/amnezia/awg/backend/GoBackend.java") }
    inputs.dir(upstreamRoot)
    outputs.dir(generatedRoot)
    doLast {
        val outputRoot = generatedRoot.get().asFile
        project.delete(outputRoot)
        project.copy {
            from(upstreamRoot)
            into(outputRoot)
            exclude("org/amnezia/awg/backend/GoBackend.java")
            // The app uses GoBackend only; omit unused privileged shell tooling.
            exclude("org/amnezia/awg/backend/AwgQuickBackend.java")
            exclude("org/amnezia/awg/util/RootShell.java")
            exclude("org/amnezia/awg/util/ToolsInstaller.java")
        }
        val source = upstream.readText()
        var patched = source
        patched = patched.replace(
            "import android.content.Context;\nimport android.content.Intent;\nimport android.os.Build;",
            "import android.app.Notification;\nimport android.app.NotificationChannel;\nimport android.app.NotificationManager;\nimport android.app.PendingIntent;\nimport android.content.ComponentName;\nimport android.content.Context;\nimport android.content.Intent;\nimport android.content.pm.ServiceInfo;\nimport android.graphics.drawable.Icon;\nimport android.os.Build;",
        )
        patched = patched.replace(
            "                context.startService(new Intent(context, VpnService.class));",
            "                final Intent serviceIntent = new Intent(context, VpnService.class);\n                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)\n                    context.startForegroundService(serviceIntent);\n                else\n                    context.startService(serviceIntent);",
        )
        patched = patched.replace(
            "    public static class VpnService extends android.net.VpnService {",
            "    public static class VpnService extends android.net.VpnService {\n        private static final int FOREGROUND_NOTIFICATION_ID = 44;\n        private static final String FOREGROUND_CHANNEL_ID = \"deytt-awg\";",
        )
        patched = patched.replace(
            "            vpnService = vpnService.newIncompleteFuture();\n            super.onDestroy();",
            "            vpnService = vpnService.newIncompleteFuture();\n            stopForeground(true);\n            super.onDestroy();",
        )
        patched = patched.replace(
            "        public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {\n            vpnService.complete(this);",
            "        public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {\n            startForegroundCompat();\n            vpnService.complete(this);",
        )
        patched = patched.replace(
            "        public void setOwner(final GoBackend owner) {",
            """        private void startForegroundCompat() {
            final NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(new NotificationChannel(
                        FOREGROUND_CHANNEL_ID,
                        "Состояние соединения",
                        NotificationManager.IMPORTANCE_LOW));
            }
            final Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            final PendingIntent contentIntent = launchIntent == null ? null : PendingIntent.getActivity(
                    this,
                    FOREGROUND_NOTIFICATION_ID,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            final Intent disconnectIntent = new Intent("space.deytt.connect.action.DISCONNECT_AWG");
            disconnectIntent.setComponent(new ComponentName(
                    getPackageName(), "space.deytt.connect.AwgDisconnectReceiver"));
            final PendingIntent disconnect = PendingIntent.getBroadcast(
                    this,
                    FOREGROUND_NOTIFICATION_ID + 1,
                    disconnectIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            final Intent restoreIntent = new Intent("space.deytt.connect.action.RESTORE_AWG_NOTIFICATION");
            restoreIntent.setComponent(new ComponentName(
                    getPackageName(), "space.deytt.connect.AwgDisconnectReceiver"));
            final PendingIntent restore = PendingIntent.getBroadcast(
                    this,
                    FOREGROUND_NOTIFICATION_ID + 2,
                    restoreIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            final CharSequence appLabel = getApplicationInfo().loadLabel(getPackageManager());
            final Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, FOREGROUND_CHANNEL_ID)
                    : new Notification.Builder(this);
            builder.setSmallIcon(getApplicationInfo().icon)
                    .setContentTitle(appLabel)
                    .setContentText("Соединение активно")
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setDeleteIntent(restore)
                    .setOnlyAlertOnce(true)
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(this, getApplicationInfo().icon),
                            "Отключить",
                            disconnect).build());
            if (contentIntent != null)
                builder.setContentIntent(contentIntent);
            final Notification notification = builder.build();
            notification.flags |= Notification.FLAG_NO_CLEAR;
            if ((notification.flags & Notification.FLAG_ONGOING_EVENT) == 0)
                throw new IllegalStateException("AWG foreground notification must be ongoing");
            if ((notification.flags & Notification.FLAG_AUTO_CANCEL) != 0)
                throw new IllegalStateException("AWG foreground notification must not auto-cancel");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                startForeground(
                        FOREGROUND_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            else
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
        }

        public void setOwner(final GoBackend owner) {""",
        )
        check(patched != source) { "DEYTTT AWG foreground overlay did not match upstream GoBackend.java" }
        val output = generated.get().asFile
        output.parentFile.mkdirs()
        output.writeText("/* DEYTTT overlay: actual AWG VpnService foreground lifecycle. */\n\n$patched")
    }
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(prepareDeyttAwgBackend)
}

// AmneziaWG 3.1 moved the Go module under /v3, but its Android Makefile still
// injects the old linker symbol and pins a Go release below the module minimum.
// Keep the linker socket in this app's writable cache and use the official,
// checksum-pinned Go release required to build the backend.
val prepareDeyttAwgBackendMakefile by tasks.registering {
    val upstreamMakefile = file("../third_party/amneziawg-android/tunnel/tools/libwg-go/Makefile")
    outputs.upToDateWhen { false }
    doLast {
        check(upstreamMakefile.isFile) { "AmneziaWG Go backend Makefile is missing; initialize the vendored submodule" }
        val oldSymbol = "github.com/amnezia-vpn/amneziawg-go/ipc.socketDirectory"
        val newSymbol = "github.com/amnezia-vpn/amneziawg-go/v3/ipc.socketDirectory"
        val source = upstreamMakefile.readText()
        var patched = source
        when {
            source.contains(newSymbol) && !source.contains(oldSymbol) -> Unit
            source.contains(oldSymbol) && !source.contains(newSymbol) ->
                patched = patched.replace(oldSymbol, newSymbol)
            else -> error("Unexpected AmneziaWG Go backend linker symbol; review its Makefile before building")
        }
        val pinnedGoArtifacts = mapOf(
            "GO_VERSION := 1.24.2" to "GO_VERSION := 1.25.14",
            "GO_HASH_darwin-amd64 := 238d9c065d09ff6af229d2e3b8b5e85e688318d69f4006fb85a96e41c216ea83" to
                "GO_HASH_darwin-amd64 := b09087a67d5792a8b0fcbf74212d62560c94ac8a8fd750ff920d8cb5f1e20118",
            "GO_HASH_darwin-arm64 := b70f8b3c5b4ccb0ad4ffa5ee91cd38075df20fdbd953a1daedd47f50fbcff47a" to
                "GO_HASH_darwin-arm64 := 5b26c0b6f308240fca2614fb02f622cfcc8c0cc3b69c78bba4845489a4590259",
            "GO_HASH_linux-amd64 := 68097bd680839cbc9d464a0edce4f7c333975e27a90246890e9f1078c7e702ad" to
                "GO_HASH_linux-amd64 := a21ae5633a269bcd7e90cf767e48225633795e99d831742cbf3397064fee7712",
        )
        for ((oldLine, newLine) in pinnedGoArtifacts) {
            patched = when {
                patched.contains(newLine) && !patched.contains(oldLine) -> patched
                patched.contains(oldLine) && !patched.contains(newLine) -> patched.replace(oldLine, newLine)
                else -> error("Unexpected AmneziaWG Go toolchain pin; review upstream Makefile before building")
            }
        }
        check(patched.contains(newSymbol) && pinnedGoArtifacts.values.all { patched.contains(it) }) {
            "AmneziaWG Go backend patch did not produce the reviewed linker and Go toolchain pins"
        }
        if (patched != source) upstreamMakefile.writeText(patched)

        // The upstream Makefile does not list itself as a prerequisite for
        // libwg-go.so, so an existing binary can survive a linker-flag fix.
        // Keep valid outputs; force only stale builds to be regenerated.
        val expectedSocketPath = "/data/data/space.deytt.connect/cache/amneziawg"
        fileTree(layout.buildDirectory.dir("intermediates/cxx")) {
            include("**/obj/**/libwg-go.so")
        }.files.forEach { output ->
            val contents = output.readBytes().toString(Charsets.ISO_8859_1)
            if (!contents.contains(expectedSocketPath)) {
                check(output.delete()) { "Could not remove stale AmneziaWG backend output: ${output.name}" }
            }
        }
    }
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    dependsOn(prepareDeyttAwgBackendMakefile)
}

// AGP's annotation extraction reads the generated source directly and must
// be ordered after the mirror is produced. Matching only these consumers is
// important: broad task wiring makes native clean tasks depend on generation
// and creates a clean/prepare cycle.
tasks.matching { task ->
    task.name.startsWith("extract") && task.name.endsWith("Annotations")
}.configureEach {
    dependsOn(prepareDeyttAwgBackend)
}

android {
    namespace = "org.amnezia.awg.tunnel"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 24
        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_PACKAGE_NAME=space.deytt.connect",
                    "-DGRADLE_USER_HOME=${project.gradle.gradleUserHomeDir}",
                )
                targets("libwg-go.so", "libdeytt-awg.so")
            }
        }
    }

    sourceSets {
        getByName("main") {
            // Compile a generated mirror so the overlay can replace exactly
            // one upstream class without editing the clean submodule.
            java.srcDir(layout.buildDirectory.dir("generated/deytt-awg"))
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The upstream module supports API 24 through guarded/desugared paths.
        disable += "NewApi"
        disable += "LongLogTag"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.collection:collection:1.5.0")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testImplementation("junit:junit:4.13.2")
}
