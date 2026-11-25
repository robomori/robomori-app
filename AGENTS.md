# Project preferences

- Use Spectre only as interactive tooling to inspect and operate the running app.
- Do not create or run automated Compose/Spectre UI tests, JUnit UI drivers, or scripted UI test suites.
- Backend/unit tests are allowed. Keep them headless; they must not open app windows or drive the UI.
- Keep interactive tooling out of packaged production releases.
- Declare dependencies and plugins in the Gradle version catalog, `gradle/libs.versions.toml`. The app's version is `version` in `gradle.properties`.

## Hot reload

- To run the app, start `./gradlew hotRun --auto` in the background and return. Don't poll for the window or the build: the background task reports if it exits, and the person you're working with sees the window open.
- While `./gradlew hotRun --auto` is running, don't run other Gradle builds in the same checkout. They share the build folder, and an overlapping build makes the app reload half-finished classes. Let hot reload compile, and read `build/run/main/main.chr.log` for its result.
- Run tests or other builds only while hot reload is idle: its log ends with "Waiting for changes to input files...".
- Hot reload can't apply values set once at startup, or changes that alter a class's shape (its log then shows "Reload failed"). Restart the app for those. Quitting the app stops every running emulator, so start them again afterwards.
- Hot reload builds with Gradle's configuration cache. Task actions in `build.gradle.kts` must capture only local values, never build-script properties or functions, or every reload build fails.

## Operating the running app

Drive the app with Spectre (docs: https://spectre.sebastiano.dev/guide/cli/) to check what you build. Never click by screen position: other windows may cover the app.

- Start the app with hot reload as usual. Development runs add `-XX:+EnableDynamicAgentLoading` so Spectre can attach; packaged releases don't.
- Attach to the app's JVM, `com.composables.codepush.CodePushLauncher`. The "Compose Hot Reload" window is a separate JVM with none of the app's UI.
  ```sh
  PID=$(spectre ps | awk '/CodePushLauncher/ {print $1}')
  spectre attach "$PID" --json          # the session is "pid-$PID"
  ```
- Find controls by test tag, then act on the node key `find` returns:
  ```sh
  spectre tree "pid-$PID" --json                         # every node with its testTag and description
  spectre find "pid-$PID" CaptureScreenshot --json
  spectre click "pid-$PID" <node key>
  ```
- Node keys are short-lived. Find the node again after each hot reload or UI change.
- Don't use `spectre launch` for this: its session can't be reached by other commands.
- Run `spectre detach "pid-$PID"` when done.

## Design system

- Build all UI from the design system: components in `src/main/kotlin/com/robomori/components/`, theme and styles in `src/main/kotlin/com/robomori/theme/` (colors and type styles in `RobomoriTokens.kt`, read with `RobomoriTheme.colors` and `.typography`; fonts in the `googleFonts` block of `build.gradle.kts`).
- Never hand-build buttons, fields, checkboxes, progress bars or other controls in feature code, even for screens with a distinct look.
- Components used by a single screen, such as the onboarding's scaffold and rows, may live with that screen. Build them from design-system controls.
- Never create a new component, style, variant or size without asking Alex first. When a screen needs a look the design system doesn't have, propose it and wait. Once he agrees, add it to the design system, then use it.

## Screen architecture

- There are no ViewModels. A screen's state is a plain class owned by `AppState`, such as `CommandLineToolState` or `LocalhostPortsState`, holding Compose state and launching its work in the app's coroutine scope.
- Composables render that state and call its functions; they make no decisions and start no work during recomposition.
- State holds typed outcomes, not UI text: the composable chooses the wording. Model outcomes that exclude each other as one sealed type, such as `OperationState`, not as flags that can drift apart.
- Unit-test the state class directly with fake dependencies, such as a `CommandExecutor` that records adb commands.

## Packaging

- Packaging runs the app once to train its AOT cache. That run must stay invisible on the Mac building the release: its window opens off-screen without focus, and on macOS it starts as a background app (`apple.awt.UIElement`, set in `Main.kt`) so no Dock icon or menu bar appears. Keep anything new that the training run shows hidden the same way.

## Analytics

- The app reports to Simple Analytics under the domain `app.robomori.com`.
- Event names and screen paths are defined in `src/main/kotlin/com/robomori/Tracking.kt` (`FunnelEvent`, `BuySource`, `PagePath`). Add new events there.
- The funnel, in order: `first_launch` → `onboarding_started` → `onboarding_sdk` → `onboarding_platforms` → `onboarding_cli` → `onboarding_license` → `onboarding_finished` or `onboarding_skipped`, then `buy_clicked` (with a `source`) → `license_activated`. `device_started` is sent for each emulator that comes up after a launch from the app, the sign that setup worked, with its `type`, `android` version and hardware `profile`. `active_day` counts daily active users and `app_opened` counts every launch.
- Which devices people use: `device_connected` once per phone, tablet or watch each run, and `device_paired` when one is paired over Wi-Fi (with a `method` of `qr` or `code`). Both carry its `type`, `manufacturer`, `model`, `android` version and `connection` (`usb` or `wifi`), never a serial or name.
- Only packaged builds send analytics; development runs use `Analytics.None`.
