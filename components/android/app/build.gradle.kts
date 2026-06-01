plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.github.triplet.play") version "3.13.0"
}

val releaseStoreFile = providers.environmentVariable("WISPSHELL_UPLOAD_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("WISPSHELL_UPLOAD_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("WISPSHELL_UPLOAD_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("WISPSHELL_UPLOAD_KEY_PASSWORD").orNull
val ciVersionCode = providers.environmentVariable("WISPSHELL_VERSION_CODE").orNull?.toIntOrNull()
val ciVersionName = providers.environmentVariable("WISPSHELL_VERSION_NAME").orNull
val hasReleaseSigning =
    !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.biplabs.wisp"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.biplabs.wisp"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode ?: 3
        versionName = ciVersionName ?: "0.1.1"
    }

    buildFeatures {
        compose = true
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(checkNotNull(releaseStoreFile))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "**/libandroidx.graphics.path.so",
                "**/libtermux.so",
                "**/libwispshell_core.so",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

play {
    defaultToAppBundles.set(true)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.termux.termux-app:terminal-view:0.118.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

val repoRoot = rootProject.projectDir.parentFile.parentFile
val generatedJniLibs = layout.buildDirectory.dir("generated/jniLibs")
val llvmStrip = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip")
val llvmBin = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")

tasks.register<Exec>("cargoBuildWispshellCoreArm64") {
    workingDir = repoRoot
    commandLine("cargo", "build", "-p", "wispshell-core", "--target", "aarch64-linux-android")
    environment(
        "CC_aarch64_linux_android",
        llvmBin.resolve("aarch64-linux-android26-clang").absolutePath,
    )
    environment("AR_aarch64_linux_android", llvmBin.resolve("llvm-ar").absolutePath)
    environment(
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER",
        llvmBin.resolve("aarch64-linux-android26-clang").absolutePath,
    )
}

tasks.register<Copy>("copyWispshellCoreArm64") {
    dependsOn("cargoBuildWispshellCoreArm64")
    from(repoRoot.resolve("target/aarch64-linux-android/debug")) {
        include("libwispshell_core.so")
    }
    into(generatedJniLibs.map { it.dir("arm64-v8a") })
    doLast {
        val library = generatedJniLibs.get().dir("arm64-v8a").file("libwispshell_core.so").asFile
        exec {
            commandLine(llvmStrip.absolutePath, "--strip-unneeded", library.absolutePath)
        }
    }
}

tasks.named("preBuild") {
    dependsOn("copyWispshellCoreArm64")
}
