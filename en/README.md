# blockmsg by illiuoner

A native Android workout stopwatch and interval timer. A workout activates Do Not Disturb while keeping system media available. YouTube Music controls use the player’s existing Android media session.

## Build and install

- JDK 17 or a compatible newer JDK
- Android SDK Platform 36 and Build-Tools 36.0.0
- Android SDK Platform-Tools for ADB
- Android 8.0 (API 26) or newer on the phone
- Network access to Google’s Maven repository, Maven Central, and Gradle for the first build

In Android Studio, install Platform 36, Build-Tools 36.0.0 and Platform-Tools through SDK Manager. Open the project folder and select your JDK in the Gradle settings.

From the project folder in Windows PowerShell, set the paths to your installations. Skip assignments that are already correct.

~~~powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\build-debug.ps1
~~~

The script runs the JVM tests and copies the debug build to the following path.

~~~text
dist\blockmsg-by-illiuoner-en-debug.apk
~~~

Enable USB debugging on your phone, connect it, and confirm the phone's ADB prompt.

~~~powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb devices
& $adb -d install -r '.\dist\blockmsg-by-illiuoner-en-debug.apk'
& $adb -d shell am start -n 'com.illiuoner.blockmsg.en/.MainActivity'
~~~

On macOS or Linux, set the JDK and Android SDK paths and run ./gradlew :app:testDebugUnitTest :app:assembleDebug. Gradle writes app/build/outputs/apk/debug/app-debug.apk.

## Set up the phone

1. Open the app and grant **Do Not Disturb access**. The workout Start button requires it.
2. To control music inside the app, grant **Notification access** and start playback in YouTube Music or a compatible player first. The app uses this access for media sessions and does not process notification content. It has no internet permission.
3. On Android 12 or newer, allow **Alarms & reminders** for timely interval signals while the phone is asleep. Without it, the screen-off signal can be delayed.
4. For an optional exit guard, enable **App pinning** in Android's Security settings and accept the system dialog at the start of a workout. Keep the system unpin gesture available.
5. On a Xiaomi 13T Pro with lock-screen lighting, look for **Settings → Always-on display & Lock screen → Notification effect** and select **None**. In Lock screen settings, also turn off **Wake lock screen for notifications**. If HyperOS offers **Beam for notifications**, disable it if the flash or screen still activates. Use the in-app diagnostic checklist to revisit these settings.

## Operation and recovery

- The stopwatch and the interval timer use the monotonic Android clock during a boot. The saved wall-clock timestamps recover elapsed time after a reboot.
- The **Intervals** tab and a left swipe open intervals. Edit duration, pause and resume, or run the completed interval again. Presets can be named, edited, and deleted.
- Interval sounds use the phone’s current media output and mix with music without taking audio focus. The alarm callback has a short wake lock. Exact alarms still depend on system idle limits and vendor battery settings.
- Android 10 and later use an automatic DND rule owned by this app. Ending a workout removes that rule. A stricter concurrent Android mode can still affect media or notification behavior.
- Android 8 and 9 use a saved global DND policy. Restoration avoids overwriting a policy the user edited during a session. A reboot releases the app’s DND rule or policy and keeps the workout time. Open the app and select **Restore workout focus** to reactivate it.
- Settings and the DND recovery journal are persisted together with Android AtomicFile. There is no recurring background task, background service, or notification listener that dismisses notifications.
- Android may defer recovery after a force-stop. If a rule remains active, turn off **Workout · blockmsg** in Android’s DND settings and reopen the app.
- The system may still show emergency alerts, call UI, or manufacturer-specific effects. This app silences supported notification signals through DND, leaves call handling to Android, and never changes Bluetooth or mobile connectivity.

## Verify on the device

| Area | Test |
| --- | --- |
| Timers | Start a workout, swipe to intervals, run and repeat a timer, and compare both readouts with Android's clock. Rotate, background, and reopen the app. |
| Persistence | Create a preset and a running interval. Reopen the app and verify duration, the preset, and elapsed time. Repeat across a reboot. |
| Alerts | Select and preview each sound. Run a short interval with the screen off and confirm the signal on the phone speaker and a Bluetooth headset. |
| Music | Play a track in YouTube Music, use all three controls, connect and disconnect a headset, and confirm that starting and ending a workout leaves playback under the player’s control. |
| DND | Send a message, place a regular call, and check the lock screen and Xiaomi notification effect. Test with an existing user mode and confirm that ending the workout leaves their mode available. |
| Pinning | Accept app pinning, try Back and Home, then end the workout. Also exercise Android’s manual unpin gesture and return to the preserved workout. |
| Permission loss | Revoke DND access during a workout, return to the app, and verify the protection status and recovery controls. |

The JVM tests cover elapsed time, deadlines, repeat behavior, and reboot fallback. Device tests cover operating system behavior and the actual HyperOS configuration.

## References

- [Android Gradle Plugin 9.0.1 compatibility](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
- [Android DND rule API](https://developer.android.com/reference/android/app/AutomaticZenRule)
- [Android alarm scheduling](https://developer.android.com/develop/background-work/services/alarms)
- [Xiaomi 13T Pro notification effects](https://www.mi.com/global/support/faq/details/KA-93205/)

Author: **illiuoner**
