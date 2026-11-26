import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.desktop.application.dsl.PublishMode
import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
plugins {
    // On the classpath through buildSrc, which sets their versions.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.compose.hot-reload")
    alias(libs.plugins.nucleus)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.google.fonts)
    id("com.robomori.development-mac-icon")
}
// The app's fonts, bundled at build time and used by the type styles in RobomoriTokens.kt.
googleFonts {
    packageName.set("com.robomori.fonts")
    family("Inter") { weights(400, 600, 700) }
    family("JetBrains Mono") { weights(400) }
}

fun appEnvironment(name: String) = providers.environmentVariable(name)

val buildVersion = project.version.toString()
val macBuildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orElse("1").get()
require(buildVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Set a stable version in gradle.properties" }
val scrcpyVersion = "5.0.1"
val hostOs = System.getProperty("os.name")
val hostArch = System.getProperty("os.arch")
// Only the platforms Robomori ships on bundle scrcpy. Elsewhere, such as Linux on ARM where the tests run in a VM,
// the app builds and runs without device mirroring.
val scrcpyAsset: Pair<String, String>? = when {
    hostOs.startsWith("Mac") && hostArch in setOf("aarch64", "arm64") ->
        "macos-aarch64" to "33611e51977a8289e2e124b220f727895181ef2eb9060e0987a4dc2db9cf0d6b"
    hostOs.startsWith("Mac") && hostArch == "x86_64" ->
        "macos-x86_64" to "31a5467a9e907f162b093267cbbab1276b15c6e709c2ea8159566a8d165beb50"
    hostOs.startsWith("Windows") && hostArch in setOf("aarch64", "arm64") ->
        "winarm64" to "16c3fc2068df64946f670e3504b4752073ebae47a416a45b46e79a5ab19c689b"
    hostOs.startsWith("Windows") && hostArch in setOf("amd64", "x86_64") ->
        "win64" to "b12a2c4ee8be317422451fc7dcf8ee20a71b5ea7ef9ad73ddd825a25316227a5"
    hostOs.startsWith("Linux") && hostArch in setOf("amd64", "x86_64") ->
        "linux-x86_64" to "9f969d30cc574816077edecda65719c1c70b0aee1df1ba5fa00779ac07b8fd6e"
    else -> null
}
if (scrcpyAsset == null) logger.warn("No bundled scrcpy release for $hostOs/$hostArch: building without device mirroring")
else {
val scrcpyName = "scrcpy-${scrcpyAsset.first}-v$scrcpyVersion"
val scrcpyArchive = layout.buildDirectory.file("scrcpy/$scrcpyName.${if (scrcpyAsset.first.startsWith("win")) "zip" else "tar.gz"}")
val downloadScrcpy = tasks.register("downloadScrcpy") {
    outputs.file(scrcpyArchive)
    // Task actions capture only these locals, never the build script, so the configuration cache can store them.
    val archiveFile = scrcpyArchive
    val releaseUrl = "https://github.com/Genymobile/scrcpy/releases/download/v$scrcpyVersion"
    val checksum = scrcpyAsset.second
    doLast {
        val archive = archiveFile.get().asFile
        archive.parentFile.mkdirs()
        val download = archive.resolveSibling("${archive.name}.part")
        URI("$releaseUrl/${archive.name}")
            .toURL().openStream().use { input -> download.outputStream().use(input::copyTo) }
        val digest = MessageDigest.getInstance("SHA-256").digest(download.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == checksum) {
            download.delete()
            "scrcpy archive checksum mismatch"
        }
        Files.move(download.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
// Linux mirroring decodes the phone's screen with a small libavcodec built from FFmpeg's source; scrcpy's Windows
// release already ships its DLLs. The libraries go into the scrcpy bundle, where the decoder loads them from.
val ffmpegLibraries = layout.buildDirectory.dir("ffmpeg/${scrcpyAsset.first}")
val buildFfmpeg = tasks.register<Exec>("buildFfmpeg") {
    val script = layout.projectDirectory.file("packaging/linux/build-ffmpeg.sh")
    inputs.file(script)
    outputs.dir(ffmpegLibraries)
    commandLine("bash", script.asFile.absolutePath, ffmpegLibraries.get().asFile.absolutePath, "x86_64")
}
val scrcpyStaging = layout.buildDirectory.dir("scrcpy/staged")
val macSigningIdentity = appEnvironment("ROBOMORI_MAC_SIGN_IDENTITY").orElse("")
val stageScrcpy = tasks.register<Sync>("stageScrcpy") {
    dependsOn(downloadScrcpy)
    from(if (scrcpyAsset.first.startsWith("win")) zipTree(scrcpyArchive) else tarTree(resources.gzip(scrcpyArchive))) {
        include("$scrcpyName/scrcpy")
        include("$scrcpyName/scrcpy.exe")
        include("$scrcpyName/scrcpy-server")
        include("$scrcpyName/*.dll")
        include("$scrcpyName/LICENSE*")
    }
    if (hostOs.startsWith("Linux")) {
        dependsOn(buildFfmpeg)
        from(ffmpegLibraries) { into(scrcpyName) }
    }
    into(scrcpyStaging)
    inputs.property("macSigningIdentity", macSigningIdentity)
    val signOnMac = hostOs.startsWith("Mac")
    val staging = scrcpyStaging
    val executablePath = "$scrcpyName/scrcpy"
    val identity = macSigningIdentity
    doLast {
        if (signOnMac && identity.get().isNotEmpty()) {
            val executable = staging.get().file(executablePath).asFile
            val signing = ProcessBuilder("/usr/bin/codesign", "--force", "--options", "runtime", "--timestamp",
                "--sign", identity.get(), executable.absolutePath).inheritIO().start()
            check(signing.waitFor() == 0) { "Could not sign the bundled screen recorder." }
        }
    }
}
val bundleScrcpy = tasks.register<Zip>("bundleScrcpy") {
    dependsOn(stageScrcpy)
    from(scrcpyStaging)
    archiveFileName.set("scrcpy-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("scrcpy/bundle"))
}
tasks.processResources {
    dependsOn(bundleScrcpy)
    from(bundleScrcpy.flatMap { it.archiveFile }) { into("scrcpy") }
}
}
tasks.processResources {
    val version = buildVersion
    inputs.property("buildVersion", version)
    filesMatching("robomori-build.properties") {
        expand("buildVersion" to version)
    }
}

// Build-only compiler: the packaged helper contains our code, not R8 or an Android SDK.
val deviceHelperCompiler by configurations.creating
dependencies { deviceHelperCompiler("com.android.tools:r8:9.5.28") }
// Signatures of the Android classes the helper calls, to compile against; the device provides the real ones.
val compileDeviceHelperStubs = tasks.register<JavaCompile>("compileDeviceHelperStubs") {
    source("device-helper/stubs")
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("device-helper/stubs"))
    options.release.set(8)
    javaCompiler.set(javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) })
}
val compileDeviceHelper = tasks.register<JavaCompile>("compileDeviceHelper") {
    source("device-helper/src")
    classpath = files(compileDeviceHelperStubs.flatMap { it.destinationDirectory })
    destinationDirectory.set(layout.buildDirectory.dir("device-helper/classes"))
    options.release.set(8)
    javaCompiler.set(javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) })
}
val deviceHelperClasses = tasks.register<Jar>("deviceHelperClasses") {
    from(compileDeviceHelper.flatMap { it.destinationDirectory })
    archiveFileName.set("device-helper-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("device-helper"))
}
val deviceHelperJar = layout.buildDirectory.file("device-helper/device-helper.jar")
val dexDeviceHelper = tasks.register<JavaExec>("dexDeviceHelper") {
    dependsOn(deviceHelperClasses)
    classpath = deviceHelperCompiler
    mainClass.set("com.android.tools.r8.D8")
    inputs.file(deviceHelperClasses.flatMap { it.archiveFile })
    outputs.file(deviceHelperJar)
    val output = deviceHelperJar
    val input = deviceHelperClasses.flatMap { it.archiveFile }
    doFirst {
        args("--min-api", "24", "--output", output.get().asFile.absolutePath, input.get().asFile.absolutePath)
    }
}
tasks.processResources {
    dependsOn(dexDeviceHelper)
    from(deviceHelperJar) { into("device-helper") }
}

protobuf { protoc { artifact = "com.google.protobuf:protoc:3.25.9" } }
kotlin { jvmToolchain { languageVersion = JavaLanguageVersion.of(25); vendor = JvmVendorSpec.JETBRAINS } }

dependencies {
    implementation(compose.desktop.currentOs) {
        // Exclude Material from this app's UI, not from Hot Reload's separate developer tools.
        exclude(group = "org.jetbrains.compose.material")
        exclude(group = "org.jetbrains.compose.material3")
        exclude(group = "androidx.compose.material")
        exclude(group = "androidx.compose.material3")
    }
    implementation(project(":codepush"))
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nucleus.updater.runtime)
    implementation(libs.nucleus.aot.runtime)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.snakeyaml)
    implementation(libs.compose.unstyled.button)
    implementation(libs.compose.unstyled.dialog)
    implementation(libs.compose.unstyled.drawer)
    implementation(libs.compose.unstyled.dropdown.menu)
    implementation(libs.compose.unstyled.focus.ring)
    implementation(libs.compose.unstyled.progress)
    implementation(libs.compose.unstyled.slider)
    implementation(libs.compose.unstyled.text.field)
    implementation(libs.compose.unstyled.theming)
    implementation(libs.compose.unstyled.toggle.switch)
    implementation(libs.compose.unstyled.tooltip)
    implementation(libs.ripple.indication)
    implementation(libs.filekit.dialogs)
    implementation(libs.jna)
    implementation(libs.jcodec)
    implementation(libs.zxing.core)
    implementation(libs.simple.analytics)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.brotli.dec)
    implementation(libs.aircompressor)
    implementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
// The device helper's screen logic, such as refs and diffs, is tested on the computer against the same stand-ins
// it compiles with.
sourceSets.test { java.srcDirs("device-helper/src", "device-helper/stubs") }

tasks.test {
    // Opt-in localhost checks depend on external service state, not just test inputs.
    outputs.upToDateWhen { !appEnvironment("ROBOMORI_LOCAL_TEST_KEY_FILE").isPresent }
    outputs.doNotCacheIf("Local licensing integration is enabled") {
        appEnvironment("ROBOMORI_LOCAL_TEST_KEY_FILE").isPresent
    }
    inputs.property("installedSdkSmoke", appEnvironment("ROBOMORI_TEST_INSTALLED_SDK").orElse("false"))
    inputs.property("imageInstallSmoke", appEnvironment("ROBOMORI_TEST_IMAGE_INSTALL").orElse("false"))
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("java.awt.headless", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
// Development runs let Spectre attach to the app and operate it. Packaged releases leave this out.
tasks.withType<JavaExec>().matching { it.name == "run" || it.name.startsWith("hotRun") }.configureEach {
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
nucleus {
    application {
        // The packaged app starts through codepush, which runs com.robomori.MainKt from the newest healthy code update.
        mainClass = "com.composables.codepush.CodePushLauncher"
        javaHome = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.JETBRAINS
        }.get().metadata.installationPath.asFile.path
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // Java prints startup (AOT) cache problems to standard output, which would land in the robomori command's
        // results, such as in front of its JSON. A cache that can't be used only costs startup time, so keep them quiet.
        jvmArgs += "-Xlog:aot=off"
        if (System.getProperty("os.name").startsWith("Mac")) {
            jvmArgs += listOf("-Dapple.awt.application.name=Robomori", "-Dapple.awt.application.appearance=NSAppearanceNameAqua")
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            // The robomori command runs this one: the app's own launcher is a windowed program that can't print to a terminal.
            // It inherits the app's main class and options.
            additionalLaunchers { create("robomori-cli") { winConsole = true } }
        }
        nativeDistributions {
            targetFormats(*when {
                System.getProperty("os.name").startsWith("Mac") -> arrayOf(TargetFormat.Dmg, TargetFormat.Zip)
                System.getProperty("os.name").startsWith("Windows") -> arrayOf(TargetFormat.Nsis)
                else -> arrayOf(TargetFormat.AppImage)
            })
            packageName = "Robomori"
            packageVersion = buildVersion
            // Packaging runs the app once to record what it loads at startup, so later launches start faster.
            // The cache only works with the JDK and platform it was built on, so each platform builds its own.
            enableAotCache = true
            windows {
                iconFile.set(project.file("packaging/app-icon.ico"))
                nsis { oneClick = false; allowToChangeInstallationDirectory = true; runAfterFinish = false }
            }
            linux {
                iconFile.set(project.file("packaging/app-icon.png"))
                packageName = "robomori"
                appCategory = "Development"
                debMaintainer = "Alex Styl"
            }
            description = "The companion app for Android developers and AI coding agents"
            vendor = "Alex Styl"
            modules("java.net.http", "java.prefs", "java.sql", "java.xml", "jdk.security.auth", "jdk.unsupported")
            publish {
                publishMode = PublishMode.Never
            }
            macOS {
                bundleID = "com.alexstyl.robomori"
                iconFile.set(project.file("packaging/app-icon.icns"))
                minimumSystemVersion = "12.0"
                packageBuildVersion = macBuildNumber
                // Emulators launched by Robomori use the Mac's camera and microphone.
                entitlementsFile.set(project.file("packaging/macos.entitlements"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSCameraUsageDescription</key>
                        <string>Android emulators use your camera as their front and back cameras.</string>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Android emulators use your microphone as their microphone.</string>
                    """.trimIndent()
                }
                signing {
                    sign.set(appEnvironment("ROBOMORI_MAC_SIGN_IDENTITY").isPresent)
                    identity.set(appEnvironment("ROBOMORI_MAC_SIGN_IDENTITY"))
                }
                notarization {
                    keychainProfile.set(appEnvironment("ROBOMORI_NOTARY_PROFILE"))
                    keychainPath.set(appEnvironment("ROBOMORI_NOTARY_KEYCHAIN"))
                }
            }
        }
    }
}

// Nucleus signs the app bundle; also sign the disk image before notarizing it.
tasks.matching { it.name == "packageDmg" }.configureEach {
    val identity = appEnvironment("ROBOMORI_MAC_SIGN_IDENTITY")
    inputs.property("diskImageSigningIdentity", identity.orElse(""))
    doLast {
        if (identity.isPresent) {
            val images = layout.buildDirectory.dir("compose/binaries/main/dmg").get().asFile.listFiles().orEmpty().filter { it.extension == "dmg" }
            check(images.size == 1) { "Expected exactly one DMG to sign" }
            providers.exec { commandLine("/usr/bin/codesign", "--force", "--timestamp", "--sign", identity.get(), images.single().absolutePath) }.result.get().assertNormalExitValue()
        }
    }
}

// Exercise the real command-line entry point without starting Compose or opening a window.
tasks.register<JavaExec>("platformDiagnostics") {
    group = "verification"
    description = "Run application diagnostics headlessly on the current host"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.robomori.MainKt")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.JETBRAINS
    })
    systemProperty("java.awt.headless", "true")
    args("--diagnostics")
}
