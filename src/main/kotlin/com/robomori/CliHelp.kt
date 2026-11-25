package com.robomori

/** What `robomori help` prints: an overview, each group's commands, and each command's usage and examples. */
internal object CliHelp {
    private class Command(val usage: String, val summary: String, val details: String = "")

    private val emulator = "<emulator>"
    private val deviceOption = "[--device <device>]"

    private val all: Map<String, Command> = linkedMapOf(
        "device list" to Command("robomori device list [--emulators | --physical] [--long]",
            "List devices: running and stopped emulators, and connected physical devices",
            """
            Shows each device's name, kind (emulator or physical), Android version and state. The name is what
            --device and the emulator commands take.

            Options:
              --emulators   Only emulators
              --physical    Only physical devices
              --long        Add the display name, type and serial

            Examples:
              robomori device list
              robomori device list --emulators --json
            """),
        "device restart" to Command("robomori device restart $deviceOption [--no-wait]",
            "Restart a running emulator or physical device",
            """
            Restarts Android and waits until it has booted again, then prints the serial.

            Options:
              --no-wait   Return as soon as the restart has begun.
            """),
        "device claim" to Command("robomori device claim [<device>] [--force] [--no-animations]",
            "Claim a running device for this worktree",
            """
            Gives each coding agent working in parallel its own device, named as <device> or with --device. Without one,
            the device this git worktree already claimed, or else a running emulator no one claimed. Never starts or
            creates one, and never picks a physical device unless you name it. Prints the serial.

            Robomori commands run in this worktree then use the claimed device without --device, and refuse devices
            other worktrees claimed. Export ANDROID_SERIAL with the serial so adb, Gradle and Robomori all use it. A
            claim ends with 'robomori device release', or when the device stops or restarts.

            Releasing puts back the controls the claim found, such as dark mode, text size and language, and the way the
            screen was turned, so the next agent or person finds the device as it was. Apps and their data stay.


            Options:
              --force           Take over a device another worktree claimed.
              --no-animations   Turn the emulator's animations off until it's released, which makes each step
                                an agent takes about twice as fast. Leave them on when the work involves motion,
                                such as a transition.

            Examples:
              export ANDROID_SERIAL=${'$'}(robomori device claim)
              robomori device claim Pixel_Tablet
            """),
        "device release" to Command("robomori device release [<device>]", "Give back the devices this worktree claimed",
            """
            Without <device>, releases every device this worktree claimed. Prints the released devices.
            """),
        "emulator create" to Command("robomori emulator create [<profile>] [--name <name>] [--api <level>] [--play-store] [--start]",
            "Create an emulator",
            """
            <profile> is the hardware to copy, from 'robomori profile list'. Without it, the newest Pixel phone.

            Options:
              --name <name>   The name shown in the app. Without it, one is picked, such as "Pixel 10 (2)".
              --api <level>   The Android version, such as 37. Without it, an installed image, or else the newest
                              compatible one. See 'robomori image list --profile <profile>'.
              --play-store    Use an image with the Play Store. Phones, tablets and foldables otherwise get one with
                              Google APIs, where the Network tab can read every app's HTTPS; with the Play Store, only
                              your own debug apps'.
              --start         Also start it and wait until Android has booted.

            Downloads the Android image first if needed. Prints the new emulator's name.

            Examples:
              robomori emulator create
              robomori emulator create pixel-10 --api 36
              robomori emulator create pixel-tablet --name "QA tablet" --start --json
            """),
        "emulator start" to Command("robomori emulator start $emulator [--cold] [--no-wait]",
            "Start an emulator and wait until Android has booted",
            """
            Prints the emulator's serial, such as emulator-5554, for adb. An emulator that's already running just
            prints its serial. Emulators started here keep running and show up in the Robomori app.

            Options:
              --cold      Start from scratch instead of the saved snapshot.
              --no-wait   Return as soon as it's launched.

            Example:
              robomori emulator start Pixel_10
            """),
        "emulator open" to Command("robomori emulator open $emulator", "Show an emulator in its own window",
            """
            Opens the emulator in a Robomori window, starting the app if it's closed, and starts the emulator if it isn't
            running. Each emulator gets its own window, so several can be open side by side. Prints the emulator's
            serial.

            Example:
              robomori emulator open Pixel_10
            """),
        "emulator stop" to Command("robomori emulator stop $emulator", "Stop a running emulator",
            """
            Waits until it has stopped. Does nothing if it isn't running.

            Example:
              robomori emulator stop Pixel_10
            """),
        "emulator delete" to Command("robomori emulator delete $emulator", "Delete a stopped emulator",
            """
            Deletes the emulator and everything on it. It can't be running.

            Example:
              robomori emulator delete Pixel_10
            """),
        "emulator wipe" to Command("robomori emulator wipe $emulator", "Erase an emulator and start it fresh",
            """
            Erases everything on a stopped emulator, like a factory reset, then starts it and waits until Android has
            booted. Prints its serial.

            Example:
              robomori emulator wipe Pixel_10
            """),
        "emulator fold" to Command("robomori emulator fold $emulator", "Fold a running foldable emulator",
            """
            Folds it to its cover screen, as when you fold the phone shut.
            """),
        "emulator unfold" to Command("robomori emulator unfold $emulator", "Unfold a running foldable emulator"),
        "device controls" to Command("robomori device controls [<control> [<value>]] $deviceOption",
            "Show or change a running device's settings",
            """
            Without a value, prints the current value. Without a control, prints them all.

            Controls:
              dark-mode on|off                 Dark theme
              font-scale <scale>               Font size: ${textSizeOptions.joinToString(", ") { cliNumber(it) }}.
              locale <tag>                     Language and region, such as fr-FR.
              talkback on|off                  TalkBack screen reader
              animations <scale>               All animations: ${animationScaleOptions.joinToString(", ") { cliNumber(it) }}, or off.
              keyboard on|off                  Show the on-screen keyboard while the computer's keyboard is
                                               attached. Emulators only.

            Examples:
              robomori device controls
              robomori device controls dark-mode on
              robomori device controls animations off --device Pixel_10
            """),
        "device location" to Command("robomori device location set <latitude>,<longitude> | clear $deviceOption",
            "Simulate a device's GPS location",
            """
            set gives the device a GPS position; clear returns it to its real location. On a physical device, a
            background process keeps the position until you clear it.

            Examples:
              robomori device location set 37.4220,-122.0841
              robomori device location clear
            """),
        "input press" to Command("robomori input press <button> [--long] [--quiet] $deviceOption", "Press a button on a running device",
            """
            Buttons:
              back, home, recents, power   Phones, tablets and foldables
              crown, side                  Watches
              up, down, left, right, select, play-pause
                                           TV remote
              enter, delete, tab, escape, search, volume-up, volume-down
              space, minus, plus, equals, period, comma, slash, at
                                           Keys
              notifications, quick-settings, power-menu, lock
                                           System panels and the lock screen

            Any key works too: a letter or digit, a KEYCODE_ name or a key code of two or more digits. Hold modifiers by naming them first,
            such as ctrl+a or ctrl+shift+z; they are shift, ctrl, alt and meta.

            Options:
              --long   Hold the button, as for the power menu or a key's long-press action.
              --quiet  Only act: don't wait for the screen to settle or print what changed.

            Examples:
              robomori input press back
              robomori input press notifications
              robomori input press ctrl+a
            """),
        "device rotate" to Command("robomori device rotate [clockwise | counterclockwise] [--quiet] $deviceOption",
            "Rotate a running device's screen a quarter turn",
            """
            Clockwise unless you say otherwise. Emulators turn as if you turned them in your hand. Physical devices
            switch off auto-rotate to stay the way you turned them. Prints what changed on the screen once it settles.

            Options:
              --quiet   Only rotate: don't wait for the screen to settle or print what changed.

            Example:
              robomori device rotate counterclockwise
            """),
        "screen shot" to Command("robomori screen shot $deviceOption [-o <file.png>] [--size <pixels>] [--clean]",
            "Save a screenshot of a running device",
            """
            Options:
              -o <file.png>       Where to save it. '-o -' writes the PNG to standard output. Without it, the Desktop.
              --size <pixels>     Scale it down so its shorter side is this long, such as 540, so a coding agent
                                  looking at it uses fewer tokens. Without it, the screen's full size.
              --clean             Show 9:41, full Wi-Fi and battery and no notification icons in the status bar, ready
                                  for a store listing. Phones, tablets and foldables.

            Captures the screen exactly as it is, to check an app. Prints where it was saved.

            Examples:
              robomori screen shot -o home.png
              robomori screen shot --device Pixel_10 -o settings.png
              robomori screen shot --clean -o store.png
            """),
        "screen record" to Command("robomori screen record $deviceOption [--duration <time>] [-o <file.mp4>] [--clean]",
            "Record a running device's screen",
            """
            Records until --duration has passed, or until you press Ctrl+C. Unlike adb, there's no 3-minute limit.

            Options:
              --duration <time>   How long to record, such as 30s or 2m.
              -o <file.mp4>       Where to save it. Without it, the Desktop.
              --clean             Show 9:41, full Wi-Fi and battery and no notification icons in the status bar while
                                  recording, ready for a store listing.

            Prints where it was saved.

            Example:
              robomori screen record --duration 30 -o checkout.mp4
            """),
        "screen read" to Command("robomori screen read [--all] $deviceOption", "Read a running device's screen as text",
            """
            Prints the app showing and the screen's size, then one line per element, top to bottom:

              @4 switch "Wi-Fi" on (900,600)
              @5 list more ↓ [0,300,1080,1800]
              Network preferences

            An element you can act on starts with its @ref, then its role, its "label", its state and its center
            (x,y), or its bounds [left,top,right,bottom] when it's a list. Lines without a ref are text. Refs stay the
            same while an element stays on the screen. Toasts shown since the screen was last read follow at the end, as
            toast "Saved". Reads in milliseconds through Robomori's agent on the device, once the screen has stopped
            changing. The agent holds the device's UI automation until 30 seconds after the last command; UI tests and
            uiautomator need it, so they work again after that.

            Options:
              --all    Include the status bar, the navigation bar and the keyboard's keys.

            Examples:
              robomori screen read
              robomori input tap "Sign in"
            """),
        "input tap" to Command("robomori input tap <target> [--long] [--times <n>] [--quiet] $deviceOption", "Tap something on a running device's screen",
            """
            <target> is an @ref from 'robomori screen read', such as @4; text, a description or a view ID on the screen,
            such as 7 on a calculator; or a point as x,y. Text that matches several things fails and lists their refs. Prints what it tapped.

            Options:
              --long        Long press.
              --times <n>   Tap n times in a row, as quickly as a double tap: 2 double taps.
              --quiet       Only act: don't wait for the screen to settle or print what changed.

            Examples:
              robomori input tap "Sign in"
              robomori input tap @12
              robomori input tap 540,1200 --times 2
            """),
        "input type" to Command("robomori input type [<text>] [--into <target>] [--clear] [--submit] [--quiet] $deviceOption", "Type text on a running device",
            """
            Adds the text to the end of the field that has focus, all at once, so emoji and other languages work too.
            Fields Android's accessibility can't reach, such as in some games, get key presses instead.

            Options:
              --into <target>   Tap this field first: an @ref or its text.
              --clear           Replace the field's text instead of adding to it. Without text, empties the field.
              --submit          Press enter afterwards.
              --quiet           Only act: don't wait for the screen to settle or print what changed.

            Examples:
              robomori input type "ada@example.com" --into Email
              robomori input type "new name" --into @3 --clear
            """),
        "input scroll" to Command("robomori input scroll up|down|left|right [<target>] [--until <text>] [--quiet] $deviceOption",
            "Scroll a running device's screen",
            """
            Shows more of a list in that direction, without flinging: down shows what's below. Scrolls <target>, an @ref
            or text, or else the largest list on the screen. 'robomori screen read' marks lists with more to show, such as
            more ↓.

            Options:
              --until <text>   Keep scrolling until something with this text shows, and print it. Fails at the end of
                               the list.
              --quiet          Only act: don't wait for the screen to settle or print what changed.

            Examples:
              robomori input scroll down
              robomori input scroll down --until "About phone"
            """),
        "input swipe" to Command("robomori input swipe <from> [<x,y>...] <to> [--duration <time>] [--quiet] $deviceOption", "Swipe across a running device's screen",
            """
            Drags a finger from <from> to <to> in 300 ms, such as to dismiss something or turn a page. Each is an @ref,
            text or x,y, and <to> can instead be up, down, left or right, which moves 60% of the screen that way.
            Points between them make a path the finger follows, such as around a dial. To pick something up and
            move it, use 'robomori input drag'.

            Options:
              --duration <time>   How long the swipe takes, such as 300ms or 0.8s; 300ms unless you say otherwise.
              --quiet             Only act: don't wait for the screen to settle or print what changed.

            Examples:
              robomori input swipe 900,1200 100,1200
              robomori input swipe @5 left
              robomori input swipe 790,960 540,1210 290,960 540,710 --duration 800ms
            """),
        "input drag" to Command("robomori input drag <target> <to> [--duration <time>] [--quiet] $deviceOption", "Drag something to another place on a running device",
            """
            Presses and holds <target> until it's picked up, moves it to <to> and drops it there, such as to reorder a
            list or move an icon. Both are an @ref, text or x,y.

            Options:
              --duration <time>   How long the move takes after the hold, such as 600ms or 1s; 600ms unless you say
                                  otherwise.
              --quiet             Only act: don't wait for the screen to settle or print what changed.

            Example:
              robomori input drag "Wi-Fi" @7
            """),
        "input zoom" to Command("robomori input zoom in|out [<target>] [--quiet] $deviceOption", "Pinch to zoom on a running device",
            """
            Spreads two fingers apart to zoom in, or pinches them together to zoom out, over <target>, an @ref or text,
            or else the middle of the screen. Works on maps, photos and anything else that zooms.

            Options:
              --quiet   Only act: don't wait for the screen to settle or print what changed.

            Example:
              robomori input zoom in
            """),
        "app launch" to Command("robomori app launch <package|link> [--restart] [--clear-data] [--quiet] $deviceOption", "Open an app on a running device",
            """
            Opens the app as its icon would, and waits until it's showing. Prints the activity it opened. A link, such as
            https://example.com or a deep link like myapp://orders/42, opens in whatever app handles it.

            Options:
              --restart      Start the app over instead of returning to where it was.
              --clear-data   Also erase its data first, as if it was just installed: for testing a first run.
              --quiet        Only act: don't wait for the screen to settle or print what changed.

            Examples:
              robomori app launch com.android.settings
              robomori app launch com.example.app --clear-data
              robomori app launch "myapp://orders/42"
            """),
        "app close" to Command("robomori app close <package> [--quiet] $deviceOption", "Stop an app on a running device",
            """
            Stops the app completely, as Force stop in its settings does, so it starts fresh the next time.

            Options:
              --quiet   Only act: don't wait for the screen to settle or print what changed.

            Example:
              robomori app close com.example.app
            """),
        "app list" to Command("robomori app list $deviceOption", "List the apps on a running device",
            """
            Prints the package of each app the launcher shows, one per line, to open with 'robomori app launch'.

            Example:
              robomori app list
            """),
        "app install" to Command("robomori app install <apk>... [--grant] $deviceOption", "Install an app on a running device",
            """
            Installs the APK, replacing the app if it's already there and keeping its data. Several APKs are the parts
            of one app split by bundletool, and install together.

            Options:
              --grant   Grant every permission the app asks for, so no permission prompts show.

            Example:
              robomori app install app/build/outputs/apk/debug/app-debug.apk --grant
            """),
        "app uninstall" to Command("robomori app uninstall <package> $deviceOption", "Remove an app from a running device",
            """
            Example:
              robomori app uninstall com.example.app
            """),
        "device clipboard" to Command("robomori device clipboard [<text>] $deviceOption", "Read or set a running device's clipboard",
            """
            Prints the text on the clipboard, or with <text>, copies it there, ready to paste.

            Examples:
              robomori device clipboard
              robomori device clipboard "https://example.com/reset?token=abc"
            """),
        "screen wait" to Command("robomori screen wait <text> [--gone] [--timeout <time>] $deviceOption", "Wait for text to show on a running device",
            """
            Waits until something on the screen contains <text>, and prints it. Fails after --timeout, 10 seconds
            unless you say otherwise.

            Options:
              --gone             Wait until nothing contains it instead.
              --timeout <time>   How long to wait, such as 30s or 2m.

            Example:
              robomori screen wait "Welcome back" --timeout 20
            """),
        "files list" to Command("robomori files list [<path>] $deviceOption",
            "List a folder's files on a running device",
            """
            Lists the files in <path>, shared storage (/sdcard) by default, with when each changed and its size in
            bytes; folders end with /. An app's own files are under /data/data/<package>: Robomori opens a debug
            build's through run-as, and every app's on emulators without Google Play and rooted devices.

            Examples:
              robomori files list
              robomori files list /sdcard/Download
              robomori files list /data/data/com.example.app/files
            """),
        "files read" to Command("robomori files read <path> $deviceOption",
            "Print a text file on a running device",
            """
            Prints a text file, such as a log the app wrote or its SharedPreferences XML, including an app's own
            files under /data/data/<package>. For other files, use 'robomori files pull'.

            Examples:
              robomori files read /data/data/com.example.app/shared_prefs/settings.xml
            """),
        "files pull" to Command("robomori files pull <path> [-o <local path>] $deviceOption",
            "Copy a file or folder from a running device to this computer",
            """
            Copies <path>, a file or a folder with everything in it, into the current folder, or to -o. Prints where.

            Examples:
              robomori files pull /sdcard/Download/report.pdf
              robomori files pull /data/data/com.example.app/files -o app-files
            """),
        "files push" to Command("robomori files push <local file> <folder> $deviceOption",
            "Copy a file from this computer into a folder on a running device",
            """
            Copies one file into <folder> on the device, replacing a file with the same name, and prints its path.
            Into an app's own folder under /data/data/<package>, the file becomes the app's.

            Examples:
              robomori files push photo.jpg /sdcard/Pictures
              robomori files push fixture.json /data/data/com.example.app/files
            """),
        "files delete" to Command("robomori files delete <path> $deviceOption",
            "Delete a file or folder on a running device",
            """
            Deletes <path>, a file or a folder with everything in it.

            Examples:
              robomori files delete /sdcard/Download/old.apk
            """),
        "data list" to Command("robomori data list <package> $deviceOption",
            "List an app's databases, with their tables, and its preferences",
            """
            Lists the SQLite and Room databases <package> keeps, each table with how many rows it has, and its
            SharedPreferences and DataStore files, as the app's App data page shows them. Works for debug builds, and
            for every app on emulators without Google Play and rooted devices.

            Examples:
              robomori data list com.example.app
            """),
        "data query" to Command("robomori data query <package> <database> <sql> [--limit <rows>] $deviceOption",
            "Run SQL on an app's database",
            """
            Runs <sql> on one of <package>'s databases and prints the rows, tab-separated with a header, or with --json
            as columns and rows. Reading statements run on a copy, so the app isn't disturbed. Statements that change
            the database, such as UPDATE or DELETE, run on the device as the app, all or none, and print how many rows
            changed; the running app sees the change at once.

            Options:
              --limit <rows>   Print up to this many rows, 100 by default.

            Examples:
              robomori data query com.example.app app.db "SELECT * FROM users"
              robomori data query com.example.app app.db "UPDATE users SET premium = 1 WHERE id = 1"
            """),
        "data prefs" to Command("robomori data prefs <package> <file> $deviceOption",
            "Print an app's SharedPreferences or DataStore preferences",
            """
            Prints each preference in <file>, from 'robomori data list', as its key, type and value, tab-separated.
            The file can be named with or without its extension.

            Examples:
              robomori data prefs com.example.app settings
            """),
        "network record" to Command("robomori network record [--app <package>] [--duration <time>] [-o <file.har>] $deviceOption",
            "Record a running device's network traffic, as the app's Network page does",
            """
            Sends the device's traffic through Robomori and prints each request as it finishes: its status, method, URL,
            how long it took, its size in bytes and the app that made it. Stops with Ctrl+C or after --duration. With
            --json, prints one object per line. With -o, also saves the requests as a HAR file with their headers and
            bodies, which you can read for what the app sent and got back.

            HTTPS is read for every app on emulators without Google Play and on rooted devices, and for debug builds
            elsewhere; other apps' requests show their server, marked ENCRYPTED. Rules from 'robomori network rules'
            change responses while recording, including rules added while it records. Close the app's Network page
            for the device first: only one can record it at a time.

            Options:
              --app <package>     Only requests this app made.
              --duration <time>   Stop after <time>, such as 30s or 2m.
              -o, --output <file> Also save the requests as a HAR file.

            Examples:
              robomori network record --app com.example.app --duration 30s -o requests.har
              robomori network record --json
            """),
        "network rules" to Command("robomori network rules [--on | --off]",
            "List the rules that change apps' responses",
            """
            Lists the rules saved in the app and by 'robomori network mock' and 'rewrite', in the order they're tried:
            the first one that's on and matches a request changes it. Each starts with the ID the other rule commands
            take. Rules apply on every device, in the app's Network page and in 'robomori network record'.

            Options:
              --off   Stop every rule from changing responses, keeping them, as the app's Use Rules switch does.
              --on    Use the rules again.

            Examples:
              robomori network rules
              robomori network rules --off
            """),
        "network mock" to Command("robomori network mock <url> [--method <method>] [--status <code>] [--header 'Name: value']... [--body <text> | --body-file <file>]",
            "Answer matching requests with your own response, without contacting the server",
            """
            Adds a rule, on at once, and prints its ID. <url> is the server and path, such as
            api.example.com/v1/users*, where * stands for any text; without a ?, any query matches.

            Options:
              --method <method>    Only this method, such as GET or POST. Any method when left out.
              --status <code>      The status code, 200 by default.
              --header 'Name: v'   A response header; give it more than once for several.
                                   Content-Type: application/json by default.
              --body <text>        The response body.
              --body-file <file>   Read the body from a file instead.

            Examples:
              robomori network mock 'api.example.com/v1/users' --body '[]'
              robomori network mock 'api.example.com/v1/*' --status 503
              robomori network mock 'api.example.com/v1/profile' --method GET --body-file profile.json
            """),
        "network rewrite" to Command("robomori network rewrite <url> [--method <method>] [--status <code>] [--header 'Name: value']... [--remove-header <name>]... [--body <text> | --body-file <file> | --find <text> --replace <text>]",
            "Change the server's real response to matching requests",
            """
            Adds a rule, on at once, and prints its ID. The request still goes to the server; the rule changes what
            comes back before the app gets it. <url> is matched as for 'network mock'.

            Options:
              --method <method>       Only this method, such as GET or POST.
              --status <code>         Replace the status code, such as 500.
              --header 'Name: v'      Add a header, or replace one with the same name; repeat for several.
              --remove-header <name>  Remove a header; repeat for several.
              --body <text>           Replace the body. --body-file <file> reads it from a file.
              --find <text>           Replace every <text> in the body with --replace's text.

            Examples:
              robomori network rewrite 'api.example.com/v1/orders' --status 500
              robomori network rewrite 'api.example.com/v1/feature-flags' --find '"newCheckout":false' --replace '"newCheckout":true'
            """),
        "network delete-rule" to Command("robomori network delete-rule <id>",
            "Delete a rule",
            """
            <id> is the start of the rule's ID from 'robomori network rules'.

            Examples:
              robomori network delete-rule 3f2a91c0
            """),
        "network enable-rule" to Command("robomori network enable-rule <id>", "Turn a rule on"),
        "network disable-rule" to Command("robomori network disable-rule <id>", "Turn a rule off, keeping it for later"),
        "app logs" to Command("robomori app logs [<package>] [--since <time>] [--all] $deviceOption",
            "Show what an app logged, and its crashes",
            """
            Prints the warnings, errors and crash stack traces <package> logged since 'robomori app logs' last showed
            them, so each run shows only what's new; the first time, the last 2 minutes. Without <package>, every app's.
            Stack traces show the frames in the app's own code and count the framework's, once even when logged twice.
            When an action makes an app crash, the screen it prints says so, with the exception and where in the app.

            Options:
              --since <time>   Show what was logged in the last <time>, such as 30s or 5m, instead.
              --all            Every level, including debug and info, and every stack frame.

            Examples:
              robomori app logs com.example.app
              robomori app logs com.example.app --since 1m --all
            """),
        "mcp" to Command("robomori mcp", "Serve the device commands to coding agents over MCP",
            """
            Runs a Model Context Protocol server on standard input and output, with tools to read the screen, tap,
            type, scroll, swipe, drag, zoom, press keys, open and close apps, use the clipboard, wait for text and take
            screenshots. It keeps its connection to each device, so an action and the screen it leads to come back in
            milliseconds. Each action returns what changed on the screen.

            The first tool an agent uses claims a free emulator for its worktree, as 'robomori device claim' does, so
            agents working in parallel each get their own. The claim ends, and the device's controls go back as they
            were, when the agent's session ends.

            Add it to Claude Code:
              claude mcp add robomori -- robomori mcp
            """),
        "preset list" to Command("robomori preset list", "List the presets saved in the app",
            """
            Shows each preset and the controls it changes.
            """),
        "preset apply" to Command("robomori preset apply <preset> $deviceOption", "Apply a preset to a running device",
            """
            Changes every control the preset holds, and reports each one.

            Example:
              robomori preset apply "French, large text"
            """),
        "preset save" to Command("robomori preset save <name> $deviceOption", "Save a running device's controls as a preset",
            """
            Saves dark mode, font size, locale, TalkBack and animations as they are now, to use in the app or with
            'robomori preset apply'.

            Example:
              robomori preset save "French, large text"
            """),
        "preset delete" to Command("robomori preset delete <preset>", "Delete a saved preset"),
        "profile list" to Command("robomori profile list", "List the hardware profiles emulators are created from",
            """
            The ID is what 'robomori emulator create' takes.
            """),
        "image list" to Command("robomori image list [--profile <id>] [--refresh]", "List the Android versions emulators can run",
            """
            Shows the system images that work on this computer for the profile's device type, and whether each one is
            installed. Without --profile, phone images.

            Options:
              --profile <id>   Images for this profile's device type
              --refresh        Ask the Android SDK again instead of using the saved list
            """),
        "config list" to Command("robomori config list", "List Robomori's settings",
            """
            Shows each setting, its value, and whether it was set or is the default. The app uses the same settings.

            Settings:
              sdk          Android SDK folder
              avds         Where emulators are stored
              adb, emulator, sdkmanager, avdmanager
                           A tool's path. Normally found in the SDK folder.
              ports        Your computer's ports that apps on every device reach as localhost,
                           separated by commas. 3000 and 8080 by default.
            """),
        "config get" to Command("robomori config get <setting>", "Print one setting's value",
            """
            Example:
              ${'$'}(robomori config get adb) -s emulator-5554 install app.apk
            """),
        "config set" to Command("robomori config set <setting> <value>", "Change a setting",
            """
            Checks the value the way the app's SDK Locations page does. Ports take effect on running devices right away.

            Examples:
              robomori config set sdk ~/Library/Android/sdk
              robomori config set ports 3000,8080,5173
            """),
        "config unset" to Command("robomori config unset <setting>", "Return a setting to its default"),
        "help" to Command("robomori help [<command>]", "Show help for a command"),
        "version" to Command("robomori version", "Show Robomori's version"),
    )

    /** The order help lists a group's commands in, most used first, where it isn't the order they're defined in. */
    private val order = mapOf(
        "device" to listOf("list", "claim", "release", "restart", "rotate", "controls", "location", "clipboard"),
        "app" to listOf("launch", "close", "list", "install", "uninstall", "logs"),
        "files" to listOf("list", "read", "pull", "push", "delete"),
        "data" to listOf("list", "query", "prefs"),
        "network" to listOf("record", "rules", "mock", "rewrite", "delete-rule", "enable-rule", "disable-rule"),
        "screen" to listOf("read", "wait", "shot", "record"),
        "input" to listOf("tap", "type", "press", "scroll", "swipe", "drag", "zoom"),
    )

    /** Each group and its commands, in the order help lists them. */
    val groups: Map<String, List<String>> = all.keys.filter { ' ' in it }.groupBy({ it.substringBefore(' ') }, { it.substringAfter(' ') })
        .mapValues { (group, commands) -> order[group]?.let { listed -> listed + (commands - listed.toSet()) } ?: commands }

    /** Commands with no group. */
    val commands: Set<String> = all.keys.filter { ' ' !in it }.toSet()

    val topLevel: Set<String> get() = groups.keys + commands

    /**
     * Earlier names that still work, such as tap for input tap, left out of help: agents type the short ones all day,
     * and scripts written for Robomori 1.8 keep working.
     */
    val aliases = mapOf(
        // The input commands an agent types all day.
        "tap" to "input tap", "type" to "input type", "press" to "input press", "scroll" to "input scroll", "swipe" to "input swipe",
        // Robomori 1.8's names.
        "screenshot" to "screen shot", "record" to "screen record", "rotate" to "device rotate", "controls" to "device controls",
        "simulate location" to "device location",
    )

    /** [args] with an earlier command name, such as tap or simulate location, put as the command it now is. */
    fun canonical(args: List<String>): List<String> {
        if (args.size >= 2 && "${args[0]} ${args[1]}" in aliases) return aliases.getValue("${args[0]} ${args[1]}").split(' ') + args.drop(2)
        val alias = args.firstOrNull()?.let(aliases::get) ?: return args
        return alias.split(' ') + args.drop(1)
    }

    private val groupSummaries = mapOf(
        "device" to "Devices, emulators and physical ones: list, claim, restart, rotate, and change their controls, location and clipboard",
        "emulator" to "Create, start, open, stop, delete, wipe and fold emulators",
        "app" to "Apps on a running device: open, close, install and remove them, and read their logs",
        "files" to "A running device's files, including apps' own: list, read, copy to and from, and delete them",
        "data" to "Apps' databases and preferences: list them, run SQL and read preferences",
        "network" to "A running device's network traffic: record it, and change the responses apps get with rules",
        "screen" to "A running device's screen: read it as text, wait for something, take a screenshot or record it",
        "input" to "Act on a running device's screen: tap, type, press, scroll, swipe, drag and zoom",
        "preset" to "Presets saved in the app",
        "profile" to "Hardware profiles",
        "image" to "Android system images",
        "config" to "Robomori's settings",
    )

    fun text(words: List<String>): String? {
        if (words.isEmpty()) return overview()
        val key = words.joinToString(" ")
        all[key]?.let { command -> return "Usage: ${command.usage}\n\n${command.summary}.\n" +
            command.details.trimIndent().let { if (it.isEmpty()) "" else "\n$it\n" } }
        val group = groups[words.first()]?.takeIf { words.size == 1 } ?: return null
        return buildString {
            appendLine("Usage: robomori ${words.first()} <command>")
            appendLine()
            appendLine("${groupSummaries.getValue(words.first())}.")
            appendLine()
            appendLine("Commands:")
            group.forEach { appendLine("  ${it.padEnd(13)} ${all.getValue("${words.first()} $it").summary}") }
            appendLine()
            appendLine("Run 'robomori help ${words.first()} <command>' for a command's options.")
        }
    }

    private fun overview() = buildString {
        appendLine("Robomori: Android emulators and devices from the command line.")
        appendLine()
        appendLine("Usage: robomori <command> [options]")
        appendLine()
        fun section(title: String, keys: List<String>) {
            appendLine(title)
            keys.forEach { key -> appendLine("  ${key.padEnd(21)} ${all.getValue(key).summary}") }
            appendLine()
        }
        section("Devices:", groups.getValue("device").map { "device $it" })
        section("Emulators:", groups.getValue("emulator").map { "emulator $it" })
        section("Apps:", groups.getValue("app").map { "app $it" })
        section("Files:", groups.getValue("files").map { "files $it" })
        section("App data:", groups.getValue("data").map { "data $it" })
        section("Network:", groups.getValue("network").map { "network $it" })
        section("Screen:", groups.getValue("screen").map { "screen $it" })
        section("Input:", groups.getValue("input").map { "input $it" })
        section("Presets:", groups.getValue("preset").map { "preset $it" })
        section("Catalogs:", listOf("profile list", "image list"))
        section("Coding agents:", listOf("mcp"))
        section("Settings:", groups.getValue("config").map { "config $it" })
        section("General:", listOf("help", "version"))
        appendLine("--device takes a device's name or serial from 'robomori device list'. Leave it out when only one")
        appendLine("device is running, after 'robomori device claim', or with ANDROID_SERIAL set.")
        appendLine()
        appendLine("Actions on the screen, such as input tap or app launch, then print what changed once it settles; add")
        appendLine("--quiet to only act. Name what to act on by its @ref from screen read, its text, or x,y.")
        appendLine()
        appendLine("Times, such as --timeout, take 500ms, 30s or 2m; a plain number is seconds. Text that starts with")
        appendLine("a dash goes after --, as in input type -- \"-v\".")
        appendLine()
        appendLine("Robomori is free for $TRIAL_DAYS days from the first time it runs, here or in the app; after that, it")
        appendLine("needs a license from https://robomori.com/pricing.")
        appendLine()
        appendLine("Run 'robomori help <command>' for a command's options. Add --json to any command for")
        appendLine("machine-readable output.")
    }
}

/** A scale as people type it: 1, 0.5, 1.15. */
internal fun cliNumber(value: Float): String = value.toString().removeSuffix(".0")
