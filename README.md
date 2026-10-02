# Scroll Starva

Scroll Starva is an Android app that estimates how far a user has scrolled on short-form feeds (TikTok, YouTube Shorts, Instagram Reels, and Facebook Reels) and tracks time spent in those feeds.

The dashboard separates a daily check-in, feed-time trends, scroll activity, and habit progress into tabs. Local charts show up to 15 days of activity, with supportive summaries that compare tracked weeks once enough history is available. The interface also includes an original animated progress buddy, tab-specific accent colors, animated page transitions, and tappable charts.

The **Debug** tab shows recent accessibility events from supported apps, the event source and active-window labels, feed-detection decisions, scroll deltas, and whether each scroll was counted or ignored. The on-device log keeps up to 120 recent entries in memory; visible feed text can appear in the log. Use **Copy log** only when you intend to share those details.

## Setup

Install or configure the following before building:

- IntelliJ IDEA, or Android Studio
- Android SDK Platform 35 and Android SDK Build-Tools
- JDK 17 (required by this project; newer or older JDK versions may not work)
- An Android emulator or a physical Android device running Android 8.0+ (API 26+)

### Install and configure the Android SDK

With Android Studio, open **Tools > SDK Manager** (or **Settings/Preferences > Languages & Frameworks > Android SDK**). Install **Android SDK Platform 35** and the latest **Android SDK Build-Tools**. Note the **Android SDK Location** shown at the top of the SDK Manager.

#### Without Android Studio

Download the **Command line tools only** for your operating system from the [Android Studio downloads page](https://developer.android.com/studio#command-tools). Extract them into your SDK directory so `sdkmanager` is located at `cmdline-tools/latest/bin/sdkmanager` (create the `latest` directory if needed). Then install the SDK packages and accept their licenses:

```bash
SDK="$HOME/Android/Sdk"
"$SDK/cmdline-tools/bin/sdkmanager" --sdk_root="$SDK" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
"$SDK/cmdline-tools/bin/sdkmanager" --sdk_root="$SDK" --licenses
```

Change `$HOME/Android/Sdk` to your chosen SDK directory. Add its `cmdline-tools/latest/bin` and `platform-tools` directories to `PATH` if you want to run `sdkmanager` and `adb` directly from a terminal. On Windows, run `sdkmanager.bat` from Command Prompt or PowerShell and use the Windows SDK path.

Gradle needs to know that location. Choose either option:

- **Set `ANDROID_HOME`:** Set it to the SDK location shown in Android Studio. For a typical Linux installation, add the following to `~/.bashrc` or `~/.zshrc`, changing the path if your SDK is elsewhere:

  ```bash
  export ANDROID_HOME="$HOME/Android/Sdk"
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/bin:$PATH"
  ```

  Reload the shell configuration (for example, `source ~/.bashrc`) and open a new terminal.
- **Set `sdk.dir` in `local.properties`:** Create `local.properties` in the project root—the same directory containing `settings.gradle.kts`, `build.gradle.kts`, and `gradlew`. This is inside the checked-out project, not the Linux filesystem root (`/`). For example, if the project is at `/home/alex/projects/scroll-starva`, the file should be `/home/alex/projects/scroll-starva/local.properties`. Put the absolute Android SDK path in it:

  ```properties
  sdk.dir=/home/your-user/Android/Sdk
  ```

  Use the actual path on your computer. This file is machine-specific and should not be committed.

To check the environment variable, run `echo "$ANDROID_HOME"` on Linux/macOS or `echo %ANDROID_HOME%` in Windows Command Prompt. Ensure it matches the SDK directory you installed.

### Install JDK 17 and set `JAVA_HOME`

Download and install a JDK 17 distribution, for example [Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17). Choose the installer or archive for your operating system and architecture.

Set `JAVA_HOME` to the JDK installation directory (not its `bin` subdirectory), and add `$JAVA_HOME/bin` to `PATH`:

- **Linux:** Set `JAVA_HOME` to the installed JDK directory, for example `/usr/lib/jvm/temurin-17-jdk-amd64`. Add these lines to `~/.bashrc` or `~/.zshrc`, adjusting the directory if needed:

  ```bash
  export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
  export PATH="$JAVA_HOME/bin:$PATH"
  ```

  Reload the shell configuration with `source ~/.bashrc` (or `source ~/.zshrc`).
- **macOS:** For a JDK installed in the standard location, add these lines to `~/.zshrc`:

  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 17)
  export PATH="$JAVA_HOME/bin:$PATH"
  ```

  Reload it with `source ~/.zshrc`.
- **Windows:** Open **Edit the system environment variables** > **Environment Variables**. Under your user variables, create `JAVA_HOME` with the JDK 17 installation directory (for example, `C:\Program Files\Eclipse Adoptium\jdk-17...`, without `\bin`). Edit `Path` and add `%JAVA_HOME%\bin`. Open a new terminal after saving.

Confirm the configuration in a new terminal:

```bash
java -version
```

It should report version 17. In IntelliJ IDEA, select the JDK for Gradle under **Settings/Preferences > Build, Execution, Deployment > Build Tools > Gradle > Gradle JVM**. Select a JDK 17 installation.

## Build the app

From the project root:

```bash
./gradlew assembleDebug
```

This generates a debug APK at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To build a release APK:

```bash
./gradlew assembleRelease
```

## Run the app

### IntelliJ IDEA

Open the project root (the directory containing `settings.gradle.kts`) in IntelliJ IDEA and allow Gradle to sync. Ensure the Gradle JVM is set to JDK 17. You can run the Gradle `installDebug` task from IntelliJ's Gradle tool window, or use its terminal:

```bash
./gradlew installDebug
```

IntelliJ editions may not provide Android Studio's emulator/device run controls. You can still build and install using Gradle; connect a device with USB debugging enabled (or start an emulator separately). To launch the installed app from a terminal with `adb` available:

```bash
adb shell am start -n com.scrollstarva.app/.MainActivity
```

#### Debug the Android app from IntelliJ

For a repeatable command-line setup, use [`scripts/android-debug.sh`](scripts/android-debug.sh). It builds and installs the debug app, configures Android to wait for a debugger, launches the app, and forwards its JDWP port. The app can appear frozen until IntelliJ attaches; that is expected.

Prerequisites: JDK 17 and the Android SDK are configured as described above, `adb` is on `PATH`, USB debugging is enabled on the phone, and the phone is authorized (`adb devices` shows its status as `device`). In IntelliJ, open **Run > Edit Configurations…**, add a **Remote JVM Debug** configuration (or **Remote** configuration with debugger mode set to **Attach**), and set host to `localhost` and port to `8700`. Leave that configuration ready; start it after the script says the phone app is waiting.

From the project root, run:

```bash
./scripts/android-debug.sh start
```

If exactly one authorized device is not connected, the script stops with instructions. When multiple devices are connected, select the phone explicitly:

```bash
ANDROID_SERIAL=13150314A3031291 ./scripts/android-debug.sh start
```

Replace the example serial with the ID shown by `adb devices`. When the script reports that the app is waiting, start the Remote JVM Debug configuration in IntelliJ. The app should then continue on the phone; reproduce the behavior to hit breakpoints.

When finished, stop/detach the debugger in IntelliJ and clear Android's persistent wait-for-debugger setting and the port forwarding:

```bash
./scripts/android-debug.sh stop
```

If IntelliJ does not offer a Remote JVM Debug/Remote configuration, this setup is not available in that IntelliJ installation; use Android Studio's debugger instead. Android Studio can select the connected device directly and does not need this script.

### Android Studio or command line

In Android Studio, open the project, select a connected device or emulator, and click **Run**. From a terminal, `./gradlew installDebug` installs the app on a connected device/emulator.

## Enable tracking

This app uses Android Accessibility Services to detect activity in supported short-form feeds.

After the app is installed:

1. Open the app.
2. Tap "Enable tracking".
3. In the Android accessibility settings, select "Scroll Starva tracker".
4. Turn it on.
5. Return to the app and ensure the status shows that tracking is enabled.

The app only reads visible accessibility labels from supported apps to recognize short-form feed screens. It does not send data anywhere off the device.

## Common issues

- Gradle sync fails: make sure the Android SDK is installed and configured in Android Studio.
- No device found: ensure USB debugging is enabled for a physical device or create/start an emulator.
- Accessibility toggle not visible: open Android Settings > Accessibility > Installed apps > Scroll Starva tracker.

## Useful commands

```bash
./gradlew clean
./gradlew test
./gradlew lint
```

## Project structure

```text
.
├── app/
│   ├── src/main/java/com/scrollstarva/app/
│   ├── src/main/res/
│   └── build.gradle.kts
├── scripts/
│   └── android-debug.sh
├── build.gradle.kts
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
└── spec.md
```
