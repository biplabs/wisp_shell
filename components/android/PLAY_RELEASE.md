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

## GitHub Uploads

The `Google Play` workflow publishes a signed release app bundle to the `internal` track on every push to `main`. It can also be run manually for `internal`, `alpha`, `beta`, or `production`.

Create a Play Console service account with permission to release this app to testing tracks, then add these GitHub repository secrets:

```text
ANDROID_PUBLISHER_CREDENTIALS
WISPSHELL_UPLOAD_KEYSTORE_BASE64
WISPSHELL_UPLOAD_STORE_PASSWORD
WISPSHELL_UPLOAD_KEY_ALIAS
WISPSHELL_UPLOAD_KEY_PASSWORD
```

`ANDROID_PUBLISHER_CREDENTIALS` is the raw service-account JSON. `WISPSHELL_UPLOAD_KEYSTORE_BASE64` is the base64-encoded contents of the upload keystore:

```sh
base64 -w0 ~/.config/wispshell/play-upload-keystore.p12
```

GitHub sets a unique version automatically with `WISPSHELL_VERSION_CODE=1000 + GITHUB_RUN_NUMBER` and `WISPSHELL_VERSION_NAME=0.1.<run number>`.
