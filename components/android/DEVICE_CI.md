# Android Device CI

GitHub-hosted runners cannot install WispShell onto a phone or tablet because they cannot see local USB or LAN `adb` devices. Use a GitHub self-hosted runner on the machine that has the Android device connected.

## Runner

Create a repository self-hosted runner in GitHub:

```text
Settings -> Actions -> Runners -> New self-hosted runner
```

Install it on the machine that can run `adb devices`, and add these labels:

```text
self-hosted
linux
android-device
```

Install the local prerequisites needed for device access:

```sh
sudo apt-get update
sudo apt-get install -y adb
```

The workflow installs Java, Rust, the Android SDK, and NDK `27.2.12479018`.

## Device

Enable developer options and USB debugging on the Android device, then confirm the runner can see it:

```sh
adb devices -l
```

If more than one device can be connected, set the repository variable `WISPSHELL_ANDROID_SERIAL` to the target serial from `adb devices -l`.

## Workflow

`.github/workflows/android-device.yml` runs on every push to `main` and on manual dispatch. It builds `app-debug.apk` and installs it with:

```sh
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

`.github/workflows/ci.yml` runs normal Rust tests and builds the Android debug APK on GitHub-hosted runners.
