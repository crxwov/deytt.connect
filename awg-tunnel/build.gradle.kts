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
            final CharSequence appLabel = getApplicationInfo().loadLabel(getPackageManager());
            final Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, FOREGROUND_CHANNEL_ID)
                    : new Notification.Builder(this);
            builder.setSmallIcon(getApplicationInfo().icon)
                    .setContentTitle(appLabel)
                    .setContentText("Соединение активно")
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(this, getApplicationInfo().icon),
                            "Отключить",
                            disconnect).build());
            if (contentIntent != null)
                builder.setContentIntent(contentIntent);
            final Notification notification = builder.build();
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

// AGP also consumes this source directory for annotation extraction and lint;
// make that dependency explicit for every producer/consumer task while
// leaving clean free to remove the generated mirror.
tasks.configureEach {
    if (name != "prepareDeyttAwgBackend" && name != "clean") {
        dependsOn(prepareDeyttAwgBackend)
    }
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
                targets("libwg-go.so", "libwg.so", "libwg-quick.so")
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
            path = file("../third_party/amneziawg-android/tunnel/tools/CMakeLists.txt")
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
}
