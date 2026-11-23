# Device testing in this cloud environment

There's no KVM, so the Android emulator runs with software CPU emulation (TCG). It works, but slowly.

```bash
export ANDROID_HOME=/opt/android-sdk ANDROID_I_WANT_MY_TCG=yes
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "emulator" "system-images;android-34;aosp_atd;x86_64"
echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n test34 -k "system-images;android-34;aosp_atd;x86_64" -d pixel_4a
$ANDROID_HOME/emulator/emulator -avd test34 -no-window -accel off -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect -cores 3 -memory 3072 &
# ~5–12 min to boot; system_server may restart once. Then:
cd android && WADEH_DEV_TIMEOUT_S=120 ./gradlew :app:assembleDevDebug
adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
adb shell cmd package compile -m speed -f eg.wadeh.app.dev   # AOT-compile: required, interpreted code triggers ANRs
python3 ops/adbui.py tap --id request_viewing                # drive the UI via test tags (testTagsAsResourceId)
```

Known limits:
- **Screenshots are black** (`screencap` and the emulator console). `-gpu guest` falls back to lavapipe, and the zygote crash-loops. Use `ops/adbui.py dump` (the accessibility tree) for on-device evidence, and Robolectric renders (`./gradlew :app:testDevDebugUnitTest --tests '*ScreenshotTest*'` → `evidence/screens/`) for pixels.
- `adb shell input text` cannot type Arabic. Use the `wadeh://search?q=…` deep link.
- The ATD image has no keyboard or share targets. Pressing Back closes dialogs instead of hiding a keyboard.
- Timing numbers from this emulator are meaningless. Performance targets need a physical mid-range device.
- Maven Central rate-limits this environment at times. The builds use Google's mirror (`maven-central.storage-download.googleapis.com`) first.
