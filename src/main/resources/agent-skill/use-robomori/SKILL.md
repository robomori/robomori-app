---
name: use-robomori
description: Create, start, control and stop Android emulators and devices with the robomori command, and operate the apps on them. Use for any Android task that needs an emulator or device, such as running or testing an app, reading the screen, tapping, typing, swiping and navigating through apps, reading an app's logs and crashes, reading and changing an app's files, databases and preferences, recording its network traffic and mocking API responses, checking UI changes, testing dark mode, font size, language or TalkBack, simulating GPS location, taking screenshots or screen recordings, or running several emulators in parallel. Use it instead of Android Studio, avdmanager, the emulator command, and the adb commands it replaces.
---

# Robomori

`robomori` creates and starts Android emulators, changes the state of running emulators and phones, and operates the
apps on them: it reads the screen as text and taps, types, swipes and presses keys in milliseconds, and shows what an
app logged and why it crashed. Use it to get a device ready, set it up, drive it and capture what's on it. Use `adb`
and Gradle only for what Robomori doesn't do, such as building apps.

Check it's installed with `robomori version`. If it isn't, ask the user to install it from the Robomori app:
Settings → CLI & Agents. Get the app at https://robomori.com.

## Rules

- Add `--json` when you read the output. Results go to standard output, progress and errors to standard error.
- Exit code 0 means it worked, 1 that it failed, 2 that the command was typed wrong. Errors name the command to run
  next; follow them.
- Pass `--device <name or serial>` whenever more than one device is running and you haven't claimed one. With one
  running, leave it out.
- If other agents may be working on this computer, claim your own device first (see "Work in parallel").
- Don't stop, wipe or delete emulators you didn't create. They're the user's.
- Robomori is free for 7 days from its first run, then needs a license. If a command says the free trial has ended,
  tell the user they can buy a license at https://robomori.com/pricing and activate it in the Robomori app; don't
  work around it with adb.

## Use robomori, not adb or the SDK tools

| Instead of | Run |
| --- | --- |
| `adb devices`, `emulator -list-avds` | `robomori device list` |
| `avdmanager create avd`, `sdkmanager` for system images | `robomori emulator create` |
| `emulator -avd …`, then waiting for `sys.boot_completed` | `robomori emulator start` |
| `adb emu kill` | `robomori emulator stop` |
| `emulator -wipe-data` | `robomori emulator wipe` |
| `adb reboot` | `robomori device restart` |
| `adb shell uiautomator dump`, screenshots to find what's on screen | `robomori screen read` |
| `adb shell input tap`, `input text`, `input swipe` | `robomori input tap`, `type`, `swipe`, `scroll`, `drag`, `zoom` |
| `adb shell input keyevent` | `robomori input press` |
| `adb shell am start`, `am force-stop`, `pm clear` | `robomori app launch`, `app close`, `app launch --clear-data` |
| `adb install`, `adb uninstall`, `pm list packages` | `robomori app install`, `app uninstall`, `app list` |
| `adb logcat` | `robomori app logs` |
| `adb shell ls`, `adb pull`, `adb push`, `run-as <package> cat` | `robomori files list`, `file pull`, `file push`, `file read` |
| `run-as <package> sqlite3`, pulling a database to read it | `robomori data list`, `data query` |
| A proxy such as mitmproxy, or Charles, to see or mock requests | `robomori network record`, `network mock`, `network rewrite` |
| `adb shell settings put system user_rotation …` | `robomori device rotate` |
| `adb shell cmd uimode night …` | `robomori device controls dark-mode` |
| `adb shell settings put system font_scale …` | `robomori device controls font-scale` |
| Changing the locale through `adb shell` | `robomori device controls locale` |
| `adb shell settings put secure enabled_accessibility_services …` | `robomori device controls talkback` |
| `adb shell settings put global *_animation_scale …` | `robomori device controls animations` |
| `adb emu geo fix` | `robomori device location set` |
| `adb exec-out screencap` | `robomori screen shot` |
| `adb shell screenrecord` (3-minute limit) | `robomori screen record` (no limit) |

## Get a device

```sh
robomori device list --json                               # what exists and what's running
robomori emulator create pixel-10 --start --json          # a new phone, booted; prints its name and serial
robomori emulator start Pixel_10                          # start an existing one, wait for boot; prints its serial
robomori emulator open Pixel_10                           # also show it in a window, so the user can watch
```

`emulator create` takes a profile from `robomori profile list`: phones (`pixel-10`, `pixel-10-pro`), a foldable
(`pixel-10-pro-fold`), a tablet (`pixel-tablet`), watches and TV. `--api 36` picks the Android version; see
`robomori image list --profile <profile>`. Phones, tablets and foldables get Google APIs images, which let Robomori read
every app's HTTPS; add `--play-store` for one with the Play Store. Robomori gives each emulator 2 GB of
memory, so several can run at once.

Emulators started by `robomori` keep running after the command returns and show up in the Robomori app. The serial,
such as `emulator-5554`, is what `adb -s` takes.

## Work in parallel

When several agents work on one computer, each in its own git worktree, claim a device so no other agent installs
over or taps on yours:

```sh
export ANDROID_SERIAL=$(robomori device claim)            # this worktree's emulator; same one every time you ask
robomori device claim --no-animations                     # also turn animations off until release: steps are faster
```

- `claim` returns the device this worktree already claimed, or else a running emulator no one claimed. It never starts
  one: if it says none is free, start or create an emulator, then claim again.
- With `ANDROID_SERIAL` exported, adb, Gradle and `robomori` all use your device without `--device`. Robomori refuses
  devices other worktrees claimed; never take one over with `--force` unless the user asks.
- `robomori device release` ends the claim and puts back the settings you changed, such as dark mode, text size,
  language and rotation. The claim also ends when the emulator stops or restarts; claim again after restarting it.
- `robomori device list` shows who claimed each device.
- If `device claim` is an unknown command, Robomori is older: ask the user to update it, and meanwhile create an
  emulator for your task and pass its serial with `--device`.

For one-off jobs, such as trying a third-party app or signing in to an account, create a dedicated emulator rather
than reusing the user's.

## Set the device up

```sh
robomori device controls --json                           # current values
robomori device controls dark-mode on
robomori device controls font-scale 1.3                   # 0.85, 1, 1.15, 1.3, 1.5, 1.8, 2
robomori device controls locale fr-FR
robomori device controls talkback on
robomori device controls animations off                   # steadier UI tests and screenshots
robomori device controls keyboard on                      # show the on-screen keyboard
robomori device location set 37.4220,-122.0841
robomori device location clear
robomori preset apply "French, large text"                # a preset the user saved in the app
```

## Operate apps

Read the screen as text and act on what it lists. Every action then prints what changed on the screen once it settles,
so you rarely need to read it again. Read text, not screenshots: it's faster and exact. Take a screenshot when how
something looks matters.

```sh
robomori screen read                                      # the app showing, then one line per element
#   @4 switch "Wi-Fi" on (900,600)
#   @5 list more ↓ [0,300,1080,1800]
robomori input tap @4                                     # an @ref, text on screen, or x,y; then what changed
robomori input tap "Sign in"                              # text matching several things fails and lists their refs
robomori input tap 7                                      # a plain number is text, such as a key on a dial pad
robomori input tap @7 --long                              # long press; --times 2 for a double tap
robomori input type "ada@example.com" --into Email --submit   # --clear replaces the field's text
robomori input type -- "-v"                               # text that starts with a dash goes after --
robomori input scroll down --until "About phone"          # scrolls the largest list until the text shows
robomori input swipe @5 left                              # from an element or x,y toward a direction, or to a point
robomori input swipe 790,960 540,1210 290,960 --duration 800ms   # a path through several points
robomori input drag "Item 3" @9                           # pick up, move and drop: reorder lists, move icons
robomori input zoom in                                    # pinch with two fingers; zoom out too
robomori input press back                                 # also home, recents, enter, delete, tab, a letter or digit
robomori input press ctrl+a                               # modifiers: shift, ctrl, alt, meta
robomori input press power --long                         # hold a button
robomori input press notifications                        # also quick-settings, power-menu, lock
robomori screen wait "Welcome back" --timeout 20s         # or --gone
robomori device clipboard                                 # read it; or clipboard "text" to set it
```

- Actions print `+` before what's new or different, `-` before what's gone, the whole screen when it's mostly new,
  and `No change.` when nothing changed. Add `--quiet` to only act.
- Refs stay the same while an element stays on screen. After the screen changes, act on the refs the latest output
  printed. Refs always start with `@`.
- Toasts show as `toast "Saved"`. When an action makes the app crash, the output says so with the exception and the
  line in the app's code; `robomori app logs <package>` shows the whole stack trace.
- `tap`, `type`, `press`, `scroll` and `swipe` also work without `input`, such as `robomori tap @4`.
- Times take `500ms`, `30s` or `2m`; a plain number is seconds.
- The first command starts Robomori's agent on the device, which holds its UI automation until 30 seconds after the
  last command. Espresso or UI Automator tests and `uiautomator` work again after that.
- If `robomori screen read` is an unknown command, Robomori is older: ask the user to update it, and meanwhile use
  `adb shell uiautomator dump` and `adb shell input`.

### For agents with MCP

`robomori mcp` serves the same commands as MCP tools, each returning what changed on the screen, and claims its own
emulator for its worktree. Suggest it to the user when an agent will drive a device for long:
`claude mcp add robomori -- robomori mcp`.

## Apps

```sh
robomori app install app/build/outputs/apk/debug/app-debug.apk --grant   # keeps data; --grant skips permission prompts
robomori app launch com.example.app                       # opens as its icon would, waits until it shows
robomori app launch com.example.app --restart             # start over instead of returning to where it was
robomori app launch com.example.app --clear-data          # as if just installed, to test a first run
robomori app launch "myapp://orders/42"                   # links and deep links open in whatever app handles them
robomori app close com.example.app                        # force stop
robomori app logs com.example.app                         # warnings, errors and crashes since you last looked
robomori app logs com.example.app --since 5m --all        # the last 5 minutes, every level
robomori app list                                         # the packages the launcher shows
robomori app uninstall com.example.app
```

Settings is `com.android.settings`; Chrome is `com.android.chrome`.

## Files and app data

An app's own files are under `/data/data/<package>`. Robomori opens a debug build's, and every app's on emulators
without Google Play.

```sh
robomori files list /data/data/com.example.app/files      # a folder; /sdcard by default
robomori files read /data/data/com.example.app/shared_prefs/settings.xml
robomori files pull /sdcard/Download/report.pdf -o report.pdf
robomori files push fixture.json /data/data/com.example.app/files
robomori data list com.example.app                        # its databases, tables with row counts, and preferences
robomori data query com.example.app app.db "SELECT * FROM users" --json
robomori data query com.example.app app.db "UPDATE users SET premium = 1"   # changes the running app's data
robomori data prefs com.example.app settings              # SharedPreferences or DataStore, key, type and value
```

## Network

```sh
robomori network record --app com.example.app --duration 30s -o requests.har   # each request as it finishes
robomori network mock 'api.example.com/v1/users*' --body '[]'                   # answer without the server
robomori network rewrite 'api.example.com/v1/orders' --status 500               # change the server's answer
robomori network rules                                                           # list; --off turns them all off
robomori network delete-rule <id>
```

Read the HAR file for request and response bodies. Rules stay until deleted and apply on every device, in the app's
Network page too, so delete the ones you add when you're done. Close the app's Network page for the device before
`network record`: only one can record it.

## Change the hardware

```sh
robomori device rotate                                    # clockwise; or counterclockwise
robomori emulator fold Pixel_10_Pro_Fold                  # and unfold
robomori device restart
```

## Capture

```sh
robomori screen shot -o screen.png --size 540             # then look at the image; --size costs fewer tokens
robomori screen record --duration 30s -o flow.mp4
robomori screen shot --clean -o store.png                 # 9:41 and a clean status bar, for store listings
```

## Never

- Enter the user's passwords or sign in to their accounts. Show the emulator with `robomori emulator open` and ask
  the user to sign in.
- Install APKs from third-party mirror sites. Get apps from Google Play on the emulator, or build them from source.

## When done

Delete the network rules you added with `robomori network delete-rule <id>`. Release a claimed device with
`robomori device release`. Stop the emulators you started with `robomori emulator stop
<emulator>`, and delete the ones you created for a task with `robomori emulator delete <emulator>`, unless the user
wants to keep them.

## More

`robomori help <command>` shows every option of a command. Full docs: https://robomori.com/docs/cli.md
