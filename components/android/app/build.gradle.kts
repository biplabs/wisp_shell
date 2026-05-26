plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.wispshell.app"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.wispshell.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
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

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.termux.termux-app:terminal-view:0.118.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

val repoRoot = rootProject.projectDir.parentFile.parentFile
val generatedJniLibs = layout.buildDirectory.dir("generated/jniLibs")
val llvmStrip = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip")

tasks.register<Exec>("cargoBuildWispshellCoreArm64") {
    workingDir = repoRoot
    commandLine("cargo", "build", "-p", "wispshell-core", "--target", "aarch64-linux-android")
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
