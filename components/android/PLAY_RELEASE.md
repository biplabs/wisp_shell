# Google Play Release

WispShell publishes as `com.biplabs.wisp`.

Google Play requires new Android app submissions to target Android 15 / API 35 or newer. This project uses:

- `compileSdk = 35`
- `targetSdk = 35`
- Android App Bundle output: `app/build/outputs/bundle/release/app-release.aab`

## Upload Key

Create and store the upload keystore outside the repo:

```sh
keytool -genkeypair \
  -v \
  -storetype PKCS12 \
  -keystore ~/.config/wispshell/play-upload-keystore.p12 \
  -alias wispshell-upload \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Export signing settings before building:

```sh
export WISPSHELL_UPLOAD_STORE_FILE="$HOME/.config/wispshell/play-upload-keystore.p12"
export WISPSHELL_UPLOAD_STORE_PASSWORD="..."
export WISPSHELL_UPLOAD_KEY_ALIAS="wispshell-upload"
export WISPSHELL_UPLOAD_KEY_PASSWORD="..."
```

Then build:

```sh
./gradlew bundleRelease
```

Verify the bundle is signed:

```sh
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
```

## Play Console

In Play Console, create the app using package name `com.biplabs.wisp`, enable Play App Signing, and upload the generated `.aab` to an internal testing release first.
