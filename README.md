<img src="https://robomori.com/og.jpg" alt="Robomori: the companion app for Android developers and AI coding agents, showing a Pixel 10 Pro Fold emulator next to the Network page listing its app's requests" />

# Robomori

Run Android emulators and phones next to your code, with their network traffic, logs, database and files one click
away, for you and your AI coding agents. Native on Mac, Windows and Linux.

[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

## Features

- **Inspect your Android app's HTTP requests:** see each request with its headers and body, with no code change.
  Read every app's HTTPS on rooted devices, mock responses and errors, and export it all as a HAR file.
- **Logcat without Android Studio:** filter by app, tag or level. Crashes stand out in red, and a click on a stack
  trace line opens that file in your IDE.
- **View your app's SQLite and Room database:** browse its tables like a spreadsheet, run your own SQL, and change
  its SharedPreferences and DataStore values while the app runs.
- **Give Claude Code, Codex or Cursor an Android emulator:** the `robomori` command and its MCP server let coding
  agents start an emulator, tap through the app they built, and read its logs, requests and database. Each agent
  gets its own emulator, so several can work at once.
- **Reach localhost from an Android emulator or phone:** apps reach the servers on your computer at `localhost`. No
  `10.0.2.2`, no `adb reverse`.
- **Use your webcam and microphone in the Android emulator:** test video calls, QR scanning and voice input.
- **Reproduce bug reports:** set a device's language, location, dark mode, font size and TalkBack, save it as a
  preset and replay it on any emulator or phone.
- **Every kind of Android device:** phones, tablets, foldables, watches and TVs, with the right buttons for each.
  Pick a device and an Android version, and Robomori downloads the system image and boots it.
- **Mirror your phone over USB or Wi-Fi:** pair with a QR code, type with your keyboard, and take Play Store-ready
  screenshots and screen recordings.

## Installation

Download the installer for Mac, Windows or Linux from [robomori.com/download](https://robomori.com/download), or
install on a Mac with Homebrew:

```bash
brew install robomori/tap/robomori
```

To build it from source instead, you need a JDK to start Gradle, which downloads the JetBrains Runtime the app runs
on. Build the app for your computer with Gradle's `createDistributable` task:

```bash
./gradlew createDistributable
```

The app is in `build/compose/binaries/main/app/`. On Windows, use `.\gradlew.bat`.

## Quick start

Install the `robomori` command from Robomori's Settings → CLI & Agents → Install. Then create a Pixel emulator with
the newest Android version and boot it, with `robomori emulator create --start`:

```bash
robomori emulator create --start
```

Robomori downloads the Android image if needed, boots the emulator, and prints its name. It shows up in the app,
where you can open its network traffic, logs and database.

## Set up the Android SDK

Robomori runs emulators with the Android SDK's tools: the emulator, adb and the command-line tools. It doesn't ship
them, so it needs to know where your SDK is. On first launch it finds an existing SDK, such as Android Studio's, or
points you to where to get one. To use a different SDK later, change it in Settings → SDK Locations.

## Examples

### Give Claude Code an Android emulator

Let Claude Code start emulators, read the screen and tap through your app, by adding the `robomori mcp` server:

```bash
claude mcp add robomori -- robomori mcp
```

The first tool the agent uses claims a free emulator for its worktree, so agents working in parallel each get their
own.

### Read your Android app's crash logs from the terminal

See the warnings, errors and crash stack traces your app logged, by passing its package to `robomori app logs`:

```bash
robomori app logs com.example.app
```

Each run shows only what's new since the last one; the first run shows the last 2 minutes.

### Save your Android app's HTTP requests as a HAR file

Record a device's traffic, with each request's headers and bodies, by passing an output file to
`robomori network record`:

```bash
robomori network record --app com.example.app -o requests.har
```

It prints each request as it finishes, and stops with Ctrl+C.

## Platform notes

- **Windows and Linux:** emulators need hardware acceleration, through Windows' hypervisor support or KVM on Linux.

## Support Robomori

👋 Hi, I'm [Alex Styl](https://x.com/alexstyl). Even though Robomori is open-source, I am selling the pre-built binaries to compensate for the time spent in designing, building and supporting new versions.

If Robomori saves you time, kindly consider [buying a license](https://robomori.com/pricing) for you and your team.
