![A desktop app's version going from 1.4.0 to 1.4.0+1 after a relaunch, with no installer]()

_Demo idea: publish a code update from the terminal, relaunch the app, and watch its version change from 1.4.0 to 1.4.0+1._

# codepush

Ship code changes to your installed JVM desktop app in about a minute, without building, signing or notarizing a new installer.

## Features

- **Updates in a minute:** one command signs your app's jars, instead of packaging an installer for every platform. Installed apps download only the jars they don't already have.
- **One update for every platform:** Mac, Windows and Linux run the same Java code, so one code update reaches all of them.
- **Silent and safe to apply:** updates download in the background and run from the next launch, never mid-session.
- **Automatic rollback:** an update that crashes before the app is up is marked bad, and the app goes back to its last good code.
- **Signed with your key:** every update is checked against an Ed25519 public key built into the app, so nobody else can push code to your users.
- **Works next to your installer updates:** code updates apply to one installed version, so a full update replaces them cleanly.
- **Fast launches on an update:** only your own jars load separately; libraries keep using the installed app's startup cache, so an update adds tens of milliseconds at launch.
- **License-aware:** each update's publish time is signed, so your app can offer only updates its license covers.
- **No dependencies:** only the JDK and the Kotlin standard library, with no UI and no framework assumptions.

## Installation

Add the module to your Gradle build and depend on it from your app:

```kotlin
// settings.gradle.kts
include(":codepush")

// build.gradle.kts
dependencies {
    implementation(project(":codepush"))
}
```

## Quick start

Run your app through codepush, by making `CodePushLauncher` the packaged app's main class and naming your real main class in a `codepush.properties` resource:

```kotlin
// build.gradle.kts (Compose Desktop or Nucleus)
application {
    mainClass = "com.composables.codepush.CodePushLauncher"
}
```

```properties
# src/main/resources/codepush.properties
appId=com.example.app
mainClass=com.example.MainKt
updatesUrl=https://downloads.example.com/code/
publicKey=MCowBQYDK2VwAyEA…
```

Then confirm each launch and download new updates, by calling `markHealthy()` once your app is up and `downloadLatest()` in the background:

```kotlin
val codePush = CodePushConfig.load()?.let(::CodePushClient)

// Once the main window is showing:
withContext(Dispatchers.IO) {
    codePush?.markHealthy()
    while (isActive) {
        runCatching { codePush?.downloadLatest() }
        delay(6.hours)
    }
}
```

The packaged app now runs the newest healthy code update for its installed version, or the installed code when there is none. `CodePushConfig.load()` returns null while you develop, because the app isn't packaged, so the same code runs safely from your IDE.

## Set up a signing key

Installed apps only run updates signed by your key, so codepush needs a key pair before you can publish. The private key signs updates and stays with you. The public key goes into `codepush.properties`.

Create a key pair with the publisher's `keygen` command, which prints the private key and then the public key:

```sh
./gradlew :codepush:run --args="keygen"
```

Keep the private key somewhere safe, such as the macOS Keychain. Anyone holding it can push code to every installed copy of your app.

## Set up the updates server

Installed apps download updates over HTTPS from `updatesUrl`, so codepush needs a place to host static files, such as an S3 or Cloudflare R2 bucket behind a domain. The publisher writes a folder with the server's layout, ready to upload as it is:

```
<appId>/<base>/latest                 newest update number for installed version <base>
<appId>/<base>/<patch>/manifest       the update: which jars it replaces
<appId>/<base>/<patch>/manifest.sig   its signature
<appId>/files/<sha256>                the jars, stored by content
```

Upload `latest` last and without long caching: it's what makes an update visible to installed apps.

## Examples

### Publish a code update

Ship new code to everyone on version 1.4.0, by signing your rebuilt jars as update 1 with `publish`. Each `MODULE=JAR` pair names the installed jar to replace by the start of its file name:

```sh
CODEPUSH_PRIVATE_KEY="$(security find-generic-password -s my-app-codepush -w)" \
  ./gradlew :codepush:run --args="publish --out build/codepush --app com.example.app --base 1.4.0 --patch 1 \
    MyApp=build/libs/MyApp-1.4.0.jar analytics=analytics/build/libs/analytics-1.0.0.jar"
```

Upload `build/codepush` to your updates server. Installed 1.4.0 apps download update 1 on their next check and run it from their next launch. Number each update one higher than the last for that version.

### Show the running update in your version number

Tell users and your analytics which code is running, by adding `runningPatch` to your version:

```kotlin
val displayVersion = APP_VERSION + (codePush?.runningPatch?.let { "+$it" } ?: "")
// 1.4.0+1
```

Keep comparing the plain version, without `+N`, wherever you check for full installer updates.

### Try an update before you ship it

Point an installed build at a local folder before uploading anything, by setting the `codepush.updatesUrl` system property:

```sh
python3 -m http.server 8765 --directory build/codepush &
JAVA_TOOL_OPTIONS="-Dcodepush.updatesUrl=http://127.0.0.1:8765/" /Applications/MyApp.app/Contents/MacOS/MyApp
```

The app downloads the update from your machine; relaunch it to run the update.

### Only deliver updates a license covers

Give updates only to users whose update period includes them, by passing a rule on the publish time to `downloadLatest`. If the newest update is too new, codepush takes the newest one the rule accepts:

```kotlin
codePush.downloadLatest { published -> !published.isAfter(license.updatesUntil) }
```

The publish time is part of the signed manifest, so it can't be changed after publishing.

### Keep updates in your app's data folder

Store downloaded updates next to your app's other data, by naming the folder with `dataFolder`. By default codepush uses a folder named after `appId`:

```properties
dataFolder=MyApp
```

Updates then live in `~/Library/Application Support/MyApp/codepush` on macOS, `%APPDATA%\MyApp\codepush` on Windows and `~/.local/share/MyApp/codepush` on Linux.

## What a code update can change

A code update swaps the jars of your own modules. Everything else stays as installed, so these changes need a full installer release:

- dependency versions or new libraries
- the Java runtime, JVM options or packaging settings
- native libraries, app permissions or entitlements, such as camera access on macOS
- codepush itself, which runs before any update loads

Your own classes from an update load without your packaged app's startup (AOT) cache. Libraries still use it, so launches with an update are a few tens of milliseconds slower until the next full release.

## Troubleshooting

**The app never picks up an update.** Check that the update's `base` matches the installed version exactly, that `latest` on the server holds the new number, and that the app ran long enough to download it before you relaunched.

**An update was rolled back.** The app didn't reach `markHealthy()` on the update's first launch, usually because it crashed at startup. codepush records it as bad and never runs it again; fix the crash and publish a new update number.

**Downloads fail with a signature error.** The update was signed with a different key from the `publicKey` in the app. Sign it with the private key that matches the installed app.
